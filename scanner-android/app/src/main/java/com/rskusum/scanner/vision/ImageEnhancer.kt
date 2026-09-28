package com.rskusum.scanner.vision

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfInt
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

enum class ScanFilter(val label: String) {
    AUTO("Auto color"),
    ORIGINAL("Original"),
    LIGHT_TEXT("Light text"),
    GRAYSCALE("Grayscale"),
    BW("B&W"),
    WHITEBOARD("Whiteboard"),
}

/**
 * Scanner-style enhancement. All filters (except [ScanFilter.ORIGINAL]) start from an
 * illumination-normalized image: the paper background is estimated by a large morphological
 * dilation + median blur, and the image is divided by it. That removes shadows, vignetting and
 * colour casts so the paper becomes uniformly white while ink keeps its colour.
 */
object ImageEnhancer {

    /** @param rgb 8UC3 RGB. @return new 8UC3 RGB mat. */
    fun apply(rgb: Mat, filter: ScanFilter): Mat = when (filter) {
        ScanFilter.ORIGINAL -> rgb.clone()
        ScanFilter.AUTO -> {
            val n = normalize(rgb)
            val s = stretch(n, 0.01, 248.0)
            n.release()
            saturate(s, 1.25)
            sharpen(s, 0.5)
            s
        }
        ScanFilter.WHITEBOARD -> {
            val n = normalize(rgb)
            val s = stretch(n, 0.03, 232.0)
            n.release()
            saturate(s, 1.7)
            sharpen(s, 0.6)
            s
        }
        ScanFilter.GRAYSCALE -> {
            val g = grayNormalized(rgb, 0.01, 248.0)
            sharpen(g, 0.5)
            toRgb(g)
        }
        ScanFilter.LIGHT_TEXT -> {
            val g = grayNormalized(rgb, 0.005, 252.0)
            gamma(g, 1.8)
            sharpen(g, 0.7)
            toRgb(g)
        }
        ScanFilter.BW -> {
            val g = grayNormalized(rgb, 0.0, 255.0)
            val bw = Mat()
            val block = max(15, (max(g.cols(), g.rows()) / 50) or 1)
            Imgproc.adaptiveThreshold(g, bw, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY, block, 12.0)
            g.release()
            toRgb(bw)
        }
    }

    // ---------------------------------------------------------------------------------------

    /** Divide by estimated background illumination. */
    private fun normalize(rgb: Mat): Mat {
        val w = rgb.cols()
        val h = rgb.rows()
        val s = min(1.0, 800.0 / max(w, h))
        val small = Mat()
        Imgproc.resize(rgb, small, Size(w * s, h * s), 0.0, 0.0, Imgproc.INTER_AREA)
        val k = max(5, (max(small.cols(), small.rows()) / 25) or 1)
        val bg = Mat()
        Imgproc.dilate(small, bg, Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(k.toDouble(), k.toDouble())))
        Imgproc.medianBlur(bg, bg, 21)
        val bgFull = Mat()
        Imgproc.resize(bg, bgFull, Size(w.toDouble(), h.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
        val out = Mat()
        Core.divide(rgb, bgFull, out, 255.0)
        small.release(); bg.release(); bgFull.release()
        return out
    }

    /** Linear levels: black point at [lowPercentile] of luminance, white point at [white]. */
    private fun stretch(img: Mat, lowPercentile: Double, white: Double): Mat {
        val lo = if (lowPercentile <= 0.0) 0.0 else percentile(img, lowPercentile).coerceAtMost(120.0)
        val a = 255.0 / max(1.0, white - lo)
        val out = Mat()
        img.convertTo(out, -1, a, -lo * a)
        return out
    }

    private fun grayNormalized(rgb: Mat, lowPercentile: Double, white: Double): Mat {
        val n = normalize(rgb)
        val g = Mat()
        Imgproc.cvtColor(n, g, Imgproc.COLOR_RGB2GRAY)
        n.release()
        val s = stretch(g, lowPercentile, white)
        g.release()
        return s
    }

    private fun percentile(img: Mat, p: Double): Double {
        val gray = if (img.channels() == 1) img else Mat().also { Imgproc.cvtColor(img, it, Imgproc.COLOR_RGB2GRAY) }
        val hist = Mat()
        Imgproc.calcHist(listOf(gray), MatOfInt(0), Mat(), hist, MatOfInt(256), MatOfFloat(0f, 256f))
        val total = gray.total().toDouble()
        var acc = 0.0
        var result = 0.0
        for (i in 0 until 256) {
            acc += hist.get(i, 0)[0]
            if (acc / total >= p) { result = i.toDouble(); break }
        }
        if (gray !== img) gray.release()
        hist.release()
        return result
    }

    private fun saturate(rgb: Mat, factor: Double) {
        val hsv = Mat()
        Imgproc.cvtColor(rgb, hsv, Imgproc.COLOR_RGB2HSV)
        val ch = ArrayList<Mat>()
        Core.split(hsv, ch)
        ch[1].convertTo(ch[1], -1, factor, 0.0)
        Core.merge(ch, hsv)
        Imgproc.cvtColor(hsv, rgb, Imgproc.COLOR_HSV2RGB)
        hsv.release(); ch.forEach { it.release() }
    }

    /** Unsharp mask, in place. */
    private fun sharpen(img: Mat, amount: Double) {
        val blur = Mat()
        val sigma = max(1.0, max(img.cols(), img.rows()) / 1500.0)
        Imgproc.GaussianBlur(img, blur, Size(0.0, 0.0), sigma)
        Core.addWeighted(img, 1.0 + amount, blur, -amount, 0.0, img)
        blur.release()
    }

    private fun gamma(gray: Mat, g: Double) {
        val lut = Mat(1, 256, CvType.CV_8UC1)
        val data = ByteArray(256) { i -> (255.0 * (i / 255.0).pow(g)).toInt().coerceIn(0, 255).toByte() }
        lut.put(0, 0, data)
        Core.LUT(gray, lut, gray)
        lut.release()
    }

    private fun toRgb(gray: Mat): Mat {
        val out = Mat()
        Imgproc.cvtColor(gray, out, Imgproc.COLOR_GRAY2RGB)
        gray.release()
        return out
    }
}
