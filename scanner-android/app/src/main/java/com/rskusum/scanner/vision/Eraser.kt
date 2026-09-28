package com.rskusum.scanner.vision

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max

/**
 * One eraser stroke on the finished page, in normalized page coordinates.
 * @param radius brush radius as a fraction of the page width.
 * @param keepText true = "Marks only": remove pen/pencil/highlighter marks, stains and smudges
 *   but keep dark printed text underneath; false = "Everything": restore bare paper.
 */
data class EraseStroke(val points: List<NPoint>, val radius: Float, val keepText: Boolean) {
    /** Same stroke after rotating the page 90° clockwise. */
    fun rotatedCw() = copy(points = points.map { NPoint(1f - it.y, it.x) })
}

object Eraser {

    /** Applies [strokes] to [rgb] (8UC3) in place. */
    fun apply(rgb: Mat, strokes: List<EraseStroke>) {
        if (strokes.isEmpty()) return
        val w = rgb.cols(); val h = rgb.rows()
        val all = Mat.zeros(h, w, CvType.CV_8UC1)
        val marks = Mat.zeros(h, w, CvType.CV_8UC1)
        for (s in strokes) {
            val target = if (s.keepText) marks else all
            val r = max(1, (s.radius * w).toInt())
            val pts = s.points.map { Point(it.x.toDouble() * w, it.y.toDouble() * h) }
            for (p in pts) Imgproc.circle(target, p, r, Scalar(255.0), -1)
            for (i in 1 until pts.size) Imgproc.line(target, pts[i - 1], pts[i], Scalar(255.0), 2 * r)
        }
        // Paper colour under the content, estimated from the page itself.
        val bg = ImageEnhancer.estimateBackground(rgb, kernelDiv = 25, floor = 0.8)

        if (Core.countNonZero(marks) > 0) {
            // Text = dark AND neutral (black/grey print). Everything else under the brush -
            // coloured pen, highlighter, light pencil, stains - becomes paper.
            val gray = Mat(); Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY)
            val bgGray = Mat(); Imgproc.cvtColor(bg, bgGray, Imgproc.COLOR_RGB2GRAY)
            val hsv = Mat(); Imgproc.cvtColor(rgb, hsv, Imgproc.COLOR_RGB2HSV)
            val sat = Mat(); Core.extractChannel(hsv, sat, 1)
            val darkThr = Mat(); Core.multiply(bgGray, Scalar(0.55), darkThr)
            val dark = Mat(); Core.compare(gray, darkThr, dark, Core.CMP_LT)
            val neutral = Mat(); Imgproc.threshold(sat, neutral, 70.0, 255.0, Imgproc.THRESH_BINARY_INV)
            val text = Mat(); Core.bitwise_and(dark, neutral, text)
            // Keep the soft anti-aliased edges of letters.
            Imgproc.dilate(text, text, Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(3.0, 3.0)))
            val eraseMask = Mat(); Core.bitwise_not(text, eraseMask)
            Core.bitwise_and(eraseMask, marks, eraseMask)
            bg.copyTo(rgb, eraseMask)
            listOf(gray, bgGray, hsv, sat, darkThr, dark, neutral, text, eraseMask).forEach { it.release() }
        }
        if (Core.countNonZero(all) > 0) bg.copyTo(rgb, all)
        bg.release(); all.release(); marks.release()
    }
}
