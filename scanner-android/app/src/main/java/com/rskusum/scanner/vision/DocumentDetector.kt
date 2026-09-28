package com.rskusum.scanner.vision

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Classical (non-ML) document boundary detector.
 *
 * Pipeline, tuned on synthetic perspective scenes including white paper on light,
 * textured backgrounds:
 *  1. Downscale, morphological close (erases text so the page becomes a flat blob).
 *  2. Several edge maps (Otsu-driven Canny at three sensitivities) plus an Otsu mask.
 *  3. Convex hull of each contour -> polygon approximation with growing epsilon until 4 corners.
 *  4. Every quad candidate is scored by area x edge support (fraction of its outline lying on
 *     real image edges), which rejects bogus quads coming from lighting gradients.
 *  5. [refine] fits lines to high-resolution edge pixels along each side and intersects them,
 *     giving ~1-2px corner accuracy on the full-resolution photo.
 */
object DocumentDetector {

    private const val MIN_AREA_FRACTION = 0.08
    private const val MIN_EDGE_SUPPORT = 0.6

    /**
     * @param gray 8-bit single channel image (upright).
     * @return quad in normalized coordinates, or null when no document is visible.
     */
    fun detect(gray: Mat, maxDim: Int = 480): Quad? {
        val scale = min(1.0, maxDim.toDouble() / max(gray.cols(), gray.rows()))
        val small = Mat()
        val closed = Mat()
        val blur = Mat()
        val bin = Mat()
        val edges = Mat()
        val support = Mat()
        try {
            Imgproc.resize(gray, small, Size(gray.cols() * scale, gray.rows() * scale), 0.0, 0.0, Imgproc.INTER_AREA)
            Imgproc.morphologyEx(small, closed, Imgproc.MORPH_CLOSE, rect(9))
            Imgproc.GaussianBlur(closed, blur, Size(5.0, 5.0), 0.0)
            val area = blur.rows().toDouble() * blur.cols()

            val otsu = Imgproc.threshold(blur, bin, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)
            val candidates = ArrayList<Pair<Array<Point>, Double>>()
            val thresholds = listOf(0.5 * otsu to otsu, 0.25 * otsu to 0.5 * otsu, 10.0 to 30.0)
            val k3 = rect(3)
            for ((lo, hi) in thresholds) {
                Imgproc.Canny(blur, edges, lo, hi)
                Imgproc.dilate(edges, edges, k3)
                findQuad(edges, area)?.let { candidates += it }
            }
            findQuad(bin, area)?.let { candidates += it }
            if (candidates.isEmpty()) return null

            Imgproc.Canny(blur, support, 15.0, 45.0)
            Imgproc.dilate(support, support, rect(5))

            var best: Array<Point>? = null
            var bestScore = 0.0
            for ((q, a) in candidates) {
                val s = edgeSupport(q, support)
                if (s >= MIN_EDGE_SUPPORT && a * s > bestScore) {
                    bestScore = a * s
                    best = q
                }
            }
            val q = best ?: return null
            val w = blur.cols().toDouble()
            val h = blur.rows().toDouble()
            return orderToQuad(q, w, h)
        } finally {
            small.release(); closed.release(); blur.release(); bin.release(); edges.release(); support.release()
        }
    }

    /**
     * Sub-pixel style corner refinement on a (larger) grayscale image.
     * Each side is re-estimated with a robust line fit to edge pixels within a narrow band.
     */
    fun refine(gray: Mat, quad: Quad, maxDim: Int = 1400): Quad {
        val scale = min(1.0, maxDim.toDouble() / max(gray.cols(), gray.rows()))
        val small = Mat()
        val closed = Mat()
        val blur = Mat()
        val edges = Mat()
        try {
            Imgproc.resize(gray, small, Size(gray.cols() * scale, gray.rows() * scale), 0.0, 0.0, Imgproc.INTER_AREA)
            val w = small.cols().toDouble()
            val h = small.rows().toDouble()
            val k = max(3, (max(w, h) / 60).toInt() or 1)
            Imgproc.morphologyEx(small, closed, Imgproc.MORPH_CLOSE, rect(k))
            Imgproc.GaussianBlur(closed, blur, Size(5.0, 5.0), 0.0)
            Imgproc.Canny(blur, edges, 15.0, 45.0)

            val pts = quad.points.map { Point(it.x * w, it.y * h) }
            val band = max(4, (0.012 * hypot(w, h)).toInt())
            val lines = arrayOfNulls<DoubleArray>(4)
            val mask = Mat.zeros(edges.size(), CvType.CV_8UC1)
            val masked = Mat()
            val nz = Mat()
            val nzf = Mat()
            val line = Mat()
            for (i in 0 until 4) {
                val a = pts[i]
                val b = pts[(i + 1) % 4]
                mask.setTo(Scalar(0.0))
                Imgproc.line(mask, a, b, Scalar(255.0), band * 2)
                Core.bitwise_and(edges, mask, masked)
                Core.findNonZero(masked, nz)
                val len = hypot(b.x - a.x, b.y - a.y)
                if (nz.rows() < 0.3 * len) continue
                nz.convertTo(nzf, CvType.CV_32FC2)
                Imgproc.fitLine(nzf, line, Imgproc.DIST_HUBER, 0.0, 0.01, 0.01)
                lines[i] = doubleArrayOf(line.get(0, 0)[0], line.get(1, 0)[0], line.get(2, 0)[0], line.get(3, 0)[0])
            }
            mask.release(); masked.release(); nz.release(); nzf.release(); line.release()

            val out = Array(4) { i ->
                val prev = lines[(i + 3) % 4]
                val cur = lines[i]
                var p: Point? = if (prev != null && cur != null) intersect(prev, cur) else null
                if (p == null || hypot(p.x - pts[i].x, p.y - pts[i].y) > band * 2.0) p = pts[i]
                NPoint((p.x / w).toFloat().coerceIn(0f, 1f), (p.y / h).toFloat().coerceIn(0f, 1f))
            }
            return Quad(out[0], out[1], out[2], out[3])
        } finally {
            small.release(); closed.release(); blur.release(); edges.release()
        }
    }

