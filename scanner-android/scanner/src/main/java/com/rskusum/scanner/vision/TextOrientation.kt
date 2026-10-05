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
            // 1. Headline scripts: count word headlines on top / bottom with the lines horizontal.
            //    Upright = many on top, almost none at the bottom; this also works when the line
            //    direction is unclear (tables, forms, mixed layouts).
            val none = Headlines(0, 0, 0)
            val rows = if (axis != Axis.COLUMNS) headlines(gray) else none
            val cols = if (axis != Axis.ROWS && allowQuarter) {
                val r = Mat(); Core.rotate(gray, r, Core.ROTATE_90_CLOCKWISE)
                headlines(r).also { r.release() }
            } else none
            // (rotation, words with the bar on top after it, words with the bar at the bottom, words)
            val votes = listOf(
                intArrayOf(0, rows.top, rows.bottom, rows.words), intArrayOf(180, rows.bottom, rows.top, rows.words),
                intArrayOf(90, cols.top, cols.bottom, cols.words), intArrayOf(270, cols.bottom, cols.top, cols.words),
            )
            val best = votes.maxBy { it[1] - it[2] }
            Log.d(TAG, "axis=$axis headlines rows=$rows cols=$cols")
            // A headline script: most words carry the bar, all on the same side.
            if (best[1] >= 5 && best[1] >= best[2] * 5 && best[1] >= best[3] * 0.3) return best[0]

            // 2. Other scripts: the line direction says sideways or not, the model up or down.
            if (model == null) return 0
            if (axis == null) {
                if (!allowQuarter) return if (upsideDown(rgb, model)) 180 else 0
                return allTurns(rgb, model)
            }
            if (axis == Axis.COLUMNS && !allowQuarter) return 0
            if (axis == Axis.ROWS) return if (upsideDown(rgb, model)) 180 else 0
            val turned = Mat(); Core.rotate(rgb, turned, Core.ROTATE_90_CLOCKWISE)
            return try { if (upsideDown(turned, model)) 270 else 90 } finally { turned.release() }
        } catch (t: Throwable) {
            Log.e(TAG, "orientation failed", t)
            return 0
        } finally {
            gray.release()
        }
    }

    /**
     * No clear text lines: the model looks at the page in all four turns and the votes are added
     * up (the same page seen four ways), so one biased look can't decide alone.
     */
    private fun allTurns(rgb: Mat, model: OrientationModel): Int {
        val score = FloatArray(4)
        val codes = intArrayOf(-1, Core.ROTATE_90_CLOCKWISE, Core.ROTATE_180, Core.ROTATE_90_COUNTERCLOCKWISE)
        for (k in 0..3) {
            val img = if (k == 0) rgb else Mat().also { Core.rotate(rgb, it, codes[k]) }
            val p = model.probabilities(img)
            if (k != 0) img.release()
            // Turning the picture k quarter turns clockwise turns its content k quarters further.
            if (p != null) for (a in 0..3) score[a] += p[(a + k) % 4] / 4
        }
        val a = score.indices.maxBy { score[it] }
        Log.d(TAG, "all turns ${score.toList()}")
        return if (a == 0 || score[a] < 0.5f) 0 else (360 - a * 90) % 360
    }

    /**
     * Upside down? Asked twice (the page as is and turned 180 degrees) so a bias of the model
     * towards one answer cancels out; only a clear result flips the page.
     */
    private fun upsideDown(rgb: Mat, model: OrientationModel): Boolean {
        val p = model.probabilities(rgb) ?: return false
        val r = Mat(); Core.rotate(rgb, r, Core.ROTATE_180)
        val q = model.probabilities(r)
        r.release()
        if (q == null) return p[2] >= 0.6f && p[2] > p[0] * 2
        val up = p[0] + q[2]
        val down = p[2] + q[0]
        return down >= 1.2f && down > up * 2
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

    /** Word-sized blobs ([words]) and how many carry a thin full-width bar on top / at the bottom. */
    data class Headlines(val top: Int, val bottom: Int, val words: Int)

    /**
     * Words with a thin bar across their full width (a Devanagari / Bengali / Gurmukhi headline)
     * at their top vs at their bottom, text lines horizontal. Latin letters that run together
     * give thick or partial bars and are not counted.
     */
    fun headlines(gray: Mat): Headlines {
        val ink = inkMap(gray, 1600)
        val labels = Mat(); val stats = Mat(); val cents = Mat()
        val n = Imgproc.connectedComponentsWithStats(ink, labels, stats, cents, 8, CvType.CV_32S)
        val w = labels.cols()
        val lab = IntArray(w * labels.rows())
        labels.get(0, 0, lab)
        val st = IntArray(n * 5)
        stats.get(0, 0, st)
        ink.release(); labels.release(); stats.release(); cents.release()
        var top = 0; var bottom = 0; var words = 0
        for (k in 1 until n) {
            val bx = st[k * 5]; val by = st[k * 5 + 1]; val bw = st[k * 5 + 2]; val bh = st[k * 5 + 3]
            if (bh < 8 || bh > 80 || bw < bh * 1.5 || bw > 600) continue
            words++
            val fill = IntArray(bh)
            var bestRow = 0
            for (y in 0 until bh) {
                var c = 0
                val o = (by + y) * w + bx
                for (x in 0 until bw) if (lab[o + x] == k) c++
                fill[y] = c
                if (c > fill[bestRow]) bestRow = y
            }
            val bestFill = fill[bestRow]
            if (bestFill < bw * 0.95) continue
            // Thin bar: only a few rows are nearly as full as the fullest one.
            if (fill.count { it >= bestFill * 0.8 } > bh * 0.25) continue
            val pos = bestRow.toFloat() / (bh - 1)
            if (pos < 0.35f) top++ else if (pos > 0.65f) bottom++
        }
        return Headlines(top, bottom, words)
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
