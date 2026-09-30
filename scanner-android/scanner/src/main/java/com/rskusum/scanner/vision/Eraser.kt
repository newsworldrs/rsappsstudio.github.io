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
        // Paper colour under the content, estimated from the page itself.
        val bg = ImageEnhancer.estimateBackground(original, kernelDiv = 25, floor = 0.8)
        var text: Mat? = null
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
                when (mode) {
                    Mode.EVERYTHING -> blend(rgb, bg, mask, feather = maxR * 0.35)
                    Mode.RESTORE -> blend(rgb, original, mask, feather = maxR * 0.35)
                    Mode.MARKS -> {
                        val t = text ?: textMask(original, bg).also { text = it }
                        val notText = Mat(); Core.bitwise_not(t, notText)
                        Core.bitwise_and(mask, notText, mask)
                        notText.release()
                        blend(rgb, bg, mask, feather = 1.0)
                    }
                }
                mask.release()
                i = j
            }
        } finally {
            original.release(); bg.release(); text?.release()
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

    /**
     * Printed text = much darker than the paper in EVERY colour channel AND neutral (black / grey).
     * Coloured pen (even dark blue ballpoint), red marks, highlighter and light pencil fail one of
     * the two tests and are removed; black print survives even under a translucent highlighter.
     */
    private fun textMask(rgb: Mat, bg: Mat): Mat {
        val ratio = Mat(); Core.divide(rgb, bg, ratio, 255.0)
        val ch = ArrayList<Mat>(); Core.split(ratio, ch)
        val maxRatio = Mat(); Core.max(ch[0], ch[1], maxRatio); Core.max(maxRatio, ch[2], maxRatio)
        val dark = Mat(); Imgproc.threshold(maxRatio, dark, 0.6 * 255, 255.0, Imgproc.THRESH_BINARY_INV)

        // Chroma = (max - min channel) of the pixel itself: low for black / grey ink.
        val rc = ArrayList<Mat>(); Core.split(rgb, rc)
        val hi = Mat(); Core.max(rc[0], rc[1], hi); Core.max(hi, rc[2], hi)
        val lo = Mat(); Core.min(rc[0], rc[1], lo); Core.min(lo, rc[2], lo)
        val chroma = Mat(); Core.subtract(hi, lo, chroma)
        val neutral = Mat(); Imgproc.threshold(chroma, neutral, 38.0, 255.0, Imgproc.THRESH_BINARY_INV)

        val text = Mat(); Core.bitwise_and(dark, neutral, text)
        // Keep the soft anti-aliased edges of letters.
        Imgproc.dilate(text, text, Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(3.0, 3.0)))
        (ch + rc + listOf(ratio, maxRatio, dark, hi, lo, chroma, neutral)).forEach { it.release() }
        return text
    }

    /**
     * rgb = rgb + (target - rgb) * softMask, only inside the mask's bounding box (strokes are
     * local, so full-page float buffers are never needed).
     */
    private fun blend(rgb: Mat, target: Mat, mask: Mat, feather: Double) {
        if (Core.countNonZero(mask) == 0) return
        val pts = MatOfPoint()
        Core.findNonZero(mask, pts)
        val box = Imgproc.boundingRect(pts)
        pts.release()
        val pad = (feather * 3).toInt() + 2
        val x0 = max(0, box.x - pad); val y0 = max(0, box.y - pad)
        val x1 = min(rgb.cols(), box.x + box.width + pad); val y1 = min(rgb.rows(), box.y + box.height + pad)
        val roi = Rect(x0, y0, x1 - x0, y1 - y0)

        val m = Mat(); mask.submat(roi).convertTo(m, CvType.CV_32F, 1.0 / 255)
        if (feather > 0.6) Imgproc.GaussianBlur(m, m, Size(0.0, 0.0), feather)
        val a = Mat(); Core.merge(listOf(m, m, m), a)
        val src = Mat(); rgb.submat(roi).convertTo(src, CvType.CV_32FC3)
        val dst = Mat(); target.submat(roi).convertTo(dst, CvType.CV_32FC3)
        Core.subtract(dst, src, dst)          // target - rgb
        Core.multiply(dst, a, dst)            // * alpha
        Core.add(src, dst, src)               // rgb + ...
        val out = rgb.submat(roi)
        src.convertTo(out, CvType.CV_8UC3)
        listOf(m, a, src, dst, out).forEach { it.release() }
    }
}
