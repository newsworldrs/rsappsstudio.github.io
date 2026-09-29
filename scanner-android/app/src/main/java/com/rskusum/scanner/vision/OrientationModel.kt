package com.rskusum.scanner.vision

import android.content.Context
import android.util.Log
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * On-device text orientation classifier (PP-LCNet, 224x224): tells whether the text on a flattened
 * page reads upright, or is turned 90 / 180 / 270 degrees - like ML Kit / Adobe Scan, a page shot
 * upside down or sideways comes out upright.
 *
 * Input: RGB, ImageNet-normalised, NHWC 1x224x224x3. Output: softmax over [0, 90, 180, 270]
 * where "90" means the content is turned 90 degrees clockwise.
 */
class OrientationModel private constructor(private val interpreter: Interpreter) {

    private val input = ByteBuffer.allocateDirect(SIZE * SIZE * 3 * 4).order(ByteOrder.nativeOrder())
    private val output = ByteBuffer.allocateDirect(4 * 4).order(ByteOrder.nativeOrder())
    private val pixels = ByteArray(SIZE * SIZE * 3)

    /**
     * @param rgb flattened page, 8UC3 RGB.
     * @return clockwise rotation (0/90/180/270) that makes the text upright, or 0 when unsure
     *   (blank page, pictures only).
     */
    @Synchronized
    fun uprightRotation(rgb: Mat): Int {
        val sum = FloatArray(4)
        var votes = 0
        // Two scales x three positions along the page's long side: small text and big headings
        // both get looked at, and a blank middle of the page doesn't decide on its own.
        for (short in intArrayOf(256, 400)) {
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
        if (votes == 0) return 0
        val best = sum.indices.maxBy { sum[it] }
        val conf = sum[best] / votes
        Log.d("OrientationModel", "votes=$votes probs=${sum.map { it / votes }} -> ${best * 90} ($conf)")
        if (best == 0 || conf < MIN_CONFIDENCE) return 0
        // Content turned N degrees clockwise -> rotate (360 - N) clockwise to make it upright.
        return (360 - best * 90) % 360
    }

    private fun classify(crop: Mat): FloatArray {
        crop.get(0, 0, pixels)
        input.rewind()
        val fb = input.asFloatBuffer()
        var i = 0
        while (i < pixels.size) {
            for (c in 0..2) {
                val v = (pixels[i + c].toInt() and 0xFF) / 255f
                fb.put((v - MEAN[c]) / STD[c])
            }
            i += 3
        }
        output.rewind()
        interpreter.run(input, output)
        output.rewind()
        return FloatArray(4).also { output.asFloatBuffer().get(it) }
    }

    companion object {
        const val SIZE = 224
        private const val MIN_CONFIDENCE = 0.6f
        private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)
        private const val ASSET = "models/doc_orientation.tflite"

        @Volatile private var instance: OrientationModel? = null
        @Volatile private var failed = false

        /** Loads once; returns null if the model can't be used (pages then keep their rotation). */
        fun get(context: Context): OrientationModel? {
            instance?.let { return it }
            synchronized(this) {
                instance?.let { return it }
                if (failed) return null
                return try {
                    val fd = context.assets.openFd(ASSET)
                    val buffer = FileInputStream(fd.fileDescriptor).channel
                        .map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
                    OrientationModel(Interpreter(buffer, Interpreter.Options().setNumThreads(2)))
                        .also { instance = it }
                } catch (t: Throwable) {
                    failed = true
                    Log.e("OrientationModel", "load failed", t)
                    null
                }
            }
        }
    }
}
