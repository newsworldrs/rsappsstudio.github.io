package com.rskusum.scanner.vision

import android.content.Context
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.dnn.Dnn
import org.opencv.dnn.Net

/**
 * Small helper around OpenCV's DNN module for the bundled ONNX models. The models run on OpenCV
 * (already part of the library and built for 16 KB memory pages) - no TensorFlow Lite, whose
 * native library is not 16 KB aligned and is flagged by Google Play for Android 15+.
 */
internal object OnnxNet {

    /** Loads an ONNX model from the library's assets. */
    fun load(context: Context, asset: String): Net {
        val bytes = context.assets.open(asset).use { it.readBytes() }
        val buf = MatOfByte(*bytes)
        try {
            return Dnn.readNetFromONNX(buf).apply {
                setPreferableBackend(Dnn.DNN_BACKEND_OPENCV)
                setPreferableTarget(Dnn.DNN_TARGET_CPU)
            }
        } finally {
            buf.release()
        }
    }

    /** Fixed test picture (bytes 0..255, any length); the same formula is used to compute the expected outputs. */
    fun testPattern(n: Int): FloatArray = FloatArray(n) { i -> ((i.toLong() * 7 + (i / 3).toLong() * 13) % 256).toFloat() }

    /** NHWC float input blob of shape (1, h, w, c) filled from [data]. */
    fun nhwc(h: Int, w: Int, c: Int, data: FloatArray): Mat =
        Mat(intArrayOf(1, h, w, c), CvType.CV_32F).apply { put(intArrayOf(0, 0, 0, 0), data) }

    /** Runs [net] on [input] and returns the output values in order. [input] is released. */
    fun forward(net: Net, input: Mat, out: FloatArray): FloatArray {
        net.setInput(input)
        val y = net.forward()
        input.release()
        y.get(IntArray(y.dims()), out) // all values, in order, from the first element
        y.release()
        return out
    }
}
