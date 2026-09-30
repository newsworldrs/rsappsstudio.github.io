package com.rskusum.scanner.vision

import android.util.Log
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.exp
import kotlin.math.max

/**
 * Turns a flattened page upright from its text, for any script:
 *  1. Sideways or not: text lines run along the rows (upright / upside down) or along the columns
 *     (turned 90 degrees) - measured from the ink profiles, independent of the script.
 *  2. Right way up or upside down: scripts with a headline (Devanagari - Hindi, Marathi, Nepali,
 *     Sanskrit; Bengali, Gurmukhi) have a solid bar along the TOP of every word, which decides it
 *     directly. Other scripts (Latin, ...) use the orientation model, asked only "0 or 180".
 */
object TextOrientation {

    /**
     * @param rgb flattened page, 8UC3 RGB.
     * @param allowQuarter false when the page orientation is fixed (only a 180 degree fix is allowed).
     * @return clockwise rotation (0/90/180/270) that makes the text upright; 0 when unsure.
     */
    fun uprightRotation(rgb: Mat, model: OrientationModel?, allowQuarter: Boolean): Int {
        val gray = Mat()
        Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY)
        try {
            val axis = lineAxis(gray)
            if (axis == null) {
                // No clear text lines (pictures, a few words): the model alone, as before.
                val r = model?.uprightRotation(rgb) ?: 0
                return if (r == 180 || allowQuarter) r else 0
            }
            if (axis == Axis.COLUMNS && !allowQuarter) return 0
            var base = 0
            var g = gray
            var col = rgb
            if (axis == Axis.COLUMNS) {
                base = 90
                g = Mat(); Core.rotate(gray, g, Core.ROTATE_90_CLOCKWISE)
                col = Mat(); Core.rotate(rgb, col, Core.ROTATE_90_CLOCKWISE)
            }
            try {
                val (top, bottom) = headlines(g)
                Log.d(TAG, "axis=$axis headlines top=$top bottom=$bottom")
                val flip = when {
                    top >= 8 && top >= bottom * 5 -> false
                    bottom >= 8 && bottom >= top * 5 -> true
                    else -> {
                        // No headline script: the model decides between upright and upside down.
                        val p = model?.probabilities(col)
                        p != null && p[2] >= 0.6f && p[2] > p[0] * 2
                    }
                }
                return (base + if (flip) 180 else 0) % 360
            } finally {
                if (g !== gray) g.release()
                if (col !== rgb) col.release()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "orientation failed", t)
            return 0
        } finally {
            gray.release()
        }
    }

    enum class Axis { ROWS, COLUMNS }

    /** Direction of the text lines, or null when the page has no clear lines. */
    fun lineAxis(gray: Mat): Axis? {
        val ink = inkMap(gray, 1000)
        val w = ink.cols(); val h = ink.rows()
        val px = ByteArray(w * h)
        ink.get(0, 0, px)
        ink.release()
        val x0 = w / 10; val x1 = w - w / 10; val y0 = h / 10; val y1 = h - h / 10
        val rows = FloatArray(y1 - y0); val cols = FloatArray(x1 - x0)
        var total = 0
        for (y in y0 until y1) for (x in x0 until x1) {
            if (px[y * w + x].toInt() != 0) { rows[y - y0]++; cols[x - x0]++; total++ }
        }
        if (total < rows.size * cols.size * 0.01) return null
        for (i in rows.indices) rows[i] /= cols.size
        for (i in cols.indices) cols[i] /= rows.size
        val er = energy(rows); val ec = energy(cols)
        return when {
            er > ec * 1.5 -> Axis.ROWS
            ec > er * 1.5 -> Axis.COLUMNS
            else -> null
        }
    }

    /**
     * Words whose widest solid horizontal bar lies at their top vs at their bottom (text lines
     * horizontal). Headline scripts give a very one-sided count; other scripts a mixed one.
     */
    fun headlines(gray: Mat): Pair<Int, Int> {
        val ink = inkMap(gray, 1600)
        val labels = Mat(); val stats = Mat(); val cents = Mat()
        val n = Imgproc.connectedComponentsWithStats(ink, labels, stats, cents, 8, CvType.CV_32S)
        val w = labels.cols()
        val lab = IntArray(w * labels.rows())
        labels.get(0, 0, lab)
        val st = IntArray(n * 5)
        stats.get(0, 0, st)
        ink.release(); labels.release(); stats.release(); cents.release()
        var top = 0; var bottom = 0
        for (k in 1 until n) {
            val bx = st[k * 5]; val by = st[k * 5 + 1]; val bw = st[k * 5 + 2]; val bh = st[k * 5 + 3]
            if (bh < 8 || bh > 80 || bw < bh * 1.5 || bw > 600) continue
            var bestRow = 0; var bestFill = 0
            for (y in 0 until bh) {
                var c = 0
                val o = (by + y) * w + bx
                for (x in 0 until bw) if (lab[o + x] == k) c++
                if (c > bestFill) { bestFill = c; bestRow = y }
            }
            if (bestFill < bw * 0.75) continue
            val pos = bestRow.toFloat() / (bh - 1)
            if (pos < 0.35f) top++ else if (pos > 0.65f) bottom++
        }
        return top to bottom
    }

    private fun inkMap(gray: Mat, longSide: Int): Mat {
        val s = longSide.toDouble() / max(gray.cols(), gray.rows())
        val small = Mat()
        Imgproc.resize(gray, small, Size(gray.cols() * s, gray.rows() * s), 0.0, 0.0, if (s < 1) Imgproc.INTER_AREA else Imgproc.INTER_LINEAR)
        val ink = Mat()
        Imgproc.adaptiveThreshold(small, ink, 255.0, Imgproc.ADAPTIVE_THRESH_MEAN_C, Imgproc.THRESH_BINARY_INV, 31, 15.0)
        small.release()
        return ink
    }

    /** High-frequency energy of a profile: text lines alternating with gaps. */
    private fun energy(p: FloatArray): Double {
        val sigma = 3.0
        val r = (sigma * 3).toInt()
        val k = DoubleArray(2 * r + 1) { i -> exp(-((i - r) * (i - r)) / (2 * sigma * sigma)) }
        var e = 0.0
        for (i in p.indices) {
            var s = 0.0; var ws = 0.0
            for (j in -r..r) { val t = i + j; if (t in p.indices) { s += p[t] * k[j + r]; ws += k[j + r] } }
            val d = p[i] - s / ws
            e += d * d
        }
        return e / p.size
    }

    private const val TAG = "TextOrientation"
}