    /**
     * Real-world width/height ratio of the rectangle imaged by [quad], using the single-view
     * rectangle method of Zhang & He ("Whiteboard scanning and image enhancement", 2007).
     * It estimates the focal length from the perspective itself, so it is correct regardless of
     * how strongly the phone is tilted. Falls back to measured edge lengths when degenerate.
     */
    fun estimateAspect(quad: Quad, imgW: Int, imgH: Int): Double {
        val u0 = imgW / 2.0
        val v0 = imgH / 2.0
        fun v(p: NPoint) = doubleArrayOf(p.x * imgW - u0, p.y * imgH - v0, 1.0)
        val m1 = v(quad.tl); val m2 = v(quad.tr); val m3 = v(quad.bl); val m4 = v(quad.br)
        val (nw, nh) = naiveSize(quad, imgW, imgH)
        val naive = nw / nh
        val k2 = dot(cross(m1, m4), m3) / dot(cross(m2, m4), m3)
        val k3 = dot(cross(m1, m4), m2) / dot(cross(m3, m4), m2)
        val n2 = DoubleArray(3) { k2 * m2[it] - m1[it] }
        val n3 = DoubleArray(3) { k3 * m3[it] - m1[it] }
        val den = n2[2] * n3[2]
        if (abs(den) > 1e-9 && k2.isFinite() && k3.isFinite()) {
            val f2 = -(n2[0] * n3[0] + n2[1] * n3[1]) / den
            if (f2 > 0) {
                val r = sqrt(
                    (n2[0] * n2[0] + n2[1] * n2[1] + n2[2] * n2[2] * f2) /
                        (n3[0] * n3[0] + n3[1] * n3[1] + n3[2] * n3[2] * f2)
                )
                if (r.isFinite() && r / naive in 0.6..1.6) return r
            }
        }
        return naive
    }

    /** Longest opposite edges of the quad in pixels (width, height). */
    fun naiveSize(quad: Quad, imgW: Int, imgH: Int): Pair<Double, Double> {
        fun d(a: NPoint, b: NPoint) = hypot((a.x - b.x) * imgW.toDouble(), (a.y - b.y) * imgH.toDouble())
        val w = max(d(quad.tl, quad.tr), d(quad.bl, quad.br))
        val h = max(d(quad.tl, quad.bl), d(quad.tr, quad.br))
        return w to h
    }

    /**
     * Perspective-correct [src] (any type) to a flat, aspect-true page.
     * @param forcedAspect optional width/height ratio (for ID cards etc.); orientation is kept.
     */
    fun warp(src: Mat, quad: Quad, forcedAspect: Double? = null, maxSide: Int = 4000): Mat {
        val w0 = src.cols()
        val h0 = src.rows()
        var ratio = estimateAspect(quad, w0, h0)
        if (forcedAspect != null) ratio = if (ratio >= 1) max(forcedAspect, 1 / forcedAspect) else min(forcedAspect, 1 / forcedAspect)
        val (nw, nh) = naiveSize(quad, w0, h0)
        var outW = nw
        var outH = outW / ratio
        if (outH < nh) { outH = nh; outW = outH * ratio }
        val s = min(1.0, maxSide / max(outW, outH))
        outW *= s; outH *= s
        val ow = max(1, outW.toInt())
        val oh = max(1, outH.toInt())

        val srcPts = MatOfPoint2f(*quad.points.map { Point(it.x * w0.toDouble(), it.y * h0.toDouble()) }.toTypedArray())
        val dstPts = MatOfPoint2f(Point(0.0, 0.0), Point(ow.toDouble(), 0.0), Point(ow.toDouble(), oh.toDouble()), Point(0.0, oh.toDouble()))
        val m = Imgproc.getPerspectiveTransform(srcPts, dstPts)
        val out = Mat()
        Imgproc.warpPerspective(src, out, m, Size(ow.toDouble(), oh.toDouble()), Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE)
        srcPts.release(); dstPts.release(); m.release()
        return out
    }

