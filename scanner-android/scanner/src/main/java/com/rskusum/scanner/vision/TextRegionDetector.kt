package com.rskusum.scanner.vision

import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/** Axis-aligned rectangle in normalized (0..1) image coordinates. */
data class NRect(val l: Float, val t: Float, val r: Float, val b: Float)

/**
 * Fast text-line finder for the live preview (AI Text mode). Text strokes have strong local
 * contrast: a morphological gradient + Otsu marks them, a wide horizontal closing joins the letters
 * of a line, and line-shaped blobs (wide, short, densely filled) are kept. Runs in a few ms on a
 * 480 px frame; it only highlights *where* text is - reading it is done by OCR after capture.
 */
object TextRegionDetector {

    private const val WIDTH = 480.0
    private const val MAX_BOXES = 80

    /**
     * @param gray upright 8-bit frame.
     * @param region only boxes whose centre lies inside this outline's bounding box (the page or the
     *   guide frame); null = whole frame.
     */
    fun detect(gray: Mat, region: Quad?): List<NRect> {
        val scale = WIDTH / gray.cols()
        val small = Mat()
        Imgproc.resize(gray, small, Size(WIDTH, (gray.rows() * scale).coerceAtLeast(1.0)), 0.0, 0.0, Imgproc.INTER_AREA)
        val w = small.cols().toFloat()
        val h = small.rows().toFloat()
        val grad = Mat()
        val bw = Mat()
        try {
            Imgproc.morphologyEx(small, grad, Imgproc.MORPH_GRADIENT, Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(3.0, 3.0)))
            Imgproc.threshold(grad, bw, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)
            Imgproc.morphologyEx(bw, bw, Imgproc.MORPH_CLOSE, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(9.0, 1.0)))
            Imgproc.morphologyEx(bw, bw, Imgproc.MORPH_OPEN, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 2.0)))

            val contours = ArrayList<MatOfPoint>()
            val work = bw.clone()
            val hierarchy = Mat()
            Imgproc.findContours(work, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            work.release(); hierarchy.release()

            val bounds = region?.let { q ->
                val xs = q.points.map { it.x }
                val ys = q.points.map { it.y }
                floatArrayOf(xs.min(), ys.min(), xs.max(), ys.max())
            }
            val out = ArrayList<Pair<Float, NRect>>()
            for (c in contours) {
                val r: Rect = Imgproc.boundingRect(c)
                c.release()
                if (r.height < 4 || r.height > h * 0.08f || r.width < 12 || r.width < 1.8f * r.height) continue
                val sub = bw.submat(r)
                val fill = Core.countNonZero(sub).toFloat() / (r.width * r.height)
                sub.release()
                if (fill < 0.3f) continue
                val box = NRect(r.x / w, r.y / h, (r.x + r.width) / w, (r.y + r.height) / h)
                if (bounds != null) {
                    val cx = (box.l + box.r) / 2
                    val cy = (box.t + box.b) / 2
                    if (cx < bounds[0] || cx > bounds[2] || cy < bounds[1] || cy > bounds[3]) continue
                }
                out += (r.width * r.height).toFloat() to box
            }
            return out.sortedByDescending { it.first }.take(MAX_BOXES).map { it.second }
        } finally {
            small.release(); grad.release(); bw.release()
        }
    }
}
