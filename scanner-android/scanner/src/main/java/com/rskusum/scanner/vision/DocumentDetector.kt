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
        // Two classes of candidate: outlines whose four sides are all real edges, and outlines
        // with one side on the picture border (a page running off-screen). The border counts as
        // a perfect side and adds area, so on equal terms it used to beat the true outline of a
        // page lying fully in view near the border - and the crop kept a strip of the table.
        var best: Array<Point>? = null
        var bestScore = 0.0
        var bestBorder: Array<Point>? = null
        var bestBorderScore = 0.0
        for (q0 in candidates) {
            val q = orderPoints(q0)
            val onBorder = q.indices.any { i -> isBorderSide(q[i], q[(i + 1) % 4], w, h) }
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
            if (onBorder) {
                if (score > bestBorderScore) { bestBorderScore = score; bestBorder = q }
            } else if (score > bestScore) { bestScore = score; best = q }
        }
        // The off-screen outline wins only when no comparable all-real outline exists (one at
        // least 60% of its size; a smaller one is something printed on the page, not the page).
        val clean = best
        val border = bestBorder
        val q = when {
            border == null -> clean
            clean == null -> border
            polygonArea(clean) >= 0.6 * polygonArea(border) -> clean
            else -> border
        } ?: return null
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
    fun autoDetect(gray: Mat, edgeMap: Mat?, priors: List<Quad>, fast: Boolean = false): Quad? {
        val cands = ArrayList<Quad>()
        // Generic priors so there is always a starting outline, even without a guide frame.
        val allPriors = priors + if (fast) listOf(Quad.inset(0.08f)) else listOf(Quad.inset(0.06f), Quad.inset(0.14f))
        for (prior in allPriors) {
            for (bf in if (fast) doubleArrayOf(0.12) else doubleArrayOf(0.085, 0.16)) {
                val snap = snapToQuad(gray, prior, bandFraction = bf)
                if (snap.sidesFound >= 2) cands += refine(gray, snap.quad)
            }
        }
        edgeMap?.let { m -> detectFromEdgeMap(m)?.let { cands += refine(gray, it) } }
        detect(gray, 640)?.let { cands += refine(gray, it) }
        if (!fast) detect(gray, 1000)?.let { cands += refine(gray, it) }
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
        // (whole spread, the added other page)
        val cands = listOf(
            Quad(quad.tl, beyond(quad.tl, quad.tr), beyond(quad.bl, quad.br), quad.bl) to
                Quad(quad.tr, beyond(quad.tl, quad.tr), beyond(quad.bl, quad.br), quad.br),   // other page on the right
            Quad(beyond(quad.tr, quad.tl), quad.tr, quad.br, beyond(quad.br, quad.bl)) to
                Quad(beyond(quad.tr, quad.tl), quad.tl, quad.bl, beyond(quad.br, quad.bl)),   // on the left
            Quad(quad.tl, quad.tr, beyond(quad.tr, quad.br), beyond(quad.tl, quad.bl)) to
                Quad(quad.bl, quad.br, beyond(quad.tr, quad.br), beyond(quad.tl, quad.bl)),   // below
            Quad(beyond(quad.bl, quad.tl), beyond(quad.br, quad.tr), quad.br, quad.bl) to
                Quad(beyond(quad.bl, quad.tl), beyond(quad.br, quad.tr), quad.tr, quad.tl),   // above
        )
        val baseScore = scoreQuad(gray, quad, maxDim)
        var best = quad
        var bestRank = 0.0
        for ((c, other) in cands) {
            // The other page must be (almost entirely) in the picture.
            if (c.points.any { it.x < -0.06f || it.x > 1.06f || it.y < -0.06f || it.y > 1.06f }) continue
            val snap = snapToQuad(gray, clampQuad(c), maxDim = maxDim, bandFraction = 0.06)
            val q = if (snap.sidesFound >= 3) snap.quad else clampQuad(c)
            if (q.area() <= quad.area() * 1.5f) continue
            val sc = scoreQuad(gray, q, maxDim)
            val edgesOk = snap.sidesFound >= 3 && sc >= 0.45 && sc >= baseScore * 0.85
            // Text evidence: the added part holds a page of text and the whole reads as an open book
            // (works when the other page's edges are faint, e.g. against a light surface).
            val textOk = !edgesOk && isTwoPageSpread(gray, clampQuad(other)) == false && isTwoPageSpread(gray, q) == true
            debugLog?.invoke("spread cand score=%.3f base=%.3f sides=%d text=%b".format(sc, baseScore, snap.sidesFound, textOk))
            if (!edgesOk && !textOk) continue
            val rank = sc + if (edgesOk) 1.0 else 0.0
            if (rank > bestRank) { best = q; bestRank = rank }
        }
        return best
    }

    /**
     * Open book (two pages) or a single page? The text lines of an open book run along the
     * spread's long side (across the spine), on a single page they run along its short side.
     * Measured from the ink profiles of the flattened outline, half by half.
     * @return true = two-page spread, false = one page, null = not enough text to tell.
     */
    fun isTwoPageSpread(gray: Mat, quad: Quad): Boolean? {
        val w = gray.cols(); val h = gray.rows()
        val tall = isTallSpread(quad, w, h)
        val n = 600
        val src = MatOfPoint2f(*quad.points.map { Point(it.x.toDouble() * w, it.y.toDouble() * h) }.toTypedArray())
        val dst = MatOfPoint2f(Point(0.0, 0.0), Point(n.toDouble(), 0.0), Point(n.toDouble(), n.toDouble()), Point(0.0, n.toDouble()))
        val m = Imgproc.getPerspectiveTransform(src, dst)
        val flat = Mat()
        Imgproc.warpPerspective(gray, flat, m, Size(n.toDouble(), n.toDouble()), Imgproc.INTER_AREA)
        val ink = Mat()
        Imgproc.adaptiveThreshold(flat, ink, 1.0, Imgproc.ADAPTIVE_THRESH_MEAN_C, Imgproc.THRESH_BINARY_INV, 25, 12.0)
        val px = ByteArray(n * n)
        ink.get(0, 0, px)
        src.release(); dst.release(); m.release(); flat.release(); ink.release()
        var total = 0
        for (b in px) total += b
        if (total < n * n * 0.005) return null

        fun energy(p: FloatArray): Double {
            val s = smooth1d(p, 5.0)
            var e = 0.0
            for (i in p.indices) { val d = p[i] - s[i]; e += d * d }
            return e / p.size
        }
        // Profiles of each half (split across the long side), inner 80% only (no page edges).
        var eRows = 0.0; var eCols = 0.0
        val half = n / 2
        for (k in 0..1) {
            val x0 = if (tall) 0 else k * half; val x1 = if (tall) n else x0 + half
            val y0 = if (tall) k * half else 0; val y1 = if (tall) y0 + half else n
            val ix0 = x0 + (x1 - x0) / 10; val ix1 = x1 - (x1 - x0) / 10
            val iy0 = y0 + (y1 - y0) / 10; val iy1 = y1 - (y1 - y0) / 10
            val rows = FloatArray(iy1 - iy0); val cols = FloatArray(ix1 - ix0)
            for (y in iy0 until iy1) for (x in ix0 until ix1) {
                val v = px[y * n + x].toFloat()
                rows[y - iy0] += v; cols[x - ix0] += v
            }
            for (i in rows.indices) rows[i] /= cols.size
            for (i in cols.indices) cols[i] /= rows.size
            eRows += energy(rows); eCols += energy(cols)
        }
        // Rows alternate text / gap = lines run along x. The split runs along x unless tall.
        val alongSplit = if (tall) eCols else eRows
        val acrossSplit = if (tall) eRows else eCols
        val r = alongSplit / acrossSplit.coerceAtLeast(1e-9)
        debugLog?.invoke("spread lines ratio=%.2f".format(r))
        return when {
            r > 1.6 -> true
            r < 1 / 1.6 -> false
            else -> null
        }
    }

    /** Outline to use for an image picked from the gallery (see [detectImported]). */
    class ImportResult(val quad: Quad, val wholeImage: Boolean)

    /**
     * Gallery images are often already cropped: the page fills the picture and runs into its
     * borders, so any shape found inside (a photo, a table, a box) is NOT the page. The detected
     * outline is used only when it really is a page lying on some surface: brighter paper inside,
     * a clearly darker surface outside on most of its sides. Otherwise the picture borders are the
     * page corners.
     */
    fun detectImported(gray: Mat, edgeMap: Mat?): ImportResult {
        val whole = ImportResult(Quad.FULL, true)
        // Lighter search than for camera photos: most gallery pictures are already cropped.
        val q = autoDetect(gray, edgeMap, emptyList(), fast = true) ?: return whole
        val scale = min(1.0, 500.0 / max(gray.cols(), gray.rows()))
        val sm = Mat()
        Imgproc.resize(gray, sm, Size(gray.cols() * scale, gray.rows() * scale), 0.0, 0.0, Imgproc.INTER_AREA)
        Imgproc.medianBlur(sm, sm, 5)
        val w = sm.cols(); val h = sm.rows()
        val pix = ByteArray(w * h)
        sm.get(0, 0, pix)
        sm.release()
        fun at(x: Double, y: Double): Int {
            val xi = x.toInt(); val yi = y.toInt()
            return if (xi in 0 until w && yi in 0 until h) pix[yi * w + xi].toInt() and 0xFF else -1
        }
        // Paper level = 95th percentile brightness.
        val hist = IntArray(256)
        for (b in pix) hist[b.toInt() and 0xFF]++
        var acc = 0; var paper = 255
        for (v in 255 downTo 0) { acc += hist[v]; if (acc >= pix.size * 0.05) { paper = v; break } }

        val p = q.points.map { Point(it.x.toDouble() * w, it.y.toDouble() * h) }
        val cx = p.sumOf { it.x } / 4; val cy = p.sumOf { it.y } / 4
        val d = 0.025 * max(w, h)
        var usable = 0; var votes = 0
        for (i in 0 until 4) {
            val a = p[i]; val b = p[(i + 1) % 4]
            val (nx, ny) = outwardNormal(a, b, cx, cy)
            val ins = ArrayList<Int>(); val outs = ArrayList<Int>()
            for (j in 0 until 24) {
                val t = 0.15 + 0.7 * j / 23
                val x = a.x + (b.x - a.x) * t; val y = a.y + (b.y - a.y) * t
                val iv = at(x - nx * d, y - ny * d); val ov = at(x + nx * d, y + ny * d)
                if (iv >= 0 && ov >= 0) { ins += iv; outs += ov }
            }
            if (outs.size < 8) continue // side on the picture border
            usable++
            ins.sort(); outs.sort()
            val inside = ins[ins.size / 2]; val outside = outs[outs.size / 2]
            // Page side: paper inside, a darker surface (not more paper) outside.
            if (inside - outside >= 4 && outside <= paper - max(8, paper / 20)) votes++
        }
        debugLog?.invoke("import usable=$usable votes=$votes paper=$paper area=${q.area()}")
        val pageOnSurface = usable >= 2 && votes >= kotlin.math.ceil(usable * 0.6).toInt()
        return when {
            pageOnSurface -> ImportResult(snapToBorders(q, 0.015f), false)
            usable < 2 && q.area() >= 0.6f -> ImportResult(snapToBorders(q, 0.03f), true)
            else -> whole
        }
    }

    /** Corners within [margin] of the picture border are moved onto it. */
    fun snapToBorders(q: Quad, margin: Float): Quad {
        fun s(v: Float) = when {
            v < margin -> 0f
            v > 1 - margin -> 1f
            else -> v
        }
        fun c(p: NPoint) = NPoint(s(p.x), s(p.y))
        return Quad(c(q.tl), c(q.tr), c(q.br), c(q.bl))
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
    /** Flattened gutter-shadow profile used by [findSpine]. */
    private class SpineProfile(val along: Int, val paperS: FloatArray, val inkS: FloatArray, val minI: Int, val median: Float, val depth: Float)

    private fun spineProfile(gray: Mat, quad: Quad): SpineProfile {
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
        return SpineProfile(along, paperS, inkS, minI, median, depth)
    }

    fun findSpine(gray: Mat, quad: Quad): Float {
        val p = spineProfile(gray, quad)
        if (p.depth >= 0.04f) return p.minI.toFloat() / p.along

        // No gutter shadow: widest ink-free band near the middle.
        val lo = (p.along * 0.4).toInt(); val hi = (p.along * 0.6).toInt()
        var best = -1f; var bestC = -1f; var run = 0
        for (k in lo..hi) {
            if (k < hi && p.inkS[k] < 0.01f) { run++; continue }
            if (run >= 8) {
                val c = k - run / 2f
                val score = run - abs(c - p.along / 2f) * 0.15f
                if (score > best) { best = score; bestC = c }
            }
            run = 0
        }
        return if (bestC > 0) bestC / p.along else 0.5f
    }

    /** One book page flattened by [flattenBookPage]: the image and the page's outline in it. */
    class FlatPage(val image: Mat, val quad: Quad)

    /**
     * Cuts one page of an open book out of the photo and flattens it.
     *
     * A page that doesn't lie flat is curved: its two side edges (spine to outer edge), the
     * spine itself and every text line are curves in the photo. A four-corner crop leaves
     * background beside such a page and keeps its lines bent. Here all four boundaries are
     * traced on the photo - the side and outer edges where paper meets background, the spine
     * along the gutter shadow - each as a smooth curve fitted with outliers rejected, and the
     * page is mapped from between the four curves (a Coons patch), which makes them, and the
     * lines between them, straight. The squeeze of the content near the spine is undone from
     * the side curves' height profile. A boundary that can't be traced with confidence (paper
     * on a surface of the same colour, no gutter shadow) stays the straight side of [page].
     *
     * @param src the photo, 8-bit RGB; [gray] the same view as 8-bit gray, any size.
     * @param page the page's outline, normalized; [spineSide] its side on the spine:
     *   0 top, 1 right, 2 bottom, 3 left.
     * @param margin extra picture kept around the three outer sides (fraction of the page), so
     *   the crop can still be widened by hand.
     */
    fun flattenBookPage(src: Mat, gray: Mat, page: Quad, spineSide: Int, margin: Double = 0.02): FlatPage {
        val gw = gray.cols(); val gh = gray.rows()
        val p = page.points.map { Point(it.x.toDouble() * gw, it.y.toDouble() * gh) }
        val s = spineSide and 3
        val cx = p.sumOf { it.x } / 4; val cy = p.sumOf { it.y } / 4
        // Corners: S0, S1 on the spine; O0, O1 on the outer edge. Side B runs S0 -> O0, side A S1 -> O1.
        val s0 = p[s]; val s1 = p[(s + 1) % 4]; val o1 = p[(s + 2) % 4]; val o0 = p[(s + 3) % 4]
        val paper = paperMask(src, gray, p)
        val n = 96
        val sideB = traceBoundary(paper, gray, s0, o0, cx, cy, n, EDGE_SIDE)
        val sideA = traceBoundary(paper, gray, s1, o1, cx, cy, n, EDGE_SIDE)
        val outer = traceBoundary(paper, gray, o0, o1, cx, cy, n, EDGE_OUTER)
        val spine = traceBoundary(paper, gray, s0, s1, cx, cy, n, EDGE_SPINE)
        paper.release()

        // Height profile from the side curves -> stretch along the paper near the spine.
        val focal = 0.77 * max(gw, gh) // typical phone main camera
        val stretch = DoubleArray(n) { 1.0 }
        run {
            val rho = DoubleArray(n); var weight = 0.0
            for (e in listOf(sideA, sideB)) {
                if (!e.traced) continue
                var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0; var m = 0
                for (j in (n * 0.6).toInt() until n) { val u = j.toDouble() / (n - 1); sx += u; sy += e.off[j]; sxx += u * u; sxy += u * e.off[j]; m++ }
                val den = m * sxx - sx * sx
                val slope = if (abs(den) < 1e-9) 0.0 else (m * sxy - sx * sy) / den
                val icpt = (sy - slope * sx) / m
                val mx = (e.x0 + e.x1) / 2; val my = (e.y0 + e.y1) / 2
                val r = (mx - gw / 2.0) * e.nx + (my - gh / 2.0) * e.ny
                if (abs(r) < 0.08 * max(gw, gh)) continue
                // Only a real bend counts: a few pixels of wiggle on a flat page are not a curve.
                val dev = DoubleArray(n) { j -> e.off[j] - (icpt + slope * j / (n - 1.0)) }
                val elen = hypot(e.x1 - e.x0, e.y1 - e.y0)
                if (dev.maxOf { abs(it) } < 0.02 * elen) continue
                val wgt = abs(r)
                for (j in 0 until n) rho[j] += wgt * dev[j] / r
                weight += wgt
            }
            if (weight > 0) {
                val sm = smooth1d(FloatArray(n) { rho[it].toFloat() }, n / 24.0)
                for (j in 0 until n) rho[j] = sm[j].toDouble()
                val len = (hypot(o1.x - s1.x, o1.y - s1.y) + hypot(o0.x - s0.x, o0.y - s0.y)) / 2
                val step = len / (n - 1)
                for (j in 0 until n) {
                    val d = (rho[min(j + 1, n - 1)] - rho[max(j - 1, 0)]) / weight / ((min(j + 1, n - 1) - max(j - 1, 0)) * step)
                    val sl = (focal * d).coerceIn(-0.9, 0.9)
                    stretch[j] = sqrt(1 + sl * sl)
                }
            }
        }
        val cum = DoubleArray(n)
        for (j in 1 until n) cum[j] = cum[j - 1] + (stretch[j] + stretch[j - 1]) / 2
        val total = cum[n - 1]
        for (j in 0 until n) cum[j] /= total
        val meanStretch = total / (n - 1)
        debugLog?.invoke("flatten side=%d traced A=%b B=%b outer=%b spine=%b max %.0f/%.0f/%.0f/%.0f px stretch %.2f".format(
            s, sideA.traced, sideB.traced, outer.traced, spine.traced,
            sideA.off.maxOf { abs(it) }, sideB.off.maxOf { abs(it) }, outer.off.maxOf { abs(it) }, spine.off.maxOf { abs(it) }, stretch.max()))

        // Coons patch: (u from spine to outer edge, v from side B to side A) -> photo (gray px).
        val c00 = mid(sideB.at(0.0), spine.at(0.0)); val c01 = mid(sideA.at(0.0), spine.at(1.0))
        val c10 = mid(sideB.at(1.0), outer.at(0.0)); val c11 = mid(sideA.at(1.0), outer.at(1.0))
        fun patch(u: Double, v: Double): Point {
            val b = sideB.at(u); val a = sideA.at(u); val sp = spine.at(v); val o = outer.at(v)
            val uu = u.coerceIn(0.0, 1.0); val vv = v.coerceIn(0.0, 1.0)
            fun c(f: (Point) -> Double) = (1 - v) * f(b) + v * f(a) + (1 - uu) * f(sp) + uu * f(o) -
                ((1 - uu) * (1 - vv) * f(c00) + (1 - uu) * vv * f(c01) + uu * (1 - vv) * f(c10) + uu * vv * f(c11))
            return Point(c { it.x }, c { it.y })
        }

        val k = src.cols().toDouble() / gw
        val lenU = (hypot(o1.x - s1.x, o1.y - s1.y) + hypot(o0.x - s0.x, o0.y - s0.y)) / 2 * k * meanStretch
        val lenV = (hypot(s1.x - s0.x, s1.y - s0.y) + hypot(o1.x - o0.x, o1.y - o0.y)) / 2 * k
        val uPx = max(8, (lenU * (1 + margin)).toInt())
        val vPx = max(8, (lenV * (1 + 2 * margin)).toInt())
        val alongY = s == 0 || s == 2 // u (spine -> outer edge) runs along the output's y axis
        val outW = if (alongY) vPx else uPx
        val outH = if (alongY) uPx else vPx

        // Patch evaluated on a coarse grid, then interpolated per pixel (smooth, and fast).
        val gu = 64; val gv = 48
        val uAt = DoubleArray(gu + 1) { i ->
            val t = i.toDouble() / gu * (1 + margin)
            if (t >= 1.0) t else {
                var seg = 0
                while (seg < n - 2 && cum[seg + 1] < t) seg++
                val f = if (cum[seg + 1] > cum[seg]) (t - cum[seg]) / (cum[seg + 1] - cum[seg]) else 0.0
                (seg + f) / (n - 1)
            }
        }
        val gx = Array(gu + 1) { DoubleArray(gv + 1) }; val gy = Array(gu + 1) { DoubleArray(gv + 1) }
        for (i in 0..gu) for (j in 0..gv) {
            val v = j.toDouble() / gv * (1 + 2 * margin) - margin
            val q = patch(uAt[i], v)
            gx[i][j] = q.x * k; gy[i][j] = q.y * k
        }

        val out = Mat(outH, outW, src.type())
        val band = 256
        var y0 = 0
        while (y0 < outH) {
            val rows = min(band, outH - y0)
            val mxArr = FloatArray(rows * outW); val myArr = FloatArray(rows * outW)
            for (yy in 0 until rows) {
                val y = y0 + yy
                for (x in 0 until outW) {
                    val iu: Int; val iv: Int
                    when (s) {
                        0 -> { iu = y; iv = x }
                        1 -> { iu = outW - 1 - x; iv = y }
                        2 -> { iu = outH - 1 - y; iv = outW - 1 - x }
                        else -> { iu = x; iv = outH - 1 - y }
                    }
                    val fu = iu.toDouble() / (uPx - 1) * gu; val fv = iv.toDouble() / (vPx - 1) * gv
                    val i0 = min(fu.toInt(), gu - 1); val j0 = min(fv.toInt(), gv - 1)
                    val du = fu - i0; val dv = fv - j0
                    val o = yy * outW + x
                    mxArr[o] = ((gx[i0][j0] * (1 - dv) + gx[i0][j0 + 1] * dv) * (1 - du) + (gx[i0 + 1][j0] * (1 - dv) + gx[i0 + 1][j0 + 1] * dv) * du).toFloat()
                    myArr[o] = ((gy[i0][j0] * (1 - dv) + gy[i0][j0 + 1] * dv) * (1 - du) + (gy[i0 + 1][j0] * (1 - dv) + gy[i0 + 1][j0 + 1] * dv) * du).toFloat()
                }
            }
            val mapX = Mat(rows, outW, CvType.CV_32FC1); val mapY = Mat(rows, outW, CvType.CV_32FC1)
            mapX.put(0, 0, mxArr); mapY.put(0, 0, myArr)
            val part = out.submat(y0, y0 + rows, 0, outW)
            Imgproc.remap(src, part, mapX, mapY, Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE)
            part.release(); mapX.release(); mapY.release()
            y0 += rows
        }

        // Then the lines themselves: the middle of a page can be bent differently from its edges.
        val straight = if (alongY) {
            val t = Mat(); Core.transpose(out, t)
            val st = straightenTextLines(t)
            if (st === t) { t.release(); out } else {
                t.release(); val back = Mat(); Core.transpose(st, back); st.release(); out.release(); back
            }
        } else {
            val st = straightenTextLines(out)
            if (st !== out) out.release()
            st
        }

        val lo = (margin / (1 + 2 * margin)).toFloat(); val hi = ((1 + margin) / (1 + 2 * margin)).toFloat()
        val e = (1 / (1 + margin)).toFloat()
        val quad = when (s) {
            0 -> Quad(NPoint(lo, 0f), NPoint(hi, 0f), NPoint(hi, e), NPoint(lo, e))
            1 -> Quad(NPoint(1 - e, lo), NPoint(1f, lo), NPoint(1f, hi), NPoint(1 - e, hi))
            2 -> Quad(NPoint(lo, 1 - e), NPoint(hi, 1 - e), NPoint(hi, 1f), NPoint(lo, 1f))
            else -> Quad(NPoint(0f, lo), NPoint(e, lo), NPoint(e, hi), NPoint(0f, hi))
        }
        return FlatPage(straight, quad)
    }

    private fun mid(a: Point, b: Point) = Point((a.x + b.x) / 2, (a.y + b.y) / 2)

    /**
     * Straightens bent text lines in a page image whose lines run along x (any type). A page that
     * doesn't lie flat bends its lines differently in the middle than at its top and bottom
     * edges, which no edge-based flattening can follow; here the lines themselves are found
     * (ink bands, followed strip by strip across the page), each fitted with a smooth curve, and
     * the page is shifted vertically, column by column, so every line becomes straight. Pages
     * with too few clear lines (pictures, little text) are returned unchanged ([src] itself).
     */
    fun straightenTextLines(src: Mat): Mat {
        val w = src.cols(); val h = src.rows()
        if (w < 200 || h < 200) return src
        val gray = Mat()
        when (src.channels()) {
            3 -> Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGB2GRAY)
            4 -> Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)
            else -> src.copyTo(gray)
        }
        // Work on a copy about 1000 px tall: line finding needs no more.
        val f = min(1.0, 1000.0 / h)
        val sm = Mat(); Imgproc.resize(gray, sm, Size(w * f, h * f), 0.0, 0.0, Imgproc.INTER_AREA)
        gray.release()
        val sw = sm.cols(); val sh = sm.rows()
        val ink = Mat()
        Imgproc.adaptiveThreshold(sm, ink, 255.0, Imgproc.ADAPTIVE_THRESH_MEAN_C, Imgproc.THRESH_BINARY_INV, (sh / 40) or 1, 12.0)
        sm.release()
        // Characters -> line bands: close along x only.
        Imgproc.morphologyEx(ink, ink, Imgproc.MORPH_CLOSE, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(max(3.0, sw / 60.0), 1.0)))
        val px = ByteArray(sw * sh); ink.get(0, 0, px); ink.release()

        val strips = 24
        val stripW = sw / strips
        // Ink profile per strip, smoothed lightly.
        val prof = Array(strips) { k ->
            val p = FloatArray(sh)
            for (y in 0 until sh) { var c = 0; for (x in k * stripW until (k + 1) * stripW) if (px[y * sw + x].toInt() != 0) c++; p[y] = c.toFloat() / stripW }
            smooth1d(p, 1.5)
        }
        // Line spacing: strongest period of the page's overall profile (8..80 px).
        val all = FloatArray(sh) { y -> prof.sumOf { it[y].toDouble() }.toFloat() }
        val mean = all.average().toFloat()
        var bestLag = -1; var bestR = 0.0
        for (lag in 8..min(80, sh / 6)) {
            var r = 0.0
            for (y in 0 until sh - lag) r += (all[y] - mean) * (all[y + lag] - mean)
            if (r > bestR) { bestR = r; bestLag = lag }
        }
        if (bestLag < 0) return src
        val spacing = bestLag.toDouble()
        // Peaks (line centres) in each strip.
        val peaks = Array(strips) { k ->
            val p = prof[k]; val top = p.maxOrNull() ?: 0f
            val out = ArrayList<Double>()
            if (top > 0.05f) {
                var y = 1
                while (y < sh - 1) {
                    if (p[y] >= 0.3f * top && p[y] >= p[y - 1] && p[y] > p[y + 1]) {
                        val lo = max(0, y - (spacing * 0.4).toInt()); val hi = min(sh - 1, y + (spacing * 0.4).toInt())
                        if ((lo..hi).all { p[it] <= p[y] }) out += y.toDouble()
                    }
                    y++
                }
            }
            out
        }
        // Follow each line from the strip with the most peaks, both ways.
        val ref = (strips / 4 until strips * 3 / 4).maxBy { peaks[it].size }
        val tol = spacing * 0.35
        class Line(val ys: DoubleArray)
        val lines = ArrayList<Line>()
        for (y0 in peaks[ref]) {
            val ys = DoubleArray(strips) { Double.NaN }; ys[ref] = y0
            for (dir in intArrayOf(-1, 1)) {
                var last = y0; var prev = y0; var k = ref + dir; var miss = 0
                while (k in 0 until strips && miss <= 2) {
                    val pred = last + (last - prev) * 0.5
                    val hit = peaks[k].minByOrNull { abs(it - pred) }
                    if (hit != null && abs(hit - pred) <= tol) { ys[k] = hit; prev = last; last = hit; miss = 0 } else miss++
                    k += dir
                }
            }
            // Only lines running across most of the page: on a page that is half picture, lines
            // beside the picture can't tell how the picture is bent.
            if (ys.count { !it.isNaN() } >= strips * 0.75) lines += Line(ys)
        }
        debugLog?.invoke("textlines spacing=%.0f ref=%d lines=%d".format(spacing, ref, lines.size))
        if (lines.size < 8) return src

        // Each line: smooth cubic of its deviation from its own mean height.
        val xs = DoubleArray(strips) { (it + 0.5) / strips }
        val fits = ArrayList<Pair<Double, DoubleArray>>() // (mean y in [0,1], deviation per strip in px of the small copy)
        for (l in lines) {
            val idx = (0 until strips).filter { !l.ys[it].isNaN() }
            var use = idx
            var coef = DoubleArray(4)
            repeat(2) { iter ->
                val ata = DoubleArray(16); val atb = DoubleArray(4)
                for (i in use) { val t = xs[i]; val pw = doubleArrayOf(1.0, t, t * t, t * t * t); for (r in 0 until 4) { atb[r] += pw[r] * l.ys[i]; for (c in 0 until 4) ata[r * 4 + c] += pw[r] * pw[c] } }
                val a = Mat(4, 4, CvType.CV_64F); a.put(0, 0, *ata); val b = Mat(4, 1, CvType.CV_64F); b.put(0, 0, *atb); val x = Mat()
                Core.solve(a, b, x, Core.DECOMP_SVD); coef = DoubleArray(4) { x.get(it, 0)[0] }; a.release(); b.release(); x.release()
                if (iter == 0) {
                    val res = use.map { i -> abs(coef[0] + coef[1] * xs[i] + coef[2] * xs[i] * xs[i] + coef[3] * xs[i] * xs[i] * xs[i] - l.ys[i]) }
                    val cut = max(1.5, res.sorted()[(res.size * 0.8).toInt().coerceAtMost(res.size - 1)])
                    use = use.filterIndexed { k, _ -> res[k] <= cut }
                }
            }
            fun fy(t: Double) = coef[0] + coef[1] * t + coef[2] * t * t + coef[3] * t * t * t
            val dev = DoubleArray(strips + 1) { fy(it.toDouble() / strips) }
            val m = dev.average()
            for (i in dev.indices) dev[i] -= m
            if (dev.maxOf { abs(it) } > spacing * 3) continue // not a text line
            fits += m / sh to dev
        }
        if (fits.size < 8) return src
        fits.sortBy { it.first }
        // Deviation field on a grid: lines nearby in y, weighted; constant beyond the first/last line.
        val gy = 48
        val field = Array(gy + 1) { DoubleArray(strips + 1) }
        val sigma = 2.5 * spacing / sh
        for (j in 0..gy) {
            val y = (j.toDouble() / gy).coerceIn(fits.first().first, fits.last().first)
            for (i in 0..strips) {
                var sumW = 0.0; var sum = 0.0
                for ((ly, dev) in fits) { val wgt = exp(-((y - ly) / sigma).let { it * it }); sumW += wgt; sum += wgt * dev[i] }
                field[j][i] = if (sumW > 1e-6) sum / sumW else 0.0
            }
        }
        val maxDev = field.maxOf { row -> row.maxOf { abs(it) } }
        debugLog?.invoke("textlines used=%d maxDev=%.1f px".format(fits.size, maxDev))
        if (maxDev < 1.0) return src // already straight

        // Remap: output (x, y) takes source (x, y + deviation).
        val out = Mat(h, w, src.type())
        val scale = 1 / f
        val band = 256
        var y0 = 0
        while (y0 < h) {
            val rows = min(band, h - y0)
            val mxArr = FloatArray(rows * w); val myArr = FloatArray(rows * w)
            for (yy in 0 until rows) {
                val y = y0 + yy
                val fj = y.toDouble() / (h - 1) * gy; val j0 = min(fj.toInt(), gy - 1); val dj = fj - j0
                for (x in 0 until w) {
                    val fi = x.toDouble() / (w - 1) * strips; val i0 = min(fi.toInt(), strips - 1); val di = fi - i0
                    val d = (field[j0][i0] * (1 - di) + field[j0][i0 + 1] * di) * (1 - dj) + (field[j0 + 1][i0] * (1 - di) + field[j0 + 1][i0 + 1] * di) * dj
                    mxArr[yy * w + x] = x.toFloat(); myArr[yy * w + x] = (y + d * scale).toFloat()
                }
            }
            val mapX = Mat(rows, w, CvType.CV_32FC1); val mapY = Mat(rows, w, CvType.CV_32FC1)
            mapX.put(0, 0, mxArr); mapY.put(0, 0, myArr)
            val part = out.submat(y0, y0 + rows, 0, w)
            Imgproc.remap(src, part, mapX, mapY, Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE)
            part.release(); mapX.release(); mapY.release()
            y0 += rows
        }
        return out
    }

    /** A boundary from (x0,y0) to (x1,y1) with its traced offsets [off] (px along the outward normal). */
    private class TracedEdge(val x0: Double, val y0: Double, val x1: Double, val y1: Double, val nx: Double, val ny: Double, val off: DoubleArray, val traced: Boolean) {
        /** Point at fraction [u] of the edge (outside 0..1: the end continued straight). */
        fun at(u: Double): Point {
            val n = off.size
            val f = (u.coerceIn(0.0, 1.0)) * (n - 1)
            val i = min(f.toInt(), n - 2)
            val o = off[i] + (off[i + 1] - off[i]) * (f - i)
            return Point(x0 + (x1 - x0) * u + nx * o, y0 + (y1 - y0) * u + ny * o)
        }
    }

    /**
     * Paper pixels (255) around the page in [gray]'s size: brighter and less coloured than the
     * page's own paper level allows for background, with text closed over.
     */
    private fun paperMask(src: Mat, gray: Mat, p: List<Point>): Mat {
        val small = Mat(); val hsv = Mat()
        Imgproc.resize(src, small, gray.size(), 0.0, 0.0, Imgproc.INTER_AREA)
        Imgproc.cvtColor(small, hsv, Imgproc.COLOR_RGB2HSV)
        small.release()
        // Paper level: inner part of the page, bright pixels only (text is ignored).
        val cx = p.sumOf { it.x } / 4; val cy = p.sumOf { it.y } / 4
        val inner = p.map { Point(cx + (it.x - cx) * 0.7, cy + (it.y - cy) * 0.7) }
        val roiMask = Mat.zeros(gray.size(), CvType.CV_8UC1)
        Imgproc.fillConvexPoly(roiMask, MatOfPoint(*inner.toTypedArray()), Scalar(255.0))
        val ch = ArrayList<Mat>(); Core.split(hsv, ch)
        val vHist = IntArray(256); val sHist = IntArray(256)
        val w = gray.cols(); val h = gray.rows()
        val vb = ByteArray(w * h); val sb = ByteArray(w * h); val mb = ByteArray(w * h)
        ch[2].get(0, 0, vb); ch[1].get(0, 0, sb); roiMask.get(0, 0, mb)
        var cnt = 0
        for (i in mb.indices) if (mb[i].toInt() != 0) { vHist[vb[i].toInt() and 0xFF]++; cnt++ }
        fun pct(hist: IntArray, f: Double): Int { var acc = 0; for (v in 0..255) { acc += hist[v]; if (acc >= cnt * f) return v }; return 255 }
        val paperV = pct(vHist, 0.8)
        for (i in mb.indices) if (mb[i].toInt() != 0 && (vb[i].toInt() and 0xFF) >= paperV * 0.85) sHist[sb[i].toInt() and 0xFF]++
        cnt = sHist.sum()
        val paperS = if (cnt > 0) pct(sHist, 0.9) else 40
        val out = Mat()
        Core.inRange(hsv, Scalar(0.0, 0.0, paperV * 0.72), Scalar(180.0, min(255.0, paperS + 45.0), 255.0), out)
        val k = max(3, (max(w, h) / 150) or 1)
        Imgproc.morphologyEx(out, out, Imgproc.MORPH_CLOSE, rect(k))
        Imgproc.morphologyEx(out, out, Imgproc.MORPH_OPEN, rect(max(3, k / 2 or 1)))
        hsv.release(); roiMask.release(); ch.forEach { it.release() }
        return out
    }

    /**
     * Traces a page boundary near the straight line a-b: for an edge, where [paper] ends going
     * outward; for the [spine], the bottom of the gutter shadow in [gray]. Positions found across
     * the strip are fitted with a smooth cubic, rejecting outliers (a picture on the page, a
     * pattern on the surface). Returns offsets from the line; untraced (straight) when too few
     * positions are found or they don't follow a smooth curve.
     */
    private fun traceBoundary(paper: Mat, gray: Mat, a: Point, b: Point, cx: Double, cy: Double, n: Int, kind: Int): TracedEdge {
        val spine = kind == EDGE_SPINE
        val len = hypot(b.x - a.x, b.y - a.y)
        val tx = (b.x - a.x) / len; val ty = (b.y - a.y) / len
        val (nx, ny) = outwardNormal(a, b, cx, cy)
        val flat = TracedEdge(a.x, a.y, b.x, b.y, nx, ny, DoubleArray(n), false)
        val band = ((if (spine) 0.05 else 0.08) * len).toInt()
        if (len < 40 || band < 4) return flat
        val cols = max(n, (len / 3).toInt())
        val rows = 2 * band + 1
        val step = len / (cols - 1)
        // strip(col, row) = a + t * col * step + n * (row - band); rows grow outward.
        val m = Mat(2, 3, CvType.CV_64F)
        m.put(0, 0, tx * step, nx, a.x - nx * band, ty * step, ny, a.y - ny * band)
        val strip = Mat()
        try {
            val img = if (spine) gray else paper
            Imgproc.warpAffine(img, strip, m, Size(cols.toDouble(), rows.toDouble()), Imgproc.INTER_LINEAR or Imgproc.WARP_INVERSE_MAP, Core.BORDER_REPLICATE)
            val px = ByteArray(cols * rows)
            strip.get(0, 0, px)
            fun at(c: Int, r: Int) = px[r * cols + c].toInt() and 0xFF
            val pos = DoubleArray(cols) { Double.NaN }
            if (!spine) {
                // Outermost paper, scanning outward from inside the page: the first run of
                // background long enough not to be a gap in the paper.
                val run = max(3, band / 12)
                for (c in 0 until cols) {
                    if (at(c, 0) < 128) continue // no paper deep inside here (picture, shadow)
                    var r = 0; var bg = 0; var found = -1
                    while (r < rows) {
                        if (at(c, r) < 128) { bg++; if (bg >= run) { found = r - bg + 1; break } } else bg = 0
                        r++
                    }
                    if (found > 0 && found < rows - 1) pos[c] = (found - band).toDouble()
                }
            } else {
                // Gutter: darkest paper level across the strip, per stretch of the spine.
                val chunk = max(4, cols / 24)
                var c0 = 0
                val col = IntArray(chunk)
                while (c0 + chunk <= cols) {
                    val prof = DoubleArray(rows)
                    for (r in 0 until rows) {
                        for (j in 0 until chunk) col[j] = at(c0 + j, r)
                        col.sort(); prof[r] = col[(chunk * 0.8).toInt().coerceAtMost(chunk - 1)].toDouble()
                    }
                    val sm = smooth1d(FloatArray(rows) { prof[it].toFloat() }, max(1.5, band / 15.0))
                    var mi = 0
                    for (r in 1 until rows) if (sm[r] < sm[mi]) mi = r
                    val sorted = sm.sorted()
                    val level = sorted[(rows * 0.8).toInt()]
                    if (mi in 2 until rows - 2 && (level - sm[mi]) >= 0.05 * level) {
                        for (j in 0 until chunk) pos[c0 + j] = (mi - band).toDouble()
                    }
                    c0 += chunk
                }
            }
            val found = (0 until cols).filter { !pos[it].isNaN() }
            if (found.size < cols * (if (spine) 0.4 else 0.5)) return flat
            // Spikes out (running median), gaps filled, then smoothed: a flexible curve that can
            // stay straight along most of an edge and bend only near the spine.
            val win = max(5, cols / 30) or 1
            val med = DoubleArray(cols) { c ->
                val vals = (max(0, c - win / 2)..min(cols - 1, c + win / 2)).map { pos[it] }.filter { !it.isNaN() }
                if (vals.size < 2) Double.NaN else vals.sorted()[vals.size / 2]
            }
            val filled = fillGaps(med)
            val curve = smooth1d(FloatArray(cols) { filled[it].toFloat() }, max(2.0, cols / 60.0))
            fun fit(t: Double): Double { val f = t.coerceIn(0.0, 1.0) * (cols - 1); val k = min(f.toInt(), cols - 2); return curve[k] + (curve[k + 1] - curve[k]) * (f - k) }
            // The found positions must agree with the curve (one boundary, not scattered hits).
            val res = found.map { abs(pos[it] - curve[it]) }.sorted()
            val agree = res[res.size / 2] <= max(2.0, 0.008 * len)
            // A page bends near the spine only: the far half of a side edge, and the whole outer
            // edge, lie on a straight line. A trace that doesn't followed something beside the page
            // (e.g. a white patch touching it), and that boundary stays straight.
            fun straight(t0: Double, t1: Double): Boolean {
                val ix = found.filter { it >= t0 * (cols - 1) && it <= t1 * (cols - 1) }
                if (ix.size < 8) return false
                var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0
                for (c in ix) { sx += c; sy += pos[c]; sxx += c.toDouble() * c; sxy += c * pos[c] }
                val m = ix.size; val den = m * sxx - sx * sx
                val sl = if (abs(den) < 1e-9) 0.0 else (m * sxy - sx * sy) / den
                val ic = (sy - sl * sx) / m
                val r = ix.map { abs(pos[it] - (ic + sl * it)) }.sorted()
                return r[(r.size * 0.75).toInt().coerceAtMost(r.size - 1)] <= max(2.0, 0.006 * len)
            }
            val ok = agree && when (kind) {
                EDGE_SIDE -> straight(0.5, 1.0)
                EDGE_OUTER -> straight(0.0, 1.0)
                else -> true
            } && (0..20).all { abs(fit(it / 20.0)) < band - 1 }
            debugLog?.invoke("trace kind=%d found=%.0f%% agree=%b ok=%b max=%.0f".format(kind, 100.0 * found.size / cols, agree, ok, curve.maxOf { abs(it) }))
            if (!ok) return flat
            return TracedEdge(a.x, a.y, b.x, b.y, nx, ny, DoubleArray(n) { fit(it.toDouble() / (n - 1)) }, true)
        } finally {
            m.release(); strip.release()
        }
    }

    private const val EDGE_SIDE = 0
    private const val EDGE_OUTER = 1
    private const val EDGE_SPINE = 2

    /** [v] with NaN gaps filled by linear interpolation (ends: nearest value). */
    private fun fillGaps(v: DoubleArray): DoubleArray {
        val out = v.copyOf()
        val idx = v.indices.filter { !v[it].isNaN() }
        if (idx.isEmpty()) return DoubleArray(v.size)
        for (i in v.indices) {
            if (!out[i].isNaN()) continue
            val r = idx.firstOrNull { it > i }; val l = idx.lastOrNull { it < i }
            out[i] = when {
                l == null -> v[r!!]
                r == null -> v[l]
                else -> v[l] + (v[r] - v[l]) * (i - l).toDouble() / (r - l)
            }
        }
        return out
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

    /** True when most of the side a-b lies on the picture border. */
    private fun isBorderSide(a: Point, b: Point, w: Int, h: Int): Boolean {
        var on = 0
        val n = 20
        for (j in 0 until n) {
            val t = 0.06 + 0.88 * j / (n - 1)
            val x = a.x + (b.x - a.x) * t
            val y = a.y + (b.y - a.y) * t
            if (x < 2.5 || y < 2.5 || x > w - 3.5 || y > h - 3.5) on++
        }
        return on > n * 0.7
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
