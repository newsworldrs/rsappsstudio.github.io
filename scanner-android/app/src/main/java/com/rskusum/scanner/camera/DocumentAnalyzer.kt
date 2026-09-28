package com.rskusum.scanner.camera

import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.rskusum.scanner.vision.DocumentDetector
import com.rskusum.scanner.vision.EdgeModel
import com.rskusum.scanner.vision.Quad
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import org.opencv.objdetect.QRCodeDetector

/**
 * Runs on the CameraX analysis thread on RGBA frames, rotated upright so quads are expressed
 * in the orientation the user sees.
 *
 * Detection order: the learned edge model ([EdgeModel]) + geometric search first; the classic
 * gradient detector only if the model finds nothing (or failed to load).
 */
class DocumentAnalyzer(
    private val tracker: AutoCaptureTracker,
    private val edgeModel: EdgeModel?,
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

    /** Frames analyzed so far, and the last failure - surfaced on screen so problems are visible. */
    @Volatile var framesAnalyzed = 0L
        private set
    @Volatile var lastError: String? = null
        private set
    @Volatile var frameSize: String = ""
        private set
    /** Which detector produced the last outline: "ml", "cv" or "-". */
    @Volatile var lastSource: String = "-"
        private set

    private var buffer = ByteArray(0)
    private val qrDetector by lazy { QRCodeDetector() }
    private var frame = 0L

    override fun analyze(image: ImageProxy) {
        try {
            if (paused) return
            frame++
            frameSize = "${image.width}x${image.height}"
            val src = upright(image)
            val gray = Mat()
            val rgb = Mat()
            try {
                if (src.channels() == 4) {
                    Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)
                    Imgproc.cvtColor(src, rgb, Imgproc.COLOR_RGBA2RGB)
                } else {
                    src.copyTo(gray)
                    Imgproc.cvtColor(src, rgb, Imgproc.COLOR_GRAY2RGB)
                }
                if (qrMode) {
                    if (frame % 3 == 0L) {
                        val text = qrDetector.detectAndDecode(gray)
                        if (!text.isNullOrEmpty()) onQr(text)
                    }
                    return
                }
                val scene = DocumentDetector.sceneSignature(gray)
                val prev = tracker.currentAnchor
                var quad: Quad? = null
                if (edgeModel != null) {
                    val prob = edgeModel.run(rgb)
                    quad = DocumentDetector.detectFromEdgeMap(prob, prev)
                    prob.release()
                    if (quad != null) lastSource = "ml"
                }
                if (quad == null) {
                    quad = DocumentDetector.detect(gray, prev = prev)
                    lastSource = if (quad != null) "cv" else "-"
                }
                val sig = quad?.let { DocumentDetector.signature(gray, it) }
                val (state, fire) = tracker.update(quad, sig, SystemClock.elapsedRealtime(), autoCapture, scene)
                lastQuad = state.quad
                onState(state)
                framesAnalyzed++
                lastError = null
                if (fire) onAutoCapture()
            } finally {
                src.release(); gray.release(); rgb.release()
            }
        } catch (t: Throwable) {
            Log.w("DocumentAnalyzer", "analysis failed", t)
            lastError = "${t.javaClass.simpleName}: ${t.message}"
        } finally {
            image.close()
        }
    }

    /** Copies plane 0 (RGBA_8888 = 4 bytes/px, or Y = 1 byte/px) into an upright Mat. */
    private fun upright(image: ImageProxy): Mat {
        val plane = image.planes[0]
        val w = image.width
        val h = image.height
        val px = plane.pixelStride.coerceIn(1, 4)
        val rowBytes = w * px
        val rowStride = plane.rowStride
        val buf = plane.buffer
        buf.rewind()
        if (buffer.size != rowBytes * h) buffer = ByteArray(rowBytes * h)
        if (rowStride == rowBytes && buf.remaining() >= rowBytes * h) {
            buf.get(buffer, 0, rowBytes * h)
        } else {
            for (row in 0 until h) {
                buf.position(row * rowStride)
                buf.get(buffer, row * rowBytes, rowBytes)
            }
        }
        val mat = Mat(h, w, if (px == 4) CvType.CV_8UC4 else CvType.CV_8UC1)
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