    /** Tiny grayscale fingerprint of the document content, used to notice a page flip. */
    fun signature(gray: Mat, quad: Quad): ByteArray {
        val w = gray.cols().toDouble()
        val h = gray.rows().toDouble()
        val srcPts = MatOfPoint2f(*quad.points.map { Point(it.x * w, it.y * h) }.toTypedArray())
        val dstPts = MatOfPoint2f(Point(0.0, 0.0), Point(24.0, 0.0), Point(24.0, 32.0), Point(0.0, 32.0))
        val m = Imgproc.getPerspectiveTransform(srcPts, dstPts)
        val out = Mat()
        Imgproc.warpPerspective(gray, out, m, Size(24.0, 32.0), Imgproc.INTER_AREA)
        Imgproc.GaussianBlur(out, out, Size(3.0, 3.0), 0.0)
        val bytes = ByteArray(24 * 32)
        out.get(0, 0, bytes)
        srcPts.release(); dstPts.release(); m.release(); out.release()
        return bytes
    }

    fun signatureDistance(a: ByteArray, b: ByteArray): Double {
        if (a.size != b.size) return Double.MAX_VALUE
        var s = 0L
        for (i in a.indices) s += abs((a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF))
        return s.toDouble() / a.size
    }

    // ---------------------------------------------------------------------------------------

    private fun rect(k: Int): Mat = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(k.toDouble(), k.toDouble()))

    private fun findQuad(binary: Mat, imgArea: Double): Pair<Array<Point>, Double>? {
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(binary.clone(), contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
        hierarchy.release()
        var best: Array<Point>? = null
        var bestArea = 0.0
        val hullIdx = MatOfInt()
        for (c in contours) {
            if (Imgproc.contourArea(c) < imgArea * 0.02) { c.release(); continue }
            Imgproc.convexHull(c, hullIdx)
            val cPts = c.toArray()
            val hull = MatOfPoint2f(*hullIdx.toArray().map { cPts[it] }.toTypedArray())
            c.release()
            val a = Imgproc.contourArea(hull)
            if (a < MIN_AREA_FRACTION * imgArea || a <= bestArea) { hull.release(); continue }
            val peri = Imgproc.arcLength(hull, true)
            var quad: Array<Point>? = null
            for (eps in doubleArrayOf(0.02, 0.03, 0.045, 0.06, 0.08)) {
                val ap = MatOfPoint2f()
                Imgproc.approxPolyDP(hull, ap, eps * peri, true)
                if (ap.total() == 4L) quad = ap.toArray()
                ap.release()
                if (quad != null) break
            }
            hull.release()
            if (quad == null) continue
            val qi = MatOfPoint(*quad)
            val convex = Imgproc.isContourConvex(qi)
            qi.release()
            if (!convex) continue
            val q2 = MatOfPoint2f(*quad)
            val a4 = Imgproc.contourArea(q2)
            q2.release()
            if (a4 > bestArea) { bestArea = a4; best = quad }
        }
        hullIdx.release()
        return best?.let { it to bestArea }
    }

    private fun edgeSupport(q: Array<Point>, edges: Mat): Double {
        val m = Mat.zeros(edges.size(), CvType.CV_8UC1)
        val poly = MatOfPoint(*q.map { Point(Math.round(it.x).toDouble(), Math.round(it.y).toDouble()) }.toTypedArray())
        Imgproc.polylines(m, listOf(poly), true, Scalar(255.0), 2)
        val total = Core.countNonZero(m)
        Core.bitwise_and(m, edges, m)
        val hit = Core.countNonZero(m)
        m.release(); poly.release()
        return hit.toDouble() / max(total, 1)
    }

    /** Clockwise order starting at the corner closest to the image origin; robust to 45° rotation. */
    private fun orderToQuad(p: Array<Point>, w: Double, h: Double): Quad {
        val cx = p.sumOf { it.x } / 4
        val cy = p.sumOf { it.y } / 4
        val sorted = p.sortedBy { kotlin.math.atan2(it.y - cy, it.x - cx) }
        val start = sorted.indices.minBy { sorted[it].x + sorted[it].y }
        val o = List(4) { sorted[(start + it) % 4] }
        fun n(pt: Point) = NPoint((pt.x / w).toFloat().coerceIn(0f, 1f), (pt.y / h).toFloat().coerceIn(0f, 1f))
        return Quad(n(o[0]), n(o[1]), n(o[2]), n(o[3]))
    }

    private fun intersect(l1: DoubleArray, l2: DoubleArray): Point? {
        val (vx1, vy1, x1, y1) = l1
        val (vx2, vy2, x2, y2) = l2
        val d = vx1 * vy2 - vy1 * vx2
        if (abs(d) < 1e-6) return null
        val t = ((x2 - x1) * vy2 - (y2 - y1) * vx2) / d
        return Point(x1 + t * vx1, y1 + t * vy1)
    }

    private fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    )

    private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
}
