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
    /** Faded print / light photocopies: every stroke dark, bold and sharp on pure white. */
    DARK_TEXT("Dark text"),
    LIGHT_TEXT("Light text"),
    GRAYSCALE("Grayscale"),
    BW("B&W"),
    WHITEBOARD("Whiteboard"),
    /**
     * Old "No shadow" filter, kept so existing code compiles. Shadow removal is now a separate
     * switch that works with every filter ([ImageEnhancer.apply] `removeShadow`).
     */
    @Deprecated("Use removeShadow = true with any filter")
    NO_SHADOW("No shadow");

    companion object {
        /** Filters offered in the UI, in display order. */
        val choices: List<ScanFilter> = listOf(AUTO, ORIGINAL, DARK_TEXT, LIGHT_TEXT, GRAYSCALE, BW, WHITEBOARD)
    }
}

/**
 * Scanner-style enhancement. All filters (except [ScanFilter.ORIGINAL]) start from an
 * illumination-normalized image: the paper background is estimated by a large morphological
 * dilation + median blur, and the image is divided by it. That removes shadows, vignetting and
 * colour casts so the paper becomes uniformly white while ink keeps its colour.
 */
object ImageEnhancer {

    /**
     * @param rgb 8UC3 RGB.
     * @param removeShadow first remove shadows / uneven lighting (colours kept), then apply [filter].
     *   Works with every filter, including Original.
     * @return new 8UC3 RGB mat.
     */
    @Suppress("DEPRECATION")
    fun apply(rgb: Mat, filter: ScanFilter, removeShadow: Boolean = false): Mat {
        if (!removeShadow || filter == ScanFilter.NO_SHADOW) return applyFilter(rgb, filter)
        val flat = removeShadows(rgb)
        return try { applyFilter(flat, filter) } finally { flat.release() }
    }

    /**
     * Shadow removal on its own: a fine illumination model that follows hard shadow edges (phone /
     * hand shadows) lifts shaded paper to white; ink colours and contrast are kept.
     */
    fun removeShadows(rgb: Mat): Mat {
        val n = normalize(rgb, kernelDiv = 18, floor = 0.3)
        val s = stretch(n, 0.002, 252.0)
        n.release()
        return s
    }

    @Suppress("DEPRECATION")
    private fun applyFilter(rgb: Mat, filter: ScanFilter): Mat = when (filter) {
        ScanFilter.ORIGINAL -> rgb.clone()
        ScanFilter.DARK_TEXT -> darkText(rgb)
        ScanFilter.AUTO -> {
            // Gentle: even out lighting, whiten paper, keep natural ink and photo colours.
            val n = normalize(rgb)
            val s = stretch(n, 0.004, 250.0)
            n.release()
            saturate(s, 1.08)
            sharpen(s, 0.3)
            s
        }
        ScanFilter.NO_SHADOW -> removeShadows(rgb)
        ScanFilter.WHITEBOARD -> {
            val n = normalize(rgb)
            val s = stretch(n, 0.03, 232.0)
            n.release()
            saturate(s, 1.7)
            sharpen(s, 0.6)
            s
        }
        ScanFilter.GRAYSCALE -> {
            val g = grayNormalized(rgb, 0.004, 250.0)
            sharpen(g, 0.3)
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

    /**
     * Dark text: lighting evened out, paper grain / bleed-through smoothed (edge-preserving), paper
     * mapped to pure white, faint strokes pushed to near black (gamma), sharpened and slightly
     * thickened. Turns a faded photocopy into crisp black-on-white text.
     */
    private fun darkText(rgb: Mat): Mat {
        val n = normalize(rgb, kernelDiv = 18, floor = 0.3)
        val g = Mat()
        Imgproc.cvtColor(n, g, Imgproc.COLOR_RGB2GRAY)
        n.release()
        val smooth = Mat()
        Imgproc.bilateralFilter(g, smooth, 5, 30.0, 5.0)
        g.release()
        val ink = percentile(smooth, 0.01)
        val paper = percentile(smooth, 0.60)
        val white = min(245.0, paper - 14.0)
        val lo = min(ink, 140.0)
        val a = 255.0 / max(1.0, white - lo)
        val out = Mat()
        smooth.convertTo(out, -1, a, -lo * a)
        smooth.release()
        gamma(out, 2.2)
        sharpen(out, 1.0)
        // Slightly bolder strokes (thin faded lines become solid).
        val k = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(2.0, 2.0))
        Imgproc.erode(out, out, k)
        k.release()
        return toRgb(out)
    }

    /** Divide by estimated background illumination. */
    private fun normalize(rgb: Mat, kernelDiv: Int = 25, floor: Double = 0.55): Mat {
        val bgFull = estimateBackground(rgb, kernelDiv, floor)
        val out = Mat()
        Core.divide(rgb, bgFull, out, 255.0)
        bgFull.release()
        return out
    }

    /**
     * Full-size estimate of the bare paper colour under the content (text removed by a large
     * dilation + median). Large dark printed areas (photos, filled boxes, dark covers) must not be
     * mistaken for shadow, so the estimate never drops below [floor] x the brightest paper level.
     */
    fun estimateBackground(rgb: Mat, kernelDiv: Int = 25, floor: Double = 0.55): Mat {
        val w = rgb.cols()
        val h = rgb.rows()
        val s = min(1.0, 800.0 / max(w, h))
        val small = Mat()
        Imgproc.resize(rgb, small, Size(w * s, h * s), 0.0, 0.0, Imgproc.INTER_AREA)
        val k = max(5, (max(small.cols(), small.rows()) / kernelDiv) or 1)
        val bg = Mat()
        // Morphological closing (dilate then erode) removes text/ink smaller than the kernel but,
        // unlike a plain dilation, keeps large brightness steps (hard shadow edges) in place.
        Imgproc.morphologyEx(small, bg, Imgproc.MORPH_CLOSE, Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(k.toDouble(), k.toDouble())))
        Imgproc.medianBlur(bg, bg, if (floor < 0.5) 9 else 21)
        val chans = ArrayList<Mat>()
        Core.split(bg, chans)
        for (c in chans) {
            val paper = percentile(c, 0.95)
            Core.max(c, org.opencv.core.Scalar(paper * floor), c)
        }
        Core.merge(chans, bg)
        chans.forEach { it.release() }
        val bgFull = Mat()
        Imgproc.resize(bg, bgFull, Size(w.toDouble(), h.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
        small.release(); bg.release()
        return bgFull
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
