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
        // Page illumination (keeps shadows / gradients) and the page's plain paper colour.
        val bg = ImageEnhancer.estimateBackground(original, kernelDiv = 25, floor = 0.8)
        val paper = paperColour(bg)
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
                val feather = if (mode == Mode.MARKS) 1.0 else maxR * 0.35
                val roi = roiOf(mask, (feather * 3).toInt() + 2 + if (mode == Mode.MARKS) localKernel(w) else 0)
                if (roi != null) {
                    val origRoi = original.submat(roi)
                    val bgRoi = bg.submat(roi)
                    val rgbRoi = rgb.submat(roi)
                    val maskRoi = mask.submat(roi)
                    val target = when (mode) {
                        Mode.RESTORE -> origRoi.clone()
                        Mode.EVERYTHING -> paperTarget(bgRoi, paper)
                        Mode.MARKS -> marksTarget(origRoi, bgRoi, paper, localKernel(w))
                    }
                    blend(rgbRoi, target, maskRoi, feather)
                    listOf(target, origRoi, bgRoi, rgbRoi, maskRoi).forEach { it.release() }
                }
                mask.release()
                i = j
            }
        } finally {
            original.release(); bg.release()
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

    /** The page's plain paper colour (90th percentile of the background per channel). */
    private fun paperColour(bg: Mat): DoubleArray {
        val small = Mat()
        Imgproc.resize(bg, small, Size(64.0, 64.0), 0.0, 0.0, Imgproc.INTER_AREA)
        val px = ByteArray(64 * 64 * 3)
        small.get(0, 0, px)
        small.release()
        return DoubleArray(3) { c ->
            val v = IntArray(64 * 64) { i -> px[i * 3 + c].toInt() and 0xFF }.sorted()
            v[(v.size * 0.9).toInt()].toDouble().coerceAtLeast(1.0)
        }
    }

    /**
     * Clean paper: the page's paper colour at the local brightness. Brightness is the brightest
     * channel of the background, which a yellow highlighter doesn't lower, so highlighted areas
     * become plain paper (no grey band) while real shadows are kept. 32F 3-channel.
     */
    private fun paperTarget(bg: Mat, paper: DoubleArray): Mat {
        val ch = ArrayList<Mat>(); Core.split(bg, ch)
        val bright = Mat(); Core.max(ch[0], ch[1], bright); Core.max(bright, ch[2], bright)
        val paperMax = paper.max()
        val f = Mat(); bright.convertTo(f, CvType.CV_32F, 1.0 / paperMax)
        Core.min(f, Scalar(1.0), f)
        val planes = paper.map { c -> Mat().also { Core.multiply(f, Scalar(c), it) } }
        val out = Mat(); Core.merge(planes, out)
        (ch + planes + listOf(bright, f)).forEach { it.release() }
        return out
    }

    /**
     * "Marks only" result: clean paper everywhere, except printed text, which keeps its darkness
     * but loses any tint (black text under a highlighter comes back black). Text = darker than the
     * LOCAL paper in every channel and neutral relative to it; coloured pen (even dark blue
     * ballpoint), highlighter and pencil fail the test and become paper. 32F 3-channel.
     */
    private fun marksTarget(orig: Mat, bg: Mat, paper: DoubleArray, k: Int): Mat {
        val local = Mat()
        Imgproc.morphologyEx(orig, local, Imgproc.MORPH_CLOSE, Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(k.toDouble(), k.toDouble())))
        Imgproc.medianBlur(local, local, 5)
        val o = Mat(); orig.convertTo(o, CvType.CV_32FC3)
        val l = Mat(); local.convertTo(l, CvType.CV_32FC3)
        Core.max(l, Scalar(1.0, 1.0, 1.0), l)
        val r = Mat(); Core.divide(o, l, r)
        val rc = ArrayList<Mat>(); Core.split(r, rc)
        val rMax = Mat(); Core.max(rc[0], rc[1], rMax); Core.max(rMax, rc[2], rMax)
        val rMin = Mat(); Core.min(rc[0], rc[1], rMin); Core.min(rMin, rc[2], rMin)
        val spread = Mat(); Core.subtract(rMax, rMin, spread)
        val dark = Mat(); Imgproc.threshold(rMax, dark, 0.6, 1.0, Imgproc.THRESH_BINARY_INV)
        val neutral = Mat(); Imgproc.threshold(spread, neutral, 0.22, 1.0, Imgproc.THRESH_BINARY_INV)
        val text = Mat(); Core.multiply(dark, neutral, text)
        Imgproc.dilate(text, text, Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(3.0, 3.0)))
        // shade = mean ratio for text pixels (keeps how dark the letter is), 1 elsewhere
        val mean = Mat(); Core.add(rc[0], rc[1], mean); Core.add(mean, rc[2], mean); Core.multiply(mean, Scalar(1.0 / 3), mean)
        Core.min(mean, Scalar(1.0), mean)
        val one = Mat.ones(text.size(), CvType.CV_32F)
        val shade = Mat()
        // shade = text * mean + (1 - text) * 1
        val inv = Mat(); Core.subtract(one, text, inv)
        val tm = Mat(); Core.multiply(text, mean, tm)
        Core.add(tm, inv, shade)
        val target = paperTarget(bg, paper)
        val sh3 = Mat(); Core.merge(listOf(shade, shade, shade), sh3)
        Core.multiply(target, sh3, target)
        (rc + listOf(local, o, l, r, rMax, rMin, spread, dark, neutral, text, mean, one, shade, inv, tm, sh3)).forEach { it.release() }
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
