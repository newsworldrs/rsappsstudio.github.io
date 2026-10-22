package com.rskusum.scanner.vision

import android.content.Context
import android.util.Log
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.dnn.Net
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * On-device text orientation classifier (PP-LCNet, 224x224): tells whether the text on a flattened
 * page reads upright, or is turned 90 / 180 / 270 degrees, so a page shot
 * upside down or sideways comes out upright.
 *
 * Input: RGB, ImageNet-normalised, NHWC 1x224x224x3. Output: softmax over [0, 90, 180, 270]
 * where "90" means the content is turned 90 degrees clockwise.
 */
class OrientationModel private constructor(private val net: Net) {

    private val pixels = ByteArray(SIZE * SIZE * 3)
    private val values = FloatArray(SIZE * SIZE * 3)

    /**
     * @param rgb flattened page, 8UC3 RGB.
     * @return clockwise rotation (0/90/180/270) that makes the text upright, or 0 when unsure
     *   (blank page, pictures only).
     */
    @Synchronized
    fun uprightRotation(rgb: Mat): Int {
        val p = probabilities(rgb) ?: return 0
        val best = p.indices.maxBy { p[it] }
        val conf = p[best]
        if (best == 0 || conf < MIN_CONFIDENCE) return 0
        // Content turned N degrees clockwise -> rotate (360 - N) clockwise to make it upright.
        return (360 - best * 90) % 360
    }

    /**
     * Average class probabilities [0, 90, 180, 270] (content turned clockwise) over the crops that
     * show something readable, or null when none does.
     */
    @Synchronized
    fun probabilities(rgb: Mat): FloatArray? {
        val sum = FloatArray(4)
        var votes = 0
        // Three positions along the page's long side at one scale (between small text and big
        // headings): a blank middle of the page doesn't decide on its own. Three crops instead of
        // six keep the page preparation fast.
        for (short in intArrayOf(320)) {
            val s = short.toDouble() / min(rgb.cols(), rgb.rows())
            val w = max(SIZE, (rgb.cols() * s).roundToInt())
            val h = max(SIZE, (rgb.rows() * s).roundToInt())
            val small = Mat()
            Imgproc.resize(rgb, small, Size(w.toDouble(), h.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            for (f in doubleArrayOf(0.2, 0.5, 0.8)) {
                val x = if (w >= h) ((w - SIZE) * f).roundToInt() else (w - SIZE) / 2
                val y = if (h > w) ((h - SIZE) * f).roundToInt() else (h - SIZE) / 2
                val crop = Mat(small, Rect(x, y, SIZE, SIZE)).clone()
                val p = classify(crop)
                crop.release()
                if (p.max() < 0.4f) continue // nothing readable in this crop
                for (i in 0..3) sum[i] += p[i]
                votes++
            }
            small.release()
        }
        if (votes == 0) return null
        Log.d("OrientationModel", "votes=$votes probs=${sum.map { it / votes }}")
        return FloatArray(4) { sum[it] / votes }
    }

    private fun classify(crop: Mat): FloatArray {
        crop.get(0, 0, pixels)
        var i = 0
        while (i < pixels.size) {
            for (c in 0..2) {
                val v = (pixels[i + c].toInt() and 0xFF) / 255f
                values[i + c] = (v - MEAN[c]) / STD[c]
            }
            i += 3
        }
        return OnnxNet.forward(net, OnnxNet.nhwc(SIZE, SIZE, 3, values), FloatArray(4))
    }

    /** Runs the fixed test picture and compares with the original TensorFlow Lite model's output. */
    private fun selfTest(): Boolean {
        val raw = OnnxNet.testPattern(SIZE * SIZE * 3)
        val v = FloatArray(raw.size) { i -> (raw[i] / 255f - MEAN[i % 3]) / STD[i % 3] }
        val p = OnnxNet.forward(net, OnnxNet.nhwc(SIZE, SIZE, 3, v), FloatArray(4))
        val ok = p.indices.all { kotlin.math.abs(p[it] - TEST_PROBS[it]) < 0.08f }
        if (!ok) Log.e("OrientationModel", "self-test mismatch: ${p.toList()}")
        return ok
    }

    companion object {
        const val SIZE = 224
        // Original model's output for OnnxNet.testPattern (computed with TensorFlow Lite).
        private val TEST_PROBS = floatArrayOf(0.4623f, 0.0274f, 0.4800f, 0.0304f)
        private const val MIN_CONFIDENCE = 0.6f
        private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)
        private const val ASSET = "models/doc_orientation.onnx"

        @Volatile private var instance: OrientationModel? = null
        @Volatile private var failed = false

        /** Loads once; returns null if the model can't be used (pages then keep their rotation). */
        fun get(context: Context): OrientationModel? {
            instance?.let { return it }
            synchronized(this) {
                instance?.let { return it }
                if (failed) return null
                return try {
                    val model = OrientationModel(OnnxNet.load(context, ASSET))
                    // Self-check on this phone (see EdgeModel): off when it doesn't match.
                    check(model.selfTest()) { "self-test failed" }
                    model.also { instance = it }
                } catch (t: Throwable) {
                    failed = true
                    Log.e("OrientationModel", "load failed", t)
                    null
                }
            }
        }
    }
}
