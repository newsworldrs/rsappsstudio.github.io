package com.rskusum.scanner.vision

import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max

/** What the Smart filter picked for a page. */
data class SmartPick(val filter: ScanFilter, val removeShadow: Boolean) {
    /** e.g. "Dark text + shadow removal". */
    val label: String get() = if (removeShadow) "${filter.label} + shadow removal" else filter.label
}

/** "Smart filter": picks the most suitable filter for a flattened page from simple statistics. */
object SmartFilter {
    /** Filter + whether the page has uneven lighting / a shadow worth removing. */
    fun pick(rgb: Mat): SmartPick = SmartPick(choose(rgb), hasShadow(rgb))

    /**
     * True when the paper brightness varies strongly across the page (a phone / hand shadow or a
     * dark corner): the darkest paper area is below 78% of the brightest.
     */
    fun hasShadow(rgb: Mat): Boolean {
        val s = 300.0 / max(rgb.cols(), rgb.rows())
        val small = Mat()
        Imgproc.resize(rgb, small, Size(rgb.cols() * s, rgb.rows() * s), 0.0, 0.0, Imgproc.INTER_AREA)
        val bg = ImageEnhancer.estimateBackground(small, kernelDiv = 18, floor = 0.3)
        val gray = Mat()
        Imgproc.cvtColor(bg, gray, Imgproc.COLOR_RGB2GRAY)
        val sorted = FloatArray(gray.total().toInt()).also { arr ->
            val b = ByteArray(arr.size); gray.get(0, 0, b); for (i in b.indices) arr[i] = (b[i].toInt() and 0xFF).toFloat()
        }.sorted()
        listOf(small, bg, gray).forEach { it.release() }
        if (sorted.isEmpty()) return false
        val dark = sorted[(sorted.size * 0.05).toInt()]
        val bright = sorted[(sorted.size * 0.95).toInt().coerceAtMost(sorted.size - 1)]
        return bright > 40 && dark / bright < 0.78f
    }

    fun choose(rgb: Mat): ScanFilter {
        val s = 300.0 / max(rgb.cols(), rgb.rows())
        val small = Mat()
        Imgproc.resize(rgb, small, Size(rgb.cols() * s, rgb.rows() * s), 0.0, 0.0, Imgproc.INTER_AREA)
        val auto = ImageEnhancer.apply(small, ScanFilter.AUTO)
        val hsv = Mat()
        Imgproc.cvtColor(auto, hsv, Imgproc.COLOR_RGB2HSV)
        val meanSat = Core.mean(hsv).`val`[1]
        val gray = Mat()
        Imgproc.cvtColor(auto, gray, Imgproc.COLOR_RGB2GRAY)
        val dark = Mat()
        Imgproc.threshold(gray, dark, 110.0, 255.0, Imgproc.THRESH_BINARY_INV)
        val darkFrac = Core.countNonZero(dark).toDouble() / gray.total()
        listOf(small, auto, hsv, gray, dark).forEach { it.release() }
        return when {
            meanSat > 22 -> ScanFilter.AUTO          // photos, colored forms, highlights
            darkFrac < 0.01 -> ScanFilter.DARK_TEXT  // faint pencil / faded print / light photocopy
            else -> ScanFilter.GRAYSCALE             // plain printed text
        }
    }
}
