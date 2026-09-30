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
import kotlin.math.exp
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
            return pickBest(candidates, magArr, w, h, supportThr, prev)
        } finally {
            small.release(); closed.release(); blur.release(); gx.release(); gy.release(); mag.release()
            bin.release(); edges.release()
        }
    }

    /** Edge-map value above which the learned model reports a boundary. */
    private const val EDGE_PROB = 0.2

    /**
     * Finds the document in the output of [EdgeModel] (256x256 boundary strength). The model
     * already suppresses text, fabric patterns and noise, so the same geometric search as
     * [detect] (region contours + line pairs, scored by per-side support) becomes very reliable.
     */
    fun detectFromEdgeMap(prob: Mat, prev: Quad? = null): Quad? {
        val w = prob.cols()
        val h = prob.rows()
        val bin = Mat()
        val dil = Mat()
        try {
            Imgproc.threshold(prob, bin, EDGE_PROB, 255.0, Imgproc.THRESH_BINARY)
            bin.convertTo(bin, CvType.CV_8U)
            val candidates = ArrayList<Array<Point>>()
            collectLineQuads(bin, w, h, candidates)
            val closedEdges = Mat()
            Imgproc.dilate(bin, closedEdges, rect(3))
            Imgproc.rectangle(closedEdges, Point(0.0, 0.0), Point(w - 1.0, h - 1.0), Scalar(255.0), 1)
            collectQuads(closedEdges, w.toDouble() * h, candidates)
            closedEdges.release()
            if (candidates.isEmpty()) return null
            Imgproc.dilate(prob, dil, rect(3))
            val arr = FloatArray(w * h)
            dil.get(0, 0, arr)
            return pickBest(candidates, arr, w, h, EDGE_PROB, prev)
        } finally {
            bin.release(); dil.release()
        }
    }

    /** Scores candidates by area x per-side edge support; the weakest side dominates. */
    private fun pickBest(candidates: List<Array<Point>>, support: FloatArray, w: Int, h: Int, thr: Double, prev: Quad?): Quad? {
        val area = w.toDouble() * h
        var best: Array<Point>? = null
        var bestScore = 0.0
        for (q0 in candidates) {
            val q = orderPoints(q0)
            val sides = sideSupport(q, support, w, h, thr)
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
            // A side overshooting the real corner loses support, so it can't win on area alone.
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
            // Contrast-adaptive thresholds (faint paper edges on white tables still register).
            val gx = Mat(); val gy = Mat(); val mag = Mat()
            Imgproc.Sobel(blur, gx, CvType.CV_32F, 1, 0, 3)
            Imgproc.Sobel(blur, gy, CvType.CV_32F, 0, 1, 3)
            Core.magnitude(gx, gy, mag)
            val magArr = FloatArray(small.cols() * small.rows())
            mag.get(0, 0, magArr)
            gx.release(); gy.release(); mag.release()
            val med = percentile(magArr, 0.5)
            val hi = max(3.5 * med, 10.0)
            Imgproc.Canny(blur, edges, max(0.45 * hi, 1.5 * med), hi, 3, true)

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
     * Guided-frame crop. The user placed the page inside an on-screen guide frame, so each page
     * edge must lie in a narrow band around the corresponding frame side. Searching only there
     * makes the result robust where a global search fails:
     *  - faint edges (white paper on a white table) only compete with other lines in the band;
     *  - boxes, tables or photos printed on the page are further inside, and the *outermost*
     *    well-supported line in the band is taken, so the page border wins over inner borders.
     * A side with no convincing edge falls back to the frame side pushed out by [fallbackMargin].
     *
     * @param frame axis-aligned guide rectangle, normalized image coordinates.
     * @param edgeHint optional [EdgeModel] output for the same image (any size, CV_32F).
     */
    fun snapToFrame(gray: Mat, frame: Quad, edgeHint: Mat? = null, maxDim: Int = 1600, fallbackMargin: Float = 0.01f): Quad =
        snapToQuad(gray, frame, edgeHint, maxDim, fallbackMargin).quad

    /** Result of [snapToQuad]: the quad and how many of its 4 sides were matched to real edges. */
    class SnapResult(val quad: Quad, val sidesFound: Int)

    /**
     * Same band search as [snapToFrame], around any convex prior outline (guide frame, the user's
     * rough crop, or a coarse detection). [bandFraction] sets the search half-width relative to
     * the prior's shortest side.
     */
    fun snapToQuad(
        gray: Mat, prior: Quad, edgeHint: Mat? = null, maxDim: Int = 1600,
        fallbackMargin: Float = 0.01f, bandFraction: Double = 0.085,
    ): SnapResult {
        val scale = min(1.0, maxDim.toDouble() / max(gray.cols(), gray.rows()))
        val small = Mat()
        val closed = Mat()
        val blur = Mat()
        val edges = Mat()
        try {
            Imgproc.resize(gray, small, Size(gray.cols() * scale, gray.rows() * scale), 0.0, 0.0, Imgproc.INTER_AREA)
            val w = small.cols()
            val h = small.rows()
            val k = max(5, (max(w, h) / 80) or 1)
            Imgproc.morphologyEx(small, closed, Imgproc.MORPH_CLOSE, rect(k))
            Imgproc.GaussianBlur(closed, blur, Size(5.0, 5.0), 0.0)
            val gx = Mat(); val gy = Mat(); val mag = Mat()
            Imgproc.Sobel(blur, gx, CvType.CV_32F, 1, 0, 3)
            Imgproc.Sobel(blur, gy, CvType.CV_32F, 0, 1, 3)
            Core.magnitude(gx, gy, mag)
            val magArr = FloatArray(w * h)
            mag.get(0, 0, magArr)
            gx.release(); gy.release(); mag.release()
            val med = percentile(magArr, 0.5)
            val hi = max(3.0 * med, 8.0)
            Imgproc.Canny(blur, edges, max(0.4 * hi, 1.5 * med), hi, 3, true)
            if (edgeHint != null) {
                val hint = Mat()
                Imgproc.resize(edgeHint, hint, Size(w.toDouble(), h.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
                Imgproc.threshold(hint, hint, EDGE_PROB, 255.0, Imgproc.THRESH_BINARY)
                hint.convertTo(hint, CvType.CV_8U)
                Core.bitwise_or(edges, hint, edges)
                hint.release()
            }

            val corners = prior.points.map { Point(it.x.toDouble() * w, it.y.toDouble() * h) }.toTypedArray()
            val sideLen = DoubleArray(4) { hypot(corners[(it + 1) % 4].x - corners[it].x, corners[(it + 1) % 4].y - corners[it].y) }
            val band = bandFraction * sideLen.min()
            val cx = corners.sumOf { it.x } / 4
            val cy = corners.sumOf { it.y } / 4
            val pix = ByteArray(w * h)
            blur.get(0, 0, pix)

            var found = 0
            val lines = Array(4) { i ->
                val a = corners[i]
                val b = corners[(i + 1) % 4]
                findSideLine(edges, pix, w, h, a, b, cx, cy, band)?.also { found++ }
                    ?: run {
                        // Fallback: the prior's side itself, pushed out by the margin.
                        val m = fallbackMargin * sideLen[(i + 1) % 4]
                        val (ox, oy) = outwardNormal(a, b, cx, cy)
                        doubleArrayOf(b.x - a.x, b.y - a.y, a.x + ox * m, a.y + oy * m).normalizedDir()
                    }
            }
            val out = Array(4) { i ->
                var p = intersect(lines[(i + 3) % 4], lines[i])
                if (p == null || hypot(p.x - corners[i].x, p.y - corners[i].y) > band * 1.8) p = corners[i]
                NPoint((p.x / w).toFloat().coerceIn(0f, 1f), (p.y / h).toFloat().coerceIn(0f, 1f))
            }
            return SnapResult(Quad(out[0], out[1], out[2], out[3]), found)
        } finally {
            small.release(); closed.release(); blur.release(); edges.release()
        }
    }

    /**
     * How convincingly [quad] outlines a real object in [gray]: per side, the fraction of samples
     * with a clear brightness step across the side in the side's dominant direction (a page
     * border is consistently brighter or darker than its surroundings). Score in 0..1, driven by
     * the weakest side; a quad drifting onto text or background scores low.
     */
    fun scoreQuad(gray: Mat, quad: Quad, maxDim: Int = 1000): Double {
        val scale = min(1.0, maxDim.toDouble() / max(gray.cols(), gray.rows()))
        val small = Mat()
        val closed = Mat()
        try {
            Imgproc.resize(gray, small, Size(gray.cols() * scale, gray.rows() * scale), 0.0, 0.0, Imgproc.INTER_AREA)
            val w = small.cols(); val h = small.rows()
            Imgproc.morphologyEx(small, closed, Imgproc.MORPH_CLOSE, rect(max(5, (max(w, h) / 80) or 1)))
            Imgproc.GaussianBlur(closed, closed, Size(5.0, 5.0), 0.0)
            val pix = ByteArray(w * h)
            closed.get(0, 0, pix)
            fun at(x: Double, y: Double): Int {
                val xi = x.toInt(); val yi = y.toInt()
                return if (xi in 0 until w && yi in 0 until h) pix[yi * w + xi].toInt() and 0xFF else -1
            }
            val p = quad.points.map { Point(it.x.toDouble() * w, it.y.toDouble() * h) }
            val cx = p.sumOf { it.x } / 4; val cy = p.sumOf { it.y } / 4
            if (polygonArea(p.toTypedArray()) < 0.05 * w * h) return 0.0
            val sides = DoubleArray(4) { i ->
                val a = p[i]; val b = p[(i + 1) % 4]
                val (nx, ny) = outwardNormal(a, b, cx, cy)
                var pos = 0; var neg = 0; var usable = 0
                val n = 60
                for (j in 0 until n) {
                    val t = 0.08 + 0.84 * j / (n - 1)
                    val x = a.x + (b.x - a.x) * t; val y = a.y + (b.y - a.y) * t
                    val o = at(x + nx * 4, y + ny * 4); val iv = at(x - nx * 4, y - ny * 4)
                    if (o < 0 || iv < 0) continue // on the photo border: no evidence either way
                    usable++
                    val d = o - iv
                    if (d >= 4) pos++ else if (d <= -4) neg++
                }
                // A side hugging the photo border is a guess, not an edge: weak score.
                if (usable < n * 0.3) 0.3 else max(pos, neg).toDouble() / usable
            }
            return sides.average() * 0.4 + sides.min() * 0.6
        } finally {
            small.release(); closed.release()
        }
    }

    /**
     * Best-effort automatic outline for a captured photo, used by the crop screen's Auto button.
     * Candidates: every [priors] outline (guide frame, current crop, live detection) snapped to
     * nearby edges at two search widths, the learned-model detection ([edgeMap]) and the classic
     * detector - each refined on the full photo, then ranked by [scoreQuad].
     */
    fun autoDetect(gray: Mat, edgeMap: Mat?, priors: List<Quad>): Quad? {
        val cands = ArrayList<Quad>()
        // Generic priors so there is always a starting outline, even without a guide frame.
        val allPriors = priors + listOf(Quad.inset(0.06f), Quad.inset(0.14f))
        for (prior in allPriors) {
            for (bf in doubleArrayOf(0.085, 0.16)) {
                val snap = snapToQuad(gray, prior, bandFraction = bf)
                if (snap.sidesFound >= 2) cands += refine(gray, snap.quad)
            }
        }
        edgeMap?.let { m -> detectFromEdgeMap(m)?.let { cands += refine(gray, it) } }
        detect(gray, 640)?.let { cands += refine(gray, it) }
        detect(gray, 1000)?.let { cands += refine(gray, it) }
        var best: Quad? = null
        var bestScore = 0.0
        for (q in cands) {
            val s = scoreQuad(gray, q) * (0.6 + 0.4 * sqrt(q.area().toDouble().coerceAtMost(1.0)))
            debugLog?.invoke("auto cand score=%.3f %s".format(s, q))
            if (s > bestScore) { bestScore = s; best = q }
        }
        return if (bestScore >= 0.35) best else null
    }

    /**
     * Content fingerprint of the flattened page for duplicate detection. Built from the page's
     * fine detail (high-pass: text lines, drawings) at 96x128, so two different pages that share
     * a big dark area or similar brightness don't look alike. [FloatArray] is zero-mean /
     * unit-variance; the last element stores the detail energy (low = blank page / bare surface).
     */
    fun fingerprint(gray: Mat, quad: Quad): FloatArray {
        val w = gray.cols().toDouble(); val h = gray.rows().toDouble()
        val fw = 96; val fh = 128
        val src = MatOfPoint2f(*quad.points.map { Point(it.x * w, it.y * h) }.toTypedArray())
        val dst = MatOfPoint2f(Point(0.0, 0.0), Point(fw.toDouble(), 0.0), Point(fw.toDouble(), fh.toDouble()), Point(0.0, fh.toDouble()))
        val m = Imgproc.getPerspectiveTransform(src, dst)
        val out = Mat()
        Imgproc.warpPerspective(gray, out, m, Size(fw.toDouble(), fh.toDouble()), Imgproc.INTER_AREA)
        val f = Mat(); out.convertTo(f, CvType.CV_32F)
        val low = Mat(); Imgproc.GaussianBlur(f, low, Size(0.0, 0.0), 6.0)
        Core.subtract(f, low, f)
        Imgproc.GaussianBlur(f, f, Size(3.0, 3.0), 0.0)
        val v = FloatArray(fw * fh)
        f.get(0, 0, v)
        src.release(); dst.release(); m.release(); out.release(); f.release(); low.release()
        val mean = v.average().toFloat()
        var sd = 0.0
        for (x in v) sd += (x - mean) * (x - mean)
        val std = sqrt(sd / v.size).toFloat()
        val res = FloatArray(v.size + 1)
        for (i in v.indices) res[i] = (v[i] - mean) / std.coerceAtLeast(1e-3f)
        res[v.size] = std
        return res
    }

    /** Detail energy below this means a blank page / bare surface (nothing to compare). */
    const val BLANK_DETAIL = 2.0f

    fun isBlank(fp: FloatArray) = fp.last() < BLANK_DETAIL

    /** Correlation of two fingerprints: ~1 same page, low for different content. */
    fun fingerprintSimilarity(a: FloatArray, b: FloatArray): Double {
        if (a.size != b.size) return 0.0
        if (isBlank(a) && isBlank(b)) return 1.0 // two captures of an empty surface
        if (isBlank(a) || isBlank(b)) return 0.0
        var s = 0.0
        for (i in 0 until a.size - 1) s += a[i] * b[i]
        return s / (a.size - 1)
    }

    /** Guide rectangle of the given width/height [aspect], centred in a 3:4 portrait frame. */
    fun guideFrame(aspect: Double, margin: Double = 0.06): Quad {
        val fw = min(3 * (1 - 2 * margin), 4 * (1 - 2 * margin) * aspect)
        val fh = fw / aspect
        val nx = (fw / 3 / 2).toFloat()
        val ny = (fh / 4 / 2).toFloat()
        return Quad(NPoint(0.5f - nx, 0.5f - ny), NPoint(0.5f + nx, 0.5f - ny), NPoint(0.5f + nx, 0.5f + ny), NPoint(0.5f - nx, 0.5f + ny))
    }

    private fun DoubleArray.normalizedDir(): DoubleArray {
        val l = hypot(this[0], this[1]).coerceAtLeast(1e-9)
        return doubleArrayOf(this[0] / l, this[1] / l, this[2], this[3])
    }

    private fun outwardNormal(a: Point, b: Point, cx: Double, cy: Double): Pair<Double, Double> {
        val dx = b.x - a.x; val dy = b.y - a.y
        val l = hypot(dx, dy)
        var nx = -dy / l; var ny = dx / l
        if (nx * ((a.x + b.x) / 2 - cx) + ny * ((a.y + b.y) / 2 - cy) < 0) { nx = -nx; ny = -ny }
        return nx to ny
    }

    /**
     * Best page-edge line near the frame side a->b: straight segments within [band] of the side
     * and within 12° of its direction are grouped by offset; the outermost group covering at
     * least 30% of the side wins. Returns (vx, vy, x0, y0) or null.
     */
    private fun findSideLine(
        edges: Mat, pix: ByteArray, w: Int, h: Int,
        a: Point, b: Point, cx: Double, cy: Double, band: Double,
    ): DoubleArray? {
        val len = hypot(b.x - a.x, b.y - a.y)
        val dx = (b.x - a.x) / len
        val dy = (b.y - a.y) / len
        val (nx, ny) = outwardNormal(a, b, cx, cy)
        val mask = Mat.zeros(edges.size(), CvType.CV_8UC1)
        val ext = 0.1 * len
        val poly = MatOfPoint(
            Point(a.x - dx * ext + nx * band, a.y - dy * ext + ny * band),
            Point(b.x + dx * ext + nx * band, b.y + dy * ext + ny * band),
            Point(b.x + dx * ext - nx * band, b.y + dy * ext - ny * band),
            Point(a.x - dx * ext - nx * band, a.y - dy * ext - ny * band),
        )
        Imgproc.fillConvexPoly(mask, poly, Scalar(255.0))
        poly.release()
        val masked = Mat()
        Core.bitwise_and(edges, mask, masked)
        mask.release()
        val segs = Mat()
        Imgproc.HoughLinesP(masked, segs, 1.0, Math.PI / 360, 30, 0.08 * len, 0.03 * len)
        masked.release()

        // o0/o1: the segment's infinite line, as perpendicular offsets at both ends of the side.
        class Seg(val off: Double, val o0: Double, val o1: Double, val t0: Double, val t1: Double, val x0: Double, val y0: Double, val x1: Double, val y1: Double)
        val list = ArrayList<Seg>()
        val cosTol = kotlin.math.cos(Math.toRadians(12.0))
        for (i in 0 until segs.rows()) {
            val s = segs.get(i, 0)
            val sx = s[2] - s[0]; val sy = s[3] - s[1]
            val sl = hypot(sx, sy)
            if (sl < 1 || kotlin.math.abs((sx * dx + sy * dy) / sl) < cosTol) continue
            val mx = (s[0] + s[2]) / 2 - a.x; val my = (s[1] + s[3]) / 2 - a.y
            val off = mx * nx + my * ny
            val ta = (s[0] - a.x) * dx + (s[1] - a.y) * dy
            val tb = (s[2] - a.x) * dx + (s[3] - a.y) * dy
            val slope = (sx * nx + sy * ny) / (sx * dx + sy * dy)
            val tm = (ta + tb) / 2
            list += Seg(off, off - slope * tm, off + slope * (len - tm), min(ta, tb), max(ta, tb), s[0], s[1], s[2], s[3])
        }
        segs.release()
        if (list.isEmpty()) return null

        // Cluster segments lying on (nearly) the same infinite line: compare both end offsets,
        // so a slightly tilted page edge doesn't chain up with nearby noise.
        list.sortBy { it.off }
        val tol = 0.012 * len + 3
        val clusters = ArrayList<MutableList<Seg>>()
        for (s in list) {
            val home = clusters.firstOrNull { c ->
                kotlin.math.abs(c.map { it.o0 }.average() - s.o0) < tol && kotlin.math.abs(c.map { it.o1 }.average() - s.o1) < tol
            }
            if (home != null) home += s else clusters += mutableListOf(s)
        }
        fun coverage(c: List<Seg>): Double {
            val iv = c.map { it.t0.coerceIn(0.0, len) to it.t1.coerceIn(0.0, len) }.sortedBy { it.first }
            var covered = 0.0; var curS = -1.0; var curE = -1.0
            for ((s, e) in iv) {
                if (s > curE) { if (curE > curS) covered += curE - curS; curS = s; curE = e } else curE = max(curE, e)
            }
            if (curE > curS) covered += curE - curS
            return covered / len
        }
        fun fit(c: List<Seg>): DoubleArray {
            val pts = MatOfPoint2f(*c.flatMap { listOf(Point(it.x0, it.y0), Point(it.x1, it.y1)) }.toTypedArray())
            val line = Mat()
            Imgproc.fitLine(pts, line, Imgproc.DIST_HUBER, 0.0, 0.01, 0.01)
            val r = doubleArrayOf(line.get(0, 0)[0], line.get(1, 0)[0], line.get(2, 0)[0], line.get(3, 0)[0])
            pts.release(); line.release()
            return r
        }
        // A real page border has the same brightness step (paper vs. surface) all along it;
        // noise, fabric texture and shadows flip sign. Fraction of samples agreeing in sign.
        fun polarity(l: DoubleArray): Double {
            var pos = 0; var neg = 0; val n = 60
            for (j in 0 until n) {
                val t = 0.08 + 0.84 * j / (n - 1)
                // point on the line nearest to a + t*(b-a)
                val qx = a.x + (b.x - a.x) * t; val qy = a.y + (b.y - a.y) * t
                val u = (qx - l[2]) * l[0] + (qy - l[3]) * l[1]
                val px = l[2] + u * l[0]; val py = l[3] + u * l[1]
                val o = 4.0
                fun avg(x0: Double, y0: Double): Int {
                    // mean of 5 pixels along the line direction: suppresses sensor noise
                    var sum = 0; var cnt = 0
                    for (k in -2..2) {
                        val x = (x0 + l[0] * k * 2).toInt(); val y = (y0 + l[1] * k * 2).toInt()
                        if (x in 0 until w && y in 0 until h) { sum += pix[y * w + x].toInt() and 0xFF; cnt++ }
                    }
                    return if (cnt == 0) -1 else sum / cnt
                }
                val outside = avg(px + nx * o, py + ny * o)
                val inside = avg(px - nx * o, py - ny * o)
                if (outside < 0 || inside < 0) continue
                val d = outside - inside
                if (d >= 3) pos++ else if (d <= -3) neg++
            }
            val decisive = pos + neg
            return if (decisive < n / 4) 0.0 else kotlin.math.abs(pos - neg).toDouble() / decisive
        }
        var best: DoubleArray? = null
        var bestOff = Double.NEGATIVE_INFINITY
        for (c in clusters) {
            val cov = coverage(c)
            if (cov < 0.3) continue
            val l = fit(c)
            val pol = polarity(l)
            val off = (c.map { it.o0 }.average() + c.map { it.o1 }.average()) / 2
            debugLog?.invoke("side cluster off=%.0f cov=%.2f n=%d polarity=%.2f".format(c.map { it.off }.average(), cov, c.size, pol))
            if (pol < 0.6) continue
            if (off > bestOff) { bestOff = off; best = l }
        }
        return best
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
     * Splits the outline of an open book (two pages) into the two page outlines, following the
     * perspective. The split is across the spread's longer side: top/bottom halves when the spread
     * is taller than wide in the image (phone held along the book), left/right otherwise.
     * @param spine where the spine is, as a fraction (0..1) along the split direction of the
     *   flattened spread ([findSpine]); 0.5 = the true middle.
     * @return (first half, second half) = (top, bottom) or (left, right).
     */
    fun splitSpread(quad: Quad, imgW: Int, imgH: Int, spine: Float = 0.5f): Pair<Quad, Quad> {
        val tall = isTallSpread(quad, imgW, imgH)
        val (m0, m1) = spineLine(quad, imgW, imgH, spine)
        return if (tall) {
            // m0 = spine point on the left side, m1 = on the right side
            Quad(quad.tl, quad.tr, m1, m0) to Quad(m0, m1, quad.br, quad.bl)
        } else {
            // m0 = spine point on the top side, m1 = on the bottom side
            Quad(quad.tl, m0, m1, quad.bl) to Quad(m0, quad.tr, quad.br, m1)
        }
    }

    /**
     * Open book seen as ONE page? Completes the spread from that page: the outline is mirrored
     * across each of its four sides (the missing corners are drawn from the found ones), each
     * candidate is snapped to real edges and scored; a candidate wins only when its edges are
     * really there (a full spread mirrored runs off the book and scores low, so it stays as is).
     */
    fun expandToSpread(gray: Mat, quad: Quad, maxDim: Int = 1000): Quad {
        fun beyond(a: NPoint, b: NPoint) = NPoint(2 * b.x - a.x, 2 * b.y - a.y) // b + (b - a)
        val cands = listOf(
            Quad(quad.tl, beyond(quad.tl, quad.tr), beyond(quad.bl, quad.br), quad.bl),   // other page on the right
            Quad(beyond(quad.tr, quad.tl), quad.tr, quad.br, beyond(quad.br, quad.bl)),   // on the left
            Quad(quad.tl, quad.tr, beyond(quad.tr, quad.br), beyond(quad.tl, quad.bl)),   // below
            Quad(beyond(quad.bl, quad.tl), beyond(quad.br, quad.tr), quad.br, quad.bl),   // above
        )
        val baseScore = scoreQuad(gray, quad, maxDim)
        var best = quad
        var bestScore = 0.0
        for (c in cands) {
            // The other page must be (almost entirely) in the picture.
            if (c.points.any { it.x < -0.06f || it.x > 1.06f || it.y < -0.06f || it.y > 1.06f }) continue
            val snap = snapToQuad(gray, clampQuad(c), maxDim = maxDim, bandFraction = 0.06)
            if (snap.sidesFound < 3) continue
            val q = snap.quad
            val sc = scoreQuad(gray, q, maxDim)
            debugLog?.invoke("spread cand score=%.3f base=%.3f sides=%d".format(sc, baseScore, snap.sidesFound))
            if (sc >= 0.45 && sc >= baseScore * 0.85 && q.area() > quad.area() * 1.5 && sc > bestScore) {
                best = q; bestScore = sc
            }
        }
        return best
    }

    private fun clampQuad(q: Quad): Quad {
        fun c(p: NPoint) = NPoint(p.x.coerceIn(0f, 1f), p.y.coerceIn(0f, 1f))
        return Quad(c(q.tl), c(q.tr), c(q.br), c(q.bl))
    }

    /** True when an open book is split into top/bottom halves (spread taller than wide in the image). */
    fun isTallSpread(quad: Quad, imgW: Int, imgH: Int): Boolean {
        val (w, h) = naiveSize(quad, imgW, imgH)
        return h > w
    }

    /** The two ends of the spine line inside the spread outline, perspective-correct. */
    fun spineLine(quad: Quad, imgW: Int, imgH: Int, spine: Float = 0.5f): Pair<NPoint, NPoint> {
        val tall = isTallSpread(quad, imgW, imgH)
        val src = MatOfPoint2f(Point(0.0, 0.0), Point(1.0, 0.0), Point(1.0, 1.0), Point(0.0, 1.0))
        val dst = MatOfPoint2f(*quad.points.map { Point(it.x.toDouble() * imgW, it.y.toDouble() * imgH) }.toTypedArray())
        val hm = Imgproc.getPerspectiveTransform(src, dst)
        val t = spine.toDouble().coerceIn(0.2, 0.8)
        val probe = if (tall) arrayOf(Point(0.0, t), Point(1.0, t)) else arrayOf(Point(t, 0.0), Point(t, 1.0))
        val inPts = MatOfPoint2f(*probe)
        val outPts = MatOfPoint2f()
        Core.perspectiveTransform(inPts, outPts, hm)
        val m = outPts.toArray().map { NPoint((it.x / imgW).toFloat(), (it.y / imgH).toFloat()) }
        src.release(); dst.release(); hm.release(); inPts.release(); outPts.release()
        return m[0] to m[1]
    }

    /**
     * Finds the spine (gutter) of an open book inside [quad]: the spread is flattened and, for every
     * position across it, the *paper* brightness is measured (80th percentile, so text is ignored).
     * The binding shows as a valley (shadow / curvature near the spine). Without a visible valley,
     * the blank band between the two text blocks is used; otherwise the middle.
     * @return spine position 0..1 along the split direction (see [splitSpread]).
     */
    fun findSpine(gray: Mat, quad: Quad): Float {
        val w = gray.cols(); val h = gray.rows()
        val tall = isTallSpread(quad, w, h)
        val along = 600; val across = 400
        val size = if (tall) Size(across.toDouble(), along.toDouble()) else Size(along.toDouble(), across.toDouble())
        val src = MatOfPoint2f(*quad.points.map { Point(it.x.toDouble() * w, it.y.toDouble() * h) }.toTypedArray())
        val dst = MatOfPoint2f(Point(0.0, 0.0), Point(size.width, 0.0), Point(size.width, size.height), Point(0.0, size.height))
        val m = Imgproc.getPerspectiveTransform(src, dst)
        var flat = Mat()
        Imgproc.warpPerspective(gray, flat, m, size, Imgproc.INTER_AREA)
        src.release(); dst.release(); m.release()
        if (tall) { val t = Mat(); Core.transpose(flat, t); flat.release(); flat = t } // positions along x
        val px = ByteArray(along * across)
        flat.get(0, 0, px)
        flat.release()

        // Paper brightness and ink amount per position along the split direction.
        val paper = FloatArray(along)
        val column = IntArray(across)
        var allPaper = 0.0
        for (x in 0 until along) {
            for (y in 0 until across) column[y] = px[y * along + x].toInt() and 0xFF
            column.sort()
            paper[x] = column[(across * 0.8).toInt()].toFloat()
            allPaper += column[(across * 0.9).toInt()]
        }
        val paperLevel = (allPaper / along).toFloat()
        val ink = FloatArray(along)
        for (x in 0 until along) {
            var n = 0
            for (y in 0 until across) if ((px[y * along + x].toInt() and 0xFF) < paperLevel * 0.75f) n++
            ink[x] = n.toFloat() / across
        }
        val paperS = smooth1d(paper, 3.0)
        val inkS = smooth1d(ink, 2.0)

        // Both pages of a book are the same size, so the spine lies near the middle of the
        // spread; only an imperfect outer edge moves it a little. Search 40-60% and prefer the
        // centre, so a dark picture or shaded area on one page can't win.
        val lo = (along * 0.4).toInt(); val hi = (along * 0.6).toInt()
        val centre = along / 2f
        var minI = lo
        var minScore = Float.MAX_VALUE
        for (x in lo until hi) {
            val score = paperS[x] * (1f + 0.6f * abs(x - centre) / along)
            if (score < minScore) { minScore = score; minI = x }
        }
        val window = paperS.copyOfRange((along * 0.3).toInt(), (along * 0.7).toInt()).sorted()
        val median = window[window.size / 2]
        val depth = (median - paperS[minI]) / median.coerceAtLeast(1f)
        if (depth >= 0.04f) return minI.toFloat() / along

        // No gutter shadow: widest ink-free band near the middle.
        var best = -1f; var bestC = -1f; var run = 0
        for (k in lo..hi) {
            if (k < hi && inkS[k] < 0.01f) { run++; continue }
            if (run >= 8) {
                val c = k - run / 2f
                val score = run - abs(c - along / 2f) * 0.15f
                if (score > best) { best = score; bestC = c }
            }
            run = 0
        }
        return if (bestC > 0) bestC / along else 0.5f
    }

    private fun smooth1d(v: FloatArray, sigma: Double): FloatArray {
        val r = (sigma * 3).toInt()
        val k = DoubleArray(2 * r + 1) { i -> exp(-((i - r) * (i - r)) / (2 * sigma * sigma)) }
        val out = FloatArray(v.size)
        for (i in v.indices) {
            var s = 0.0; var ws = 0.0
            for (j in -r..r) { val t = i + j; if (t in v.indices) { s += v[t] * k[j + r]; ws += k[j + r] } }
            out[i] = (s / ws).toFloat()
        }
        return out
    }

    /** Page orientation to enforce together with a forced aspect ratio. */
    const val ORIENT_AUTO = 0
    const val ORIENT_PORTRAIT = 1
    const val ORIENT_LANDSCAPE = 2

    /**
     * Perspective-correct [src] (any type) to a flat, aspect-true page.
     * @param forcedAspect optional page ratio (e.g. A4 = sqrt 2, ID card); either way round.
     * @param forcedOrientation [ORIENT_AUTO] keeps the measured orientation; otherwise forces it.
     */
    fun warp(src: Mat, quad: Quad, forcedAspect: Double? = null, maxSide: Int = 4000, forcedOrientation: Int = ORIENT_AUTO): Mat {
        val w0 = src.cols()
        val h0 = src.rows()
        var ratio = estimateAspect(quad, w0, h0)
        if (forcedAspect != null) {
            val landscape = when (forcedOrientation) {
                ORIENT_PORTRAIT -> false
                ORIENT_LANDSCAPE -> true
                else -> ratio >= 1
            }
            ratio = if (landscape) max(forcedAspect, 1 / forcedAspect) else min(forcedAspect, 1 / forcedAspect)
        }
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
