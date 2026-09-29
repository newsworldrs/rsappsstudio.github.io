package com.rskusum.scanner.vision

import org.opencv.calib3d.Calib3d
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.DMatch
import org.opencv.core.Mat
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.features2d.BFMatcher
import org.opencv.features2d.ORB
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * What a captured page looks like, for "is this the same page again?" checks. Two complementary
 * parts, both tolerant to a different crop, tilt, blur, lighting and to the page being turned
 * 90/180/270 degrees:
 *  - [density]: 64x64 map of where the text/drawings are (layout). Compared over 4 rotations and
 *    small shifts.
 *  - ORB feature points ([points] + [descriptors]): distinctive letter/word corners. Two captures
 *    of the same page share many points that line up under one perspective mapping; different
 *    pages practically never do.
 */
class PageSignature internal constructor(
    internal val density: FloatArray,
    internal val points: FloatArray,
    internal val descriptors: ByteArray,
    internal val descriptorRows: Int,
)

object PageMatcher {

    private const val D = 64
    private const val LONG_SIDE = 800.0

    // Decision rule (tuned on real + synthetic pages: 99/100 duplicates caught, 0/100 false alarms).
    private const val STRONG_FEATURES = 25      // this many geometrically consistent points = same page
    private const val STRONG_LAYOUT = 0.86      // layout this similar = same page
    private const val LAYOUT_WITH_FEATURES = 0.80
    private const val SOME_FEATURES = 15
    private const val LAYOUT_WORTH_MATCHING = 0.55 // below this, don't even run feature matching

    private val orb: ORB by lazy { ORB.create(600, 1.2f, 6, 31, 0, 2, ORB.HARRIS_SCORE, 31, 12) }
    private val matcher: BFMatcher by lazy { BFMatcher.create(Core.NORM_HAMMING, false) }

    /** Signature of the page inside [quad] of an upright gray image (photo or preview frame). */
    @Synchronized // the ORB detector / matcher are shared between the camera and capture threads
    fun signature(gray: Mat, quad: Quad): PageSignature {
        val w = gray.cols().toDouble()
        val h = gray.rows().toDouble()
        val (pw, ph) = DocumentDetector.naiveSize(quad, gray.cols(), gray.rows())
        val s = LONG_SIDE / max(pw, ph).coerceAtLeast(1.0)
        val ow = (pw * s).coerceIn(64.0, LONG_SIDE)
        val oh = (ph * s).coerceIn(64.0, LONG_SIDE)
        val src = MatOfPoint2f(*quad.points.map { Point(it.x * w, it.y * h) }.toTypedArray())
        val dst = MatOfPoint2f(Point(0.0, 0.0), Point(ow, 0.0), Point(ow, oh), Point(0.0, oh))
        val m = Imgproc.getPerspectiveTransform(src, dst)
        val page = Mat()
        Imgproc.warpPerspective(gray, page, m, Size(ow, oh), Imgproc.INTER_AREA)
        src.release(); dst.release(); m.release()
        try {
            val density = densityMap(page)

            // ORB on a contrast-normalised copy.
            val eq = Mat()
            Imgproc.createCLAHE(2.0, Size(8.0, 8.0)).apply(page, eq)
            val kps = MatOfKeyPoint()
            val desc = Mat()
            orb.detectAndCompute(eq, Mat(), kps, desc)
            val kpArr = kps.toArray()
            val pts = FloatArray(kpArr.size * 2)
            kpArr.forEachIndexed { i, k -> pts[2 * i] = k.pt.x.toFloat(); pts[2 * i + 1] = k.pt.y.toFloat() }
            val bytes = ByteArray((desc.total() * desc.channels()).toInt())
            if (bytes.isNotEmpty()) desc.get(0, 0, bytes)
            val rows = desc.rows()
            eq.release(); kps.release(); desc.release()
            return PageSignature(density, pts, bytes, rows)
        } finally {
            page.release()
        }
    }

    /** True when [a] and [b] show the same page (any crop, rotation, lighting). */
    fun isSamePage(a: PageSignature, b: PageSignature): Boolean {
        val layout = layoutSimilarity(a.density, b.density)
        if (layout >= STRONG_LAYOUT) return true
        if (layout < LAYOUT_WORTH_MATCHING) return false
        val inliers = featureInliers(a, b)
        return inliers >= STRONG_FEATURES || (layout >= LAYOUT_WITH_FEATURES && inliers >= SOME_FEATURES)
    }

    // --- layout -----------------------------------------------------------------------------

