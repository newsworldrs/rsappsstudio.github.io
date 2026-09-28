package com.rskusum.scanner.camera

import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.rskusum.scanner.vision.DocumentDetector
import com.rskusum.scanner.vision.Quad
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.objdetect.QRCodeDetector

/**
 * Runs on the CameraX analysis thread. Uses only the luminance plane of the YUV frame,
 * rotated upright, so quads are expressed in the same orientation the user sees.
 */
class DocumentAnalyzer(
    private val tracker: AutoCaptureTracker,
    private val onState: (TrackerState) -> Unit,
    private val onAutoCapture: () -> Unit,
    private val onQr: (String) -> Unit,
) : ImageAnalysis.Analyzer {

    @Volatile var autoCapture = true
    @Volatile var qrMode = false
    @Volatile var paused = false

    /** Last detected quad, used as a fallback when the high-res photo is ambiguous. */
    @Volatile var lastQuad: Quad? = null
        private set

    private var buffer = ByteArray(0)
    private val qrDetector by lazy { QRCodeDetector() }
    private var frame = 0L

    override fun analyze(image: ImageProxy) {
        try {
            if (paused) return
            frame++
            val gray = upright(image)
            try {
                if (qrMode) {
                    if (frame % 3 == 0L) {
                        val text = qrDetector.detectAndDecode(gray)
                        if (!text.isNullOrEmpty()) onQr(text)
                    }
                    return
                }
                val quad = DocumentDetector.detect(gray, prev = tracker.currentAnchor)
                val sig = quad?.let { DocumentDetector.signature(gray, it) }
                val (state, fire) = tracker.update(quad, sig, SystemClock.elapsedRealtime(), autoCapture)
                lastQuad = state.quad
                onState(state)
                if (fire) onAutoCapture()
            } finally {
                gray.release()
            }
        } catch (t: Throwable) {
            Log.w("DocumentAnalyzer", "analysis failed", t)
        } finally {
            image.close()
        }
    }

    private fun upright(image: ImageProxy): Mat {
        val plane = image.planes[0]
        val w = image.width
        val h = image.height
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val buf = plane.buffer
        buf.rewind()
        if (buffer.size != w * h) buffer = ByteArray(w * h)
        if (rowStride == w && pixelStride == 1 && buf.remaining() >= w * h) {
            buf.get(buffer, 0, w * h)
        } else if (pixelStride == 1) {
            for (row in 0 until h) {
                buf.position(row * rowStride)
                buf.get(buffer, row * w, w)
            }
        } else {
            for (row in 0 until h) for (col in 0 until w) buffer[row * w + col] = buf.get(row * rowStride + col * pixelStride)
        }
        val mat = Mat(h, w, CvType.CV_8UC1)
        mat.put(0, 0, buffer)
        val rot = when (image.imageInfo.rotationDegrees) {
            90 -> Core.ROTATE_90_CLOCKWISE
            180 -> Core.ROTATE_180
            270 -> Core.ROTATE_90_COUNTERCLOCKWISE
            else -> return mat
        }
        val out = Mat()
        Core.rotate(mat, out, rot)
        mat.release()
        return out
    }
}
