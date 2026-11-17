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

enum class ScanFilter(@androidx.annotation.StringRes val labelRes: Int) {
    AUTO(com.rskusum.scanner.R.string.rs_scanner_filter_auto),
    ORIGINAL(com.rskusum.scanner.R.string.rs_scanner_filter_original),
    /** Faded print / light photocopies: every stroke dark, bold and sharp on pure white. */
    DARK_TEXT(com.rskusum.scanner.R.string.rs_scanner_filter_dark_text),
    LIGHT_TEXT(com.rskusum.scanner.R.string.rs_scanner_filter_light_text),
    GRAYSCALE(com.rskusum.scanner.R.string.rs_scanner_filter_grayscale),
    BW(com.rskusum.scanner.R.string.rs_scanner_filter_bw),
    WHITEBOARD(com.rskusum.scanner.R.string.rs_scanner_filter_whiteboard),
    /** Vivid colour scan: pure white paper, strong ink, stamp, logo and photo colours. */
    COLOR(com.rskusum.scanner.R.string.rs_scanner_filter_color),
    /** Colour scan with crisp text: measures how blurry the page is and deblurs it, for soft photos. */
    MAGIC(com.rskusum.scanner.R.string.rs_scanner_filter_magic),
    /**
     * Old "No shadow" filter, kept so existing code compiles. Shadow removal is now a separate
     * switch that works with every filter ([ImageEnhancer.apply] `removeShadow`).
     */
    @Deprecated("Use removeShadow = true with any filter")
    NO_SHADOW(com.rskusum.scanner.R.string.rs_scanner_remove_shadow);

    /** Display name in the current app language. */
    fun label(context: android.content.Context): String = context.getString(labelRes)