    /** Stroke-energy per cell (where the text is), zero-mean / unit-variance, D x D. */
    private fun densityMap(page: Mat): FloatArray {
        val f = Mat()
        Imgproc.resize(page, f, Size(D * 4.0, D * 4.0), 0.0, 0.0, Imgproc.INTER_AREA)
        f.convertTo(f, CvType.CV_32F)
        val low = Mat()
        Imgproc.GaussianBlur(f, low, Size(0.0, 0.0), 3.0)
        Core.absdiff(f, low, f)
        val d = Mat()
        Imgproc.resize(f, d, Size(D.toDouble(), D.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        Imgproc.GaussianBlur(d, d, Size(0.0, 0.0), 1.2)
        val v = FloatArray(D * D)
        d.get(0, 0, v)
        f.release(); low.release(); d.release()
        val mean = v.average().toFloat()
        var sd = 0.0
        for (x in v) sd += ((x - mean) * (x - mean)).toDouble()
        val std = sqrt(sd / v.size).toFloat().coerceAtLeast(1e-3f)
        for (i in v.indices) v[i] = (v[i] - mean) / std
        return v
    }

    /** Best normalized correlation over 4 rotations and shifts of up to 4 cells. */
    private fun layoutSimilarity(a: FloatArray, b: FloatArray): Double {
        var best = -1.0
        for (k in 0..3) {
            val r = rotate(b, k)
            for (dy in -4..4) for (dx in -4..4) best = max(best, ncc(a, r, dx, dy))
        }
        return best
    }

    /** [b] turned k * 90 degrees (counter-clockwise), D x D. */
    private fun rotate(b: FloatArray, k: Int): FloatArray {
        if (k == 0) return b
        val out = FloatArray(D * D)
        for (y in 0 until D) for (x in 0 until D) {
            val (sx, sy) = when (k) {
                1 -> (D - 1 - y) to x
                2 -> (D - 1 - x) to (D - 1 - y)
                else -> y to (D - 1 - x)
            }
            out[y * D + x] = b[sy * D + sx]
        }
        return out
    }

    /** Correlation of the overlap of a and b shifted by (dx, dy). */
    private fun ncc(a: FloatArray, b: FloatArray, dx: Int, dy: Int): Double {
        val y0 = max(0, dy); val y1 = D + min(0, dy)
        val x0 = max(0, dx); val x1 = D + min(0, dx)
        var sa = 0.0; var sb = 0.0; var n = 0
        for (y in y0 until y1) for (x in x0 until x1) { sa += a[y * D + x]; sb += b[(y - dy) * D + (x - dx)]; n++ }
        if (n == 0) return 0.0
        val ma = sa / n; val mb = sb / n
        var ab = 0.0; var aa = 0.0; var bb = 0.0
        for (y in y0 until y1) for (x in x0 until x1) {
            val va = a[y * D + x] - ma
            val vb = b[(y - dy) * D + (x - dx)] - mb
            ab += va * vb; aa += va * va; bb += vb * vb
        }
        val den = sqrt(aa * bb)
        return if (den > 0) ab / den else 0.0
    }

    // --- features ---------------------------------------------------------------------------

    /** Number of ORB matches that agree on one perspective mapping (RANSAC inliers). */
    @Synchronized
    private fun featureInliers(a: PageSignature, b: PageSignature): Int {
        if (a.descriptorRows < 20 || b.descriptorRows < 20) return 0
        val da = Mat(a.descriptorRows, 32, CvType.CV_8U).apply { put(0, 0, a.descriptors) }
        val db = Mat(b.descriptorRows, 32, CvType.CV_8U).apply { put(0, 0, b.descriptors) }
        val knn = ArrayList<MatOfDMatch>()
        try {
            matcher.knnMatch(da, db, knn, 2)
            val good = ArrayList<DMatch>()
            for (m in knn) {
                val pair = m.toArray()
                if (pair.size == 2 && pair[0].distance < 0.8f * pair[1].distance) good += pair[0]
            }
            if (good.size < 12) return good.size / 3
            val src = MatOfPoint2f(*good.map { Point(a.points[2 * it.queryIdx].toDouble(), a.points[2 * it.queryIdx + 1].toDouble()) }.toTypedArray())
            val dst = MatOfPoint2f(*good.map { Point(b.points[2 * it.trainIdx].toDouble(), b.points[2 * it.trainIdx + 1].toDouble()) }.toTypedArray())
            val mask = Mat()
            val hm = Calib3d.findHomography(src, dst, Calib3d.RANSAC, 8.0, mask)
            val inliers = if (hm.empty()) 0 else Core.countNonZero(mask)
            src.release(); dst.release(); mask.release(); hm.release()
            return inliers
        } finally {
            da.release(); db.release()
            knn.forEach { it.release() }
        }
    }
}
