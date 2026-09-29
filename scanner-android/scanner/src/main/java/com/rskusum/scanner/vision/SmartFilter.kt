package com.rskusum.scanner.vision

import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max

/** "AI assist": picks the most suitable filter for a flattened page from simple statistics. */
object SmartFilter {
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
            darkFrac < 0.01 -> ScanFilter.LIGHT_TEXT // faint pencil / faded print
            else -> ScanFilter.GRAYSCALE             // plain printed text
        }
    }
}
