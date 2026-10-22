package com.rskusum.scanner.vision

import android.content.Context
import android.util.Log
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.dnn.Net

/**
 * On-device learned edge detector (HED-lite, 256x256). Unlike Canny it responds to object
 * *boundaries* only - paper against table, book against bedsheet - and ignores printed text,
 * fabric patterns and noise, which is what makes white-on-white and busy backgrounds work.
 *
 * Input: RGB 0..255 float, 256x256 (stretched, not letterboxed). Output: 256x256 edge strength.
 */
class EdgeModel private constructor(private val net: Net) {

    private val pixels = ByteArray(SIZE * SIZE * 3)
    private val values = FloatArray(SIZE * SIZE * 3)
    private val result = FloatArray(SIZE * SIZE)

    /**
     * @param rgb upright 8UC3 RGB image of any size.
     * @return 256x256 CV_32F edge-strength map (caller releases).
     */
    @Synchronized
    fun run(rgb: Mat): Mat {
        val small = Mat()
        Imgproc.resize(rgb, small, Size(SIZE.toDouble(), SIZE.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        small.get(0, 0, pixels)
        small.release()
        for (i in pixels.indices) values[i] = (pixels[i].toInt() and 0xFF).toFloat()
        OnnxNet.forward(net, OnnxNet.nhwc(SIZE, SIZE, 3, values), result)
        val map = Mat(SIZE, SIZE, CvType.CV_32F)
        map.put(0, 0, result)
        return map
    }

    /** Runs the fixed test picture and compares with the original TensorFlow Lite model's output. */
    private fun selfTest(): Boolean {
        val out = OnnxNet.forward(net, OnnxNet.nhwc(SIZE, SIZE, 3, OnnxNet.testPattern(SIZE * SIZE * 3)), FloatArray(SIZE * SIZE))
        var ok = kotlin.math.abs(out.average() - TEST_MEAN) < 0.3
        for (k in TEST_INDEX.indices) if (kotlin.math.abs(out[TEST_INDEX[k]] - TEST_VALUE[k]) > 0.8f) ok = false
        if (!ok) Log.e("EdgeModel", "self-test mismatch: ${TEST_INDEX.map { out[it] }} mean=${out.average()}")
        return ok
    }

    companion object {
        const val SIZE = 256
        // Original model's output for OnnxNet.testPattern (computed with TensorFlow Lite).
        private val TEST_INDEX = intArrayOf(0, 1000, 12345, 32896, 40000, 50000, 65535, 20202)
        private val TEST_VALUE = floatArrayOf(-6.310f, -9.639f, -13.873f, -7.772f, -15.517f, -14.505f, -4.296f, -9.593f)
        private const val TEST_MEAN = -10.4976
        private const val ASSET = "models/doc_edges_hed_lite.onnx"

        @Volatile private var instance: EdgeModel? = null
        @Volatile var loadError: String? = null
            private set

        /** The model if it is already loaded, without waiting (null while loading or when off). */
        fun ready(): EdgeModel? = instance

        /** Loads once; returns null (and records [loadError]) if the model can't be used. */
        fun get(context: Context): EdgeModel? {
            instance?.let { return it }
            synchronized(this) {
                instance?.let { return it }
                if (loadError != null) return null
                return try {
                    val model = EdgeModel(OnnxNet.load(context, ASSET))
                    // Self-check on this phone: a fixed test picture must give the original
                    // model's answer, or the model stays off (classic edge detection is used).
                    check(model.selfTest()) { "self-test failed" }
                    model.also { instance = it }
                } catch (t: Throwable) {
                    loadError = "Edge model: ${t.javaClass.simpleName}: ${t.message}"
                    Log.e("EdgeModel", "load failed", t)
                    null
                }
            }
        }
    }
}
