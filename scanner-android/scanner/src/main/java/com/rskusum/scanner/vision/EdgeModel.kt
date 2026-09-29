package com.rskusum.scanner.vision

import android.content.Context
import android.util.Log
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * On-device learned edge detector (HED-lite, 256x256). Unlike Canny it responds to object
 * *boundaries* only - paper against table, book against bedsheet - and ignores printed text,
 * fabric patterns and noise, which is what makes white-on-white and busy backgrounds work.
 *
 * Input: RGB 0..255 float, 256x256 (stretched, not letterboxed). Output: 256x256 edge strength.
 */
class EdgeModel private constructor(private val interpreter: Interpreter) {

    private val input = ByteBuffer.allocateDirect(SIZE * SIZE * 3 * 4).order(ByteOrder.nativeOrder())
    private val output = ByteBuffer.allocateDirect(SIZE * SIZE * 4).order(ByteOrder.nativeOrder())
    private val pixels = ByteArray(SIZE * SIZE * 3)
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
        input.rewind()
        val fb = input.asFloatBuffer()
        for (b in pixels) fb.put((b.toInt() and 0xFF).toFloat())
        output.rewind()
        interpreter.run(input, output)
        output.rewind()
        output.asFloatBuffer().get(result)
        val map = Mat(SIZE, SIZE, CvType.CV_32F)
        map.put(0, 0, result)
        return map
    }

    companion object {
        const val SIZE = 256
        private const val ASSET = "models/doc_edges_hed_lite.tflite"

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
                    val fd = context.assets.openFd(ASSET)
                    val buffer = FileInputStream(fd.fileDescriptor).channel
                        .map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
                    val options = Interpreter.Options().setNumThreads(2)
                    EdgeModel(Interpreter(buffer, options)).also { instance = it }
                } catch (t: Throwable) {
                    loadError = "Edge model: ${t.javaClass.simpleName}: ${t.message}"
                    Log.e("EdgeModel", "load failed", t)
                    null
                }
            }
        }
    }
}
