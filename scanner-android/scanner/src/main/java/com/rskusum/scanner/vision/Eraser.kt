package com.rskusum.scanner.vision

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo
import kotlin.math.max
import kotlin.math.min

/**
 * One eraser stroke on the finished page, in normalized page coordinates.
 * @param radius brush radius as a fraction of the page width.
 * @param keepText true = "Marks only": remove pen / pencil / highlighter marks, stains and smudges
 *   but keep dark printed text underneath; false = "Everything": restore bare paper.
 * @param restore true = "Restore" brush: brings the original page back where it paints (undoes
 *   earlier erasing in that area); [keepText] is ignored.
 */
data class EraseStroke(
    val points: List<NPoint>,
    val radius: Float,
    val keepText: Boolean,
    val restore: Boolean = false,
) {
    /** Same stroke after rotating the page 90° clockwise. */
    fun rotatedCw() = copy(points = points.map { NPoint(1f - it.y, it.x) })
}

object Eraser {

    private enum class Mode { EVERYTHING, MARKS, RESTORE }

    private val EraseStroke.mode get() = when {
        restore -> Mode.RESTORE
        keepText -> Mode.MARKS
        else -> Mode.EVERYTHING
    }

    /**
     * Applies [strokes] to [rgb] (8UC3) in place, in order (so Restore can undo earlier erasing).
     * Edges are feathered so erased areas blend into the paper without visible circles.
     */
    fun apply(rgb: Mat, strokes: List<EraseStroke>) {
        if (strokes.isEmpty()) return
        val w = rgb.cols(); val h = rgb.rows()
        val original = rgb.clone()
        val k = localKernel(w)
        try {
            var i = 0
            while (i < strokes.size) {
                // Consecutive strokes with the same brush are applied together.
                val mode = strokes[i].mode
                var j = i
                val mask = Mat.zeros(h, w, CvType.CV_8UC1)
                var maxR = 1
                while (j < strokes.size && strokes[j].mode == mode) {
                    maxR = max(maxR, draw(mask, strokes[j], w, h))
                    j++
                }
                val feather = if (mode == Mode.MARKS) 1.0 else maxR * 0.25
                val roi = roiOf(mask, max((feather * 3).toInt() + 2, 3 * k))
                if (roi != null) {
                    val origRoi = original.submat(roi)
                    val rgbRoi = rgb.submat(roi)
                    val maskRoi = mask.submat(roi)
                    val target = when (mode) {
                        Mode.RESTORE -> origRoi.clone()
                        Mode.EVERYTHING -> paperFill(origRoi, maskRoi, k)
                        Mode.MARKS -> paperFill(origRoi, maskRoi, k).let { paper ->
                            marksTarget(origRoi, paper, k).also { paper.release() }
                        }
                    }
                    blend(rgbRoi, target, maskRoi, feather)
                    listOf(target, origRoi, rgbRoi, maskRoi).forEach { it.release() }
                }
                mask.release()
                i = j
            }
        } finally {
            original.release()
        }
    }

    /** Draws a stroke into [mask]; returns its radius in pixels. */
    private fun draw(mask: Mat, s: EraseStroke, w: Int, h: Int): Int {
        val r = max(1, (s.radius * w).toInt())
        val pts = s.points.map { Point(it.x.toDouble() * w, it.y.toDouble() * h) }
        for (p in pts) Imgproc.circle(mask, p, r, Scalar(255.0), -1)
        for (k in 1 until pts.size) Imgproc.line(mask, pts[k - 1], pts[k], Scalar(255.0), 2 * r)
        return r
    }

    /** Closing kernel that removes pen / text strokes but follows highlighter bands. */
    private fun localKernel(w: Int) = max(7, (w / 100) or 1)

    /** Bounding box of the painted area, padded, clipped to the image; null if nothing painted. */
    private fun roiOf(mask: Mat, pad: Int): Rect? {
        if (Core.countNonZero(mask) == 0) return null
        val pts = MatOfPoint()
        Core.findNonZero(mask, pts)
        val box = Imgproc.boundingRect(pts)
        pts.release()
        val x0 = max(0, box.x - pad); val y0 = max(0, box.y - pad)
        val x1 = min(mask.cols(), box.x + box.width + pad); val y1 = min(mask.rows(), box.y + box.height + pad)
        return Rect(x0, y0, x1 - x0, y1 - y0)
    }