    companion object {
        /** Filters offered in the UI, in display order. */
        val choices: List<ScanFilter> = listOf(AUTO, COLOR, ORIGINAL, DARK_TEXT, LIGHT_TEXT, GRAYSCALE, BW, WHITEBOARD, MAGIC)
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
        ScanFilter.COLOR -> vividColor(rgb)
        ScanFilter.MAGIC -> magicColor(rgb)
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

    /**
     * Color: lighting is evened out from the brightness only (one gain for all three channels),
     * so large solid colour areas - headers, logos, photos - keep a clean, even colour instead of
     * blotches. The paper is then made neutral white, contrast and colours are boosted, near-white
     * paper is pushed to pure white and the text is sharpened.
     */
    private fun vividColor(rgb: Mat): Mat {
        val w = rgb.cols()
        val h = rgb.rows()
        val gray = Mat()
        Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY)
        // Paper brightness under the content (text and small marks removed).
        val s = min(1.0, 800.0 / max(w, h))
        val small = Mat()
        Imgproc.resize(gray, small, Size(w * s, h * s), 0.0, 0.0, Imgproc.INTER_AREA)
        gray.release()
        val k = max(5, (max(small.cols(), small.rows()) / 14) or 1)
        val bg = Mat()
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(k.toDouble(), k.toDouble()))
        Imgproc.morphologyEx(small, bg, Imgproc.MORPH_CLOSE, kernel)
        kernel.release(); small.release()
        Imgproc.medianBlur(bg, bg, 9)
        Core.max(bg, org.opencv.core.Scalar(percentile(bg, 0.95) * 0.45), bg)
        val bgF = Mat()
        bg.convertTo(bgF, CvType.CV_32F)
        bg.release()
        val full = Mat()
        Imgproc.resize(bgF, full, Size(w.toDouble(), h.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
        bgF.release()
        val gain = Mat()
        Core.max(full, org.opencv.core.Scalar(1.0), full)
        Core.divide(245.0, full, gain)
        full.release()
        val gain3 = Mat()
        Core.merge(listOf(gain, gain, gain), gain3)
        gain.release()
        val f = Mat()
        rgb.convertTo(f, CvType.CV_32FC3)
        Core.multiply(f, gain3, f)
        gain3.release()
        val even = Mat()
        f.convertTo(even, CvType.CV_8UC3)
        f.release()

        // Neutral white paper: the brightest 40 % of the page is the paper; remove its colour cast.
        val g2 = Mat()
        Imgproc.cvtColor(even, g2, Imgproc.COLOR_RGB2GRAY)
        val mask = Mat()
        Imgproc.threshold(g2, mask, percentile(g2, 0.6), 255.0, Imgproc.THRESH_BINARY)
        g2.release()
        val m = Core.mean(even, mask)
        mask.release()
        val r = max(1.0, m.`val`[0]); val gg = max(1.0, m.`val`[1]); val b = max(1.0, m.`val`[2])
        val avg = (r + gg + b) / 3.0
        Core.multiply(even, org.opencv.core.Scalar(avg / r, avg / gg, avg / b), even)

        val out = stretch(even, 0.01, 232.0)
        even.release()
        saturate(out, 1.4)
        // Soft knee: near-white paper becomes pure white, darker tones are untouched.
        val lab = Mat()
        Imgproc.cvtColor(out, lab, Imgproc.COLOR_RGB2Lab)
        val ch = ArrayList<Mat>()
        Core.split(lab, ch)
        val lut = Mat(1, 256, CvType.CV_8UC1)
        lut.put(0, 0, ByteArray(256) { i -> (if (i > 215) minOf(255, 215 + (i - 215) * 2) else i).toByte() })
        Core.LUT(ch[0], lut, ch[0])
        lut.release()
        Core.merge(ch, lab)
        ch.forEach { it.release() }
        Imgproc.cvtColor(lab, out, Imgproc.COLOR_Lab2RGB)
        lab.release()
        sharpen(out, 0.5)
        return out
    }

    /**
     * Magic color: crisp text on pure white paper, also from a soft (slightly out-of-focus or
     * shaken) photo. Lighting is evened out as for [vividColor]; then how blurry the page is gets
     * measured (the blur that halves its strongest edges), and the brightness is deblurred by that
     * much with Richardson-Lucy deconvolution - the standard way to undo focus blur - before the
     * paper is pushed to white and the ink darkened. Colours are kept and made more vivid. A page
     * that is already sharp is deblurred little or not at all, so it isn't over-sharpened.
     */
    private fun magicColor(rgb: Mat): Mat {
        val w = rgb.cols(); val h = rgb.rows()
        // 1. Even lighting from the brightness only (one gain for all channels).
        val gray = Mat()
        Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY)
        val s = min(1.0, 800.0 / max(w, h))
        val small = Mat()
        Imgproc.resize(gray, small, Size(w * s, h * s), 0.0, 0.0, Imgproc.INTER_AREA)
        gray.release()
        val k = max(5, (max(small.cols(), small.rows()) / 14) or 1)
        val bg = Mat()
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(k.toDouble(), k.toDouble()))
        Imgproc.morphologyEx(small, bg, Imgproc.MORPH_CLOSE, kernel)
        kernel.release(); small.release()
        Imgproc.medianBlur(bg, bg, 9)
        Core.max(bg, org.opencv.core.Scalar(percentile(bg, 0.95) * 0.45), bg)
        val bgF = Mat(); bg.convertTo(bgF, CvType.CV_32F); bg.release()
        val full = Mat()
        Imgproc.resize(bgF, full, Size(w.toDouble(), h.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
        bgF.release()
        Core.max(full, org.opencv.core.Scalar(1.0), full)
        val gain = Mat(); Core.divide(245.0, full, gain); full.release()
        val gain3 = Mat(); Core.merge(listOf(gain, gain, gain), gain3); gain.release()
        val f = Mat(); rgb.convertTo(f, CvType.CV_32FC3); Core.multiply(f, gain3, f); gain3.release()
        val even = Mat(); f.convertTo(even, CvType.CV_8UC3); f.release()

        // 2. Deblur the brightness (Lab L) by the measured blur.
        val lab = Mat()
        Imgproc.cvtColor(even, lab, Imgproc.COLOR_RGB2Lab)
        even.release()
        val ch = ArrayList<Mat>()
        Core.split(lab, ch)
        val l = Mat()
        ch[0].convertTo(l, CvType.CV_32F, 1.0 / 255.0)
        // Blur is measured, and undone, on a copy at most 1600 px: what a soft photo lost is
        // coarser than that anyway, and it keeps the deconvolution fast. The page's own finer
        // detail is added back at full size.
        val sc = min(1.0, 1600.0 / max(w, h))
        val lw = Mat()
        if (sc < 1.0) Imgproc.resize(l, lw, Size(w * sc, h * sc), 0.0, 0.0, Imgproc.INTER_AREA) else l.copyTo(lw)
        val psf = (edgeBlur(lw) - 0.6) * 1.1 // blur beyond that of a normally sharp edge
        if (psf > 0.3) {
            val x = lw.clone()
            val est = Mat(); val ratio = Mat()
            repeat(if (psf < 2.0) 8 else 12) {
                Imgproc.GaussianBlur(x, est, Size(0.0, 0.0), psf)
                Core.max(est, org.opencv.core.Scalar(1e-3), est)
                Core.divide(lw, est, ratio)
                Imgproc.GaussianBlur(ratio, ratio, Size(0.0, 0.0), psf)
                Core.multiply(x, ratio, x)
                Core.min(x, org.opencv.core.Scalar(1.5), x)
            }
            est.release(); ratio.release()
            if (sc < 1.0) {
                // full = up(deblurred) + (full - up(down(full))): deblur plus the full-size detail.
                val up = Mat(); val upOrig = Mat()
                Imgproc.resize(x, up, Size(w.toDouble(), h.toDouble()), 0.0, 0.0, Imgproc.INTER_CUBIC)
                Imgproc.resize(lw, upOrig, Size(w.toDouble(), h.toDouble()), 0.0, 0.0, Imgproc.INTER_CUBIC)
                Core.subtract(l, upOrig, l)
                Core.add(l, up, l)
                up.release(); upOrig.release()
            } else {
                x.copyTo(l)
            }
            x.release()
        }
        lw.release()

        // 3. Crisp text: paper (and anything near it) pure white, ink darker.
        val l8 = Mat(); l.convertTo(l8, CvType.CV_8U, 255.0)
        val paper = max(77.0, percentile(l8, 0.7))
        l8.release()
        val lut = Mat(1, 256, CvType.CV_8UC1)
        lut.put(0, 0, ByteArray(256) { i ->
            val t = i / paper
            val o = if (t >= 0.86) 1.0 else (t / 0.86).pow(1.6) * 0.86
            (o * 255).toInt().coerceIn(0, 255).toByte()
        })
        // L back to 8 bit (values above 1 clip), then the curve.
        l.convertTo(ch[0], CvType.CV_8U, 255.0)
        l.release()
        Core.LUT(ch[0], lut, ch[0])
        lut.release()
        Core.merge(ch, lab)
        ch.forEach { it.release() }
        val out = Mat()
        Imgproc.cvtColor(lab, out, Imgproc.COLOR_Lab2RGB)
        lab.release()
        saturate(out, 1.3)
        return out
    }

    /**
     * How blurry [l] (float brightness) is: the Gaussian blur (sigma, px) that halves the strength
     * of its strongest edges (99th percentile of the gradient). About 1-1.5 for a sharp scan at
     * 1600 px, more for a soft one.
     */
    private fun edgeBlur(l: Mat): Double {
        fun strength(m: Mat): Double {
            val gx = Mat(); val gy = Mat(); val mag = Mat()
            Imgproc.Sobel(m, gx, CvType.CV_32F, 1, 0); Imgproc.Sobel(m, gy, CvType.CV_32F, 0, 1)
            Core.magnitude(gx, gy, mag); gx.release(); gy.release()
            // 99th percentile from a 1024-bin histogram (no sorting of every pixel).
            val top = Core.minMaxLoc(mag).maxVal
            if (top <= 0.0) { mag.release(); return 0.0 }
            val hist = Mat()
            Imgproc.calcHist(listOf(mag), MatOfInt(0), Mat(), hist, MatOfInt(1024), MatOfFloat(0f, (top * 1.0001).toFloat()))
            mag.release()
            val total = m.total().toDouble(); var acc = 0.0; var bin = 1023
            for (i in 0 until 1024) { acc += hist.get(i, 0)[0]; if (acc >= total * 0.99) { bin = i; break } }
            hist.release()
            return (bin + 0.5) / 1024 * top
        }
        val e0 = strength(l)
        if (e0 <= 0.0) return 0.0
        val b = Mat()
        try {
            for (sig in doubleArrayOf(0.5, 0.75, 1.0, 1.4, 2.0, 2.8, 4.0)) {
                Imgproc.GaussianBlur(l, b, Size(0.0, 0.0), sig)
                if (strength(b) < 0.5 * e0) return sig
            }
            return 4.0
        } finally {
            b.release()
        }
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
