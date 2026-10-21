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

    companion object {
        const val SIZE = 256
        private const val ASSET = "models/doc_edges_hed_lite.onnx"

        @Volatile private var instance: EdgeModel? = null
        @Volatile var loadError: String? = null
            private set

        /** Loads once; returns null (and records [loadError]) if the model can't be used. */
        fun get(context: Context): EdgeModel? {
            instance?.let { return it }
            synchronized(this) {
                instance?.let { return it }
                if (loadError != null) return null
                return try {
                    EdgeModel(OnnxNet.load(context, ASSET)).also { instance = it }
                } catch (t: Throwable) {
                    loadError = "Edge model: ${t.javaClass.simpleName}: ${t.message}"
                    Log.e("EdgeModel", "load failed", t)
                    null
                }
            }
        }
    }
}
