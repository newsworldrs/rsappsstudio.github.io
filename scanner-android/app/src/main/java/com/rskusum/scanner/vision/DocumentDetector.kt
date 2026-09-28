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
    private const val MAX_AREA_FRACTION = 0.97
    private const val MAX_CANDIDATES_PER_MAP = 6

    /** Optional trace of candidate scoring, for offline tuning. */
    @Volatile var debugLog: ((String) -> Unit)? = null

    /**
     * @param gray 8-bit single channel image (upright).
     * @param prev the outline found in the previous frame, if any; candidates close to it are
     *   preferred so the overlay doesn't hop between similar-scoring shapes.
     * @return quad in normalized coordinates, or null when no document is visible.
     */
    fun detect(gray: Mat, maxDim: Int = 480, prev: Quad? = null): Quad? {
        val scale = min(1.0, maxDim.toDouble() / max(gray.cols(), gray.rows()))
        val small = Mat()
        val closed = Mat()
        val blur = Mat()
        val gx = Mat()
        val gy = Mat()
        val mag = Mat()
        val bin = Mat()
        val edges = Mat()
        try {
            Imgproc.resize(gray, small, Size(gray.cols() * scale, gray.rows() * scale), 0.0, 0.0, Imgproc.INTER_AREA)
            val k = max(5, (max(small.cols(), small.rows()) / 50) or 1)
            Imgproc.morphologyEx(small, closed, Imgproc.MORPH_CLOSE, rect(k))
            Imgproc.GaussianBlur(closed, blur, Size(5.0, 5.0), 0.0)
            val w = blur.cols()
            val h = blur.rows()
            val area = w.toDouble() * h

            // Scene-adaptive thresholds: the median gradient is the noise/texture floor, so a
            // faint paper edge on a white table and a book on a busy bedsheet are both handled.
            Imgproc.Sobel(blur, gx, CvType.CV_32F, 1, 0, 3)
            Imgproc.Sobel(blur, gy, CvType.CV_32F, 0, 1, 3)
            Core.magnitude(gx, gy, mag)
            val magArr = FloatArray(w * h)
            mag.get(0, 0, magArr)
            val med = percentile(magArr, 0.5)
            val hi = max(3.5 * med, 10.0)
            val lo = max(0.45 * hi, 1.5 * med)

            val candidates = ArrayList<Array<Point>>()
            val otsu = Imgproc.threshold(blur, bin, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)
            val thresholds = listOf(lo to hi, 0.5 * otsu to otsu, 25.0 to 75.0)
            val k3 = rect(3)
            for ((l, u) in thresholds) {
                Imgproc.Canny(blur, edges, l, u, 3, true)
                Imgproc.dilate(edges, edges, k3)
                // Close shapes against the frame so a page running off-screen still forms a region.
                Imgproc.rectangle(edges, Point(0.0, 0.0), Point(w - 1.0, h - 1.0), Scalar(255.0), 1)
                collectQuads(edges, area, candidates)
            }
            collectQuads(bin, area, candidates)
            // Line-based candidates: bridges edges broken by shadows / glare / off-frame corners.
            Imgproc.Canny(blur, edges, lo, hi, 3, true)
            collectLineQuads(edges, w, h, candidates)
            if (candidates.isEmpty()) return null

            // Support map: gradient magnitude, max-filtered so +-1px misalignment still counts.
            Imgproc.dilate(mag, mag, k3)
            mag.get(0, 0, magArr)
            val supportThr = max(lo, 2.5 * med)

            var best: Array<Point>? = null
            var bestScore = 0.0
            for (q0 in candidates) {
                val q = orderPoints(q0)
                val sides = sideSupport(q, magArr, w, h, supportThr)
                debugLog?.invoke(
                    "cand area=%.3f sides=%s pts=%s".format(
                        polygonArea(q) / area, sides?.joinToString { "%.2f".format(it) } ?: "frame",
                        q.joinToString { "(%.0f,%.0f)".format(it.x, it.y) },
                    )
                )
                if (sides == null) continue
                val mean = sides.average()
                val weakest = sides.min()
                if (weakest < 0.55 || mean < 0.7) continue
                // The weakest side dominates: a side overshooting the real corner loses support.
                var score = polygonArea(q) / area * mean * mean * weakest * weakest
                if (prev != null) {
                    val p = prev.points
                    val d = q.indices.maxOf { i -> hypot(q[i].x / w - p[i].x, q[i].y / h - p[i].y) }
                    if (d < 0.06) score *= 1.4
                }
                if (score > bestScore) { bestScore = score; best = q }
            }
            val q = best ?: return null
            fun n(pt: Point) = NPoint((pt.x / w).toFloat().coerceIn(0f, 1f), (pt.y / h).toFloat().coerceIn(0f, 1f))
            return Quad(n(q[0]), n(q[1]), n(q[2]), n(q[3]))
        } finally {
            small.release(); closed.release(); blur.release(); gx.release(); gy.release(); mag.release()
            bin.release(); edges.release()
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

    /** Tiny thumbnail of the whole frame, for camera-steadiness and scene-change checks. */
    fun sceneSignature(gray: Mat): ByteArray {
        val out = Mat()
        Imgproc.resize(gray, out, Size(24.0, 32.0), 0.0, 0.0, Imgproc.INTER_AREA)
        val bytes = ByteArray(24 * 32)
        out.get(0, 0, bytes)
        out.release()
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

    /** Adds up to [MAX_CANDIDATES_PER_MAP] convex 4-gons (largest first) found in [binary]. */
    private fun collectQuads(binary: Mat, imgArea: Double, out: MutableList<Array<Point>>) {
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(binary.clone(), contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
        hierarchy.release()
        val hulls = ArrayList<Pair<MatOfPoint2f, Double>>()
        val hullIdx = MatOfInt()
        for (c in contours) {
            // Open edge chains (a page outline broken by noise) have ~zero contour area, so filter
            // on the convex hull instead of the contour itself.
            if (c.rows() < 4) { c.release(); continue }
            Imgproc.convexHull(c, hullIdx)
            val cPts = c.toArray()
            c.release()
            val hull = MatOfPoint2f(*hullIdx.toArray().map { cPts[it] }.toTypedArray())
            val a = Imgproc.contourArea(hull)
            if (a < MIN_AREA_FRACTION * imgArea || a > MAX_AREA_FRACTION * imgArea) { hull.release(); continue }
            hulls += hull to a
        }
        hullIdx.release()
        hulls.sortByDescending { it.second }
        var added = 0
        for ((hull, _) in hulls) {
            if (added >= MAX_CANDIDATES_PER_MAP) { hull.release(); continue }
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
            if (!convex || polygonArea(quad) < MIN_AREA_FRACTION * imgArea) continue
            out += quad
            added++
        }
    }

    /** An infinite line n·p = c (unit normal n) with the total length of its supporting segments. */
    private class Line(val nx: Double, val ny: Double, val c: Double, var weight: Double, val border: Boolean = false) {
        /** Angle of the line direction in [0, 180). */
        val angle: Double get() = (Math.toDegrees(kotlin.math.atan2(nx, -ny)) + 360.0) % 180.0
        val horizontalish: Boolean get() = angle < 45 || angle > 135
    }

    private fun collectLineQuads(edges: Mat, w: Int, h: Int, out: MutableList<Array<Point>>) {
        val segs = Mat()
        val minLen = 0.12 * min(w, h)
        Imgproc.HoughLinesP(edges, segs, 1.0, Math.PI / 180, 25, minLen, 12.0)
        val lines = ArrayList<Line>()
        for (i in 0 until segs.rows()) {
            val s = segs.get(i, 0)
            val dx = s[2] - s[0]
            val dy = s[3] - s[1]
            val len = hypot(dx, dy)
            if (len < 1) continue
            val nx = -dy / len
            val ny = dx / len
            val c = nx * s[0] + ny * s[1]
            // merge with an existing near-identical line
            val match = lines.firstOrNull { l ->
                val dot = l.nx * nx + l.ny * ny
                kotlin.math.abs(dot) > 0.995 && kotlin.math.abs(l.c - if (dot > 0) c else -c) < 6.0
            }
            if (match != null) match.weight += len else lines += Line(nx, ny, c, len)
        }
        segs.release()
        if (lines.size < 2) return
        val hs = lines.filter { it.horizontalish }.sortedByDescending { it.weight }.take(7).toMutableList()
        val vs = lines.filter { !it.horizontalish }.sortedByDescending { it.weight }.take(7).toMutableList()
        // Frame borders as fallback sides for pages extending off-screen.
        hs += Line(0.0, 1.0, 0.0, 0.0, true); hs += Line(0.0, 1.0, h - 1.0, 0.0, true)
        vs += Line(1.0, 0.0, 0.0, 0.0, true); vs += Line(1.0, 0.0, w - 1.0, 0.0, true)

        fun mid(l: Line, isH: Boolean): Double =
            // position of the line across the image centre: y for horizontals, x for verticals
            if (isH) (l.c - l.nx * w / 2) / l.ny else (l.c - l.ny * h / 2) / l.nx

        val area = w.toDouble() * h
        val margin = 0.04 * max(w, h)
        for (i in hs.indices) for (j in hs.indices) {
            if (i == j) continue
            val top = hs[i]; val bottom = hs[j]
            if (mid(top, true) >= mid(bottom, true) - 0.2 * h) continue
            for (a in vs.indices) for (b in vs.indices) {
                if (a == b) continue
                val left = vs[a]; val right = vs[b]
                if (mid(left, false) >= mid(right, false) - 0.2 * w) continue
                if (top.border && bottom.border || left.border && right.border) continue
                if (listOf(top, bottom, left, right).count { it.border } > 1) continue
                val tl = cross(top, left) ?: continue
                val tr = cross(top, right) ?: continue
                val br = cross(bottom, right) ?: continue
                val bl = cross(bottom, left) ?: continue
                val q = arrayOf(tl, tr, br, bl)
                if (q.any { it.x < -margin || it.y < -margin || it.x > w + margin || it.y > h + margin }) continue
                val qa = polygonArea(q)
                if (qa < MIN_AREA_FRACTION * area || qa > MAX_AREA_FRACTION * area) continue
                val qi = MatOfPoint(*q.map { Point(it.x, it.y) }.toTypedArray())
                val convex = Imgproc.isContourConvex(qi)
                qi.release()
                if (!convex) continue
                out += Array(4) { Point(q[it].x.coerceIn(0.0, w - 1.0), q[it].y.coerceIn(0.0, h - 1.0)) }
            }
        }
    }

    private fun cross(a: Line, b: Line): Point? {
        val d = a.nx * b.ny - a.ny * b.nx
        if (kotlin.math.abs(d) < 1e-6) return null
        return Point((a.c * b.ny - a.ny * b.c) / d, (a.nx * b.c - a.c * b.nx) / d)
    }

    /**
     * Fraction of each side backed by a real intensity edge. Stretches lying on the frame border
     * count as supported (page extends off-screen), but a shape hugging the frame on 3+ sides is
     * the frame itself and is rejected (returns null).
     */
    private fun sideSupport(q: Array<Point>, mag: FloatArray, w: Int, h: Int, thr: Double): DoubleArray? {
        val out = DoubleArray(4)
        var borderSides = 0
        val onFrame = BooleanArray(4)
        for (i in 0 until 4) {
            val a = q[i]
            val b = q[(i + 1) % 4]
            val len = hypot(b.x - a.x, b.y - a.y)
            val n = max(10, (len / 2).toInt())
            var hit = 0
            var onBorder = 0
            for (j in 0 until n) {
                val t = 0.06 + 0.88 * j / (n - 1)
                val x = a.x + (b.x - a.x) * t
                val y = a.y + (b.y - a.y) * t
                if (x < 2.5 || y < 2.5 || x > w - 3.5 || y > h - 3.5) { onBorder++; hit++; continue }
                val xi = x.toInt().coerceIn(0, w - 1)
                val yi = y.toInt().coerceIn(0, h - 1)
                if (mag[yi * w + xi] >= thr) hit++
            }
            if (onBorder > n * 0.7) { borderSides++; onFrame[i] = true }
            out[i] = hit.toDouble() / n
        }
        if (borderSides >= 2) return null
        // A side on the frame border is only plausible if the page really runs off-screen, i.e.
        // all its real sides are solid edges. Otherwise it is a genuine page stretched to the frame.
        if (borderSides == 1 && (0 until 4).any { !onFrame[it] && out[it] < 0.85 }) return null
        return out
    }

    /** Histogram percentile (quarter-unit bins up to 1024): O(n), no sorting on the camera thread. */
    private fun percentile(values: FloatArray, p: Double): Double {
        val bins = IntArray(4096)
        for (v in values) bins[(v * 4).toInt().coerceIn(0, 4095)]++
        val target = (values.size * p).toLong()
        var acc = 0L
        for (i in bins.indices) {
            acc += bins[i]
            if (acc >= target) return i / 4.0
        }
        return 1024.0
    }

    private fun polygonArea(p: Array<Point>): Double {
        var s = 0.0
        for (i in p.indices) {
            val a = p[i]
            val b = p[(i + 1) % p.size]
            s += a.x * b.y - b.x * a.y
        }
        return abs(s) / 2
    }

    /** Clockwise order starting at the corner closest to the image origin; robust to 45° rotation. */
    private fun orderPoints(p: Array<Point>): Array<Point> {
        val cx = p.sumOf { it.x } / 4
        val cy = p.sumOf { it.y } / 4
        val sorted = p.sortedBy { kotlin.math.atan2(it.y - cy, it.x - cx) }
        val start = sorted.indices.minBy { sorted[it].x + sorted[it].y }
        return Array(4) { sorted[(start + it) % 4] }
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