    private fun ellipse(k: Int) = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(k.toDouble(), k.toDouble()))

    /**
     * The bare paper under the painted area, continued from the paper right around it: text is
     * closed away, then the painted area (and any dark blob next to it, e.g. the rest of a marker
     * smudge) is filled in from the surrounding paper. So an erased area matches its surroundings
     * exactly - no off-white or grey patch - also on filtered (pure white) pages. 32F 3-channel.
     */
    private fun paperFill(orig: Mat, mask: Mat, k: Int): Mat {
        val s = min(1.0, 400.0 / max(orig.cols(), orig.rows()))
        val size = Size(max(1.0, (orig.cols() * s).toInt().toDouble()), max(1.0, (orig.rows() * s).toInt().toDouble()))
        val sm = Mat(); Imgproc.resize(orig, sm, size, 0.0, 0.0, Imgproc.INTER_AREA)
        val ks = max(3, (k * s).toInt() or 1)
        val closed = Mat(); Imgproc.morphologyEx(sm, closed, Imgproc.MORPH_CLOSE, ellipse(ks))
        val msm = Mat(); Imgproc.resize(mask, msm, size, 0.0, 0.0, Imgproc.INTER_NEAREST)
        val hole = Mat(); Imgproc.dilate(msm, hole, ellipse(ks + 2))
        // Only real paper may feed the fill.
        val ch = ArrayList<Mat>(); Core.split(closed, ch)
        val lum = Mat(); Core.max(ch[0], ch[1], lum); Core.max(lum, ch[2], lum)
        val n = lum.rows() * lum.cols()
        val lb = ByteArray(n); lum.get(0, 0, lb)
        val hb = ByteArray(n); hole.get(0, 0, hb)
        val outside = IntArray(n).let { buf -> var c = 0; for (i in 0 until n) if (hb[i].toInt() == 0) buf[c++] = lb[i].toInt() and 0xFF; buf.copyOf(c) }
        val filled = Mat()
        if (outside.isEmpty()) {
            // Painted everywhere: plain paper at the brightest level of the area.
            val all = IntArray(n) { lb[it].toInt() and 0xFF }.sorted()
            val lvl = all[(n * 0.9).toInt().coerceAtMost(n - 1)].toDouble()
            closed.copyTo(filled); filled.setTo(Scalar(lvl, lvl, lvl))
        } else {
            outside.sort()
            val lvl = outside[(outside.size * 0.9).toInt().coerceAtMost(outside.size - 1)]
            val dark = Mat(); Core.compare(lum, Scalar(lvl * 0.8), dark, Core.CMP_LT)
            Core.max(hole, dark, hole)
            dark.release()
            if (Core.countNonZero(hole) >= n) {
                closed.copyTo(filled); filled.setTo(Scalar(lvl.toDouble(), lvl.toDouble(), lvl.toDouble()))
            } else {
                Photo.inpaint(closed, hole, filled, max(3.0, ks.toDouble()), Photo.INPAINT_TELEA)
            }
        }
        Imgproc.GaussianBlur(filled, filled, Size(0.0, 0.0), max(1.0, ks / 2.0))
        val up = Mat(); Imgproc.resize(filled, up, orig.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
        val out = Mat(); up.convertTo(out, CvType.CV_32FC3)
        (ch + listOf(sm, closed, msm, hole, lum, filled, up)).forEach { it.release() }
        return out
    }

    /**
     * Text pixels of a ratio image (page / reference, 32FC3): clearly darker and neutral (seed),
     * grown into the lighter, still neutral anti-aliased edges of the same letters. 8U 0/255.
     */
    private fun textMask(r: Mat): Mat {
        val rc = ArrayList<Mat>(); Core.split(r, rc)
        val rMax = Mat(); Core.max(rc[0], rc[1], rMax); Core.max(rMax, rc[2], rMax)
        val rMin = Mat(); Core.min(rc[0], rc[1], rMin); Core.min(rMin, rc[2], rMin)
        val spread = Mat(); Core.subtract(rMax, rMin, spread)
        val neutral = Mat(); Core.compare(spread, Scalar(0.2), neutral, Core.CMP_LT)
        val seed = Mat(); Core.compare(rMax, Scalar(0.72), seed, Core.CMP_LT); Core.bitwise_and(seed, neutral, seed)
        val weak = Mat(); Core.compare(rMax, Scalar(0.93), weak, Core.CMP_LT); Core.bitwise_and(weak, neutral, weak)
        val t = seed.clone()
        val k3 = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        repeat(3) { Imgproc.dilate(t, t, k3); Core.bitwise_and(t, weak, t) }
        Core.max(t, seed, t)
        (rc + listOf(rMax, rMin, spread, neutral, seed, weak, k3)).forEach { it.release() }
        return t
    }

    /**
     * "Marks only" result: clean paper everywhere, except printed text, which keeps its darkness
     * but loses any tint. Text is looked for twice: against the local background (any text, also
     * black text under a highlighter) and against the bare paper (big bold headings). Coloured
     * pen (even dark blue ballpoint), highlighter and stains are not neutral and become paper.
     * 32F 3-channel.
     */
    private fun marksTarget(orig: Mat, paper: Mat, k: Int): Mat {
        val o = Mat(); orig.convertTo(o, CvType.CV_32FC3)
        val local8 = Mat()
        Imgproc.morphologyEx(orig, local8, Imgproc.MORPH_CLOSE, ellipse(k))
        Imgproc.medianBlur(local8, local8, 5)
        val local = Mat(); local8.convertTo(local, CvType.CV_32FC3)
        Core.max(local, Scalar(1.0, 1.0, 1.0), local)
        val p = Mat(); Core.max(paper, Scalar(1.0, 1.0, 1.0), p)
        val rP = Mat(); Core.divide(o, p, rP)
        val rL = Mat(); Core.divide(o, local, rL)
        // Against the local background first (exact shade, also under a highlighter); against the
        // bare paper for big / bold letters the local background can't separate.
        val tL = textMask(rL)
        val tP = textMask(rP)
        val notP = Mat(); Core.bitwise_not(tL, notP); Core.bitwise_and(tP, notP, tP)
        fun meanOf(r: Mat): Mat {
            val c = ArrayList<Mat>(); Core.split(r, c)
            val m = Mat(); Core.add(c[0], c[1], m); Core.add(m, c[2], m)
            Core.multiply(m, Scalar(1.0 / 3), m); Core.min(m, Scalar(1.0), m)
            c.forEach { it.release() }
            return m
        }
        val shade = Mat(orig.rows(), orig.cols(), CvType.CV_32F, Scalar(1.0))
        val sP = meanOf(rP); val sL = meanOf(rL)
        sP.copyTo(shade, tP); sL.copyTo(shade, tL)
        val sh3 = Mat(); Core.merge(listOf(shade, shade, shade), sh3)
        val target = Mat(); Core.multiply(paper, sh3, target)
        listOf(o, local8, local, p, rP, rL, tP, tL, notP, shade, sP, sL, sh3).forEach { it.release() }
        return target
    }

    /** rgb = rgb + (target - rgb) * softMask. All arguments cover the same region; target is 8UC3 or 32FC3. */
    private fun blend(rgb: Mat, target: Mat, mask: Mat, feather: Double) {
        val m = Mat(); mask.convertTo(m, CvType.CV_32F, 1.0 / 255)
        if (feather > 0.6) Imgproc.GaussianBlur(m, m, Size(0.0, 0.0), feather)
        val a = Mat(); Core.merge(listOf(m, m, m), a)
        val src = Mat(); rgb.convertTo(src, CvType.CV_32FC3)
        val dst = Mat(); target.convertTo(dst, CvType.CV_32FC3)
        Core.subtract(dst, src, dst)          // target - rgb
        Core.multiply(dst, a, dst)            // * alpha
        Core.add(src, dst, src)               // rgb + ...
        src.convertTo(rgb, CvType.CV_8UC3)
        listOf(m, a, src, dst).forEach { it.release() }
    }
}
