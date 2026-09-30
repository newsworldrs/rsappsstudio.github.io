package com.rskusum.scanner.camera

import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.rskusum.scanner.R
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
    /** Book mode: find the spine live (used in Free mode, where there is no guide line). */
    @Volatile var bookMode = false
    private var spine: SpineGuide? = null

    /** AI Text mode: look for text lines on the live preview. */
    @Volatile var textMode = false
    private var textBoxes: List<com.rskusum.scanner.vision.NRect> = emptyList()
    /** Guide frame (normalized) or null for free detection. */
    @Volatile var frame: Quad? = null
    /** Fingerprints of pages already scanned this session: never auto-capture them again. */
    @Volatile var knownPages: List<com.rskusum.scanner.vision.PageSignature> = emptyList()
    /** False when the current mode is finished (e.g. both ID card sides captured). */
    @Volatile var captureAllowed = true
    /** Page edges found near the guide frame in a recent frame (0..4), refreshed every few frames. */
    private var frameSides = 0

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
    private var frameCount = 0L

    override fun analyze(image: ImageProxy) {
        try {
            if (paused) return
            frameCount++
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
                    if (frameCount % 3 == 0L) {
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
                val guide = frame
                var trackQuad = quad
                var guidance: Int? = null
                if (guide != null && frameCount % 3 == 0L) {
                    // Same band search used on the final photo, on the live frame: tells whether a
                    // real page sits in the frame even when the global detectors miss it.
                    val snap = DocumentDetector.snapToQuad(gray, guide, maxDim = 720)
                    frameSides = snap.sidesFound
                    if (quad == null && snap.sidesFound >= 3) snappedQuad = snap.quad
                    else if (snap.sidesFound < 3) snappedQuad = null
                }
                if (guide == null) { frameSides = 0; snappedQuad = null }
                if (guide != null && quad == null) trackQuad = snappedQuad
                if (guide != null && trackQuad != null) {
                    val q = trackQuad
                    // Only a page lined up with the guide frame counts for auto-capture.
                    val ratio = q.area() / guide.area()
                    val aligned = q.distanceTo(guide) < ALIGN_TOLERANCE
                    if (!aligned) {
                        trackQuad = null
                        guidance = when {
                            ratio < 0.7f -> R.string.rs_scanner_move_closer
                            ratio > 1.3f -> R.string.rs_scanner_move_back
                            else -> R.string.rs_scanner_align_with_frame
                        }
                    }
                }
                if (!captureAllowed) guidance = R.string.rs_scanner_scan_complete_review
                val spreadQuad = trackQuad ?: quad
                if (bookMode && guide == null && spreadQuad != null &&
                    !DocumentDetector.isTallSpread(spreadQuad, gray.cols(), gray.rows())
                ) {
                    // Book lying across a portrait screen: small, and split the wrong way. Ask the
                    // user to turn the phone (the spread must fill the long side), no auto-capture.
                    trackQuad = null
                    guidance = R.string.rs_scanner_book_turn_phone
                }
                if (!bookMode || guide != null || spreadQuad == null || guidance == R.string.rs_scanner_book_turn_phone) {
                    spine = null
                } else if (frameCount % 5 == 0L) {
                    spine = liveSpine(gray, spreadQuad)
                }
                if (!textMode) {
                    textBoxes = emptyList()
                } else if (frameCount % 4 == 0L) {
                    // Live text highlighting, limited to the page (or the guide frame).
                    textBoxes = com.rskusum.scanner.vision.TextRegionDetector.detect(gray, trackQuad ?: quad ?: guide)
                }
                val sig = trackQuad?.let { DocumentDetector.signature(gray, it) }
                val (state, fire0) = tracker.update(
                    trackQuad, sig, SystemClock.elapsedRealtime(), autoCapture && captureAllowed, scene,
                    // "Hold still" capture only with evidence of a page in the frame (>= 2 real
                    // edges); never in Free mode. Stops captures of an empty table/bed.
                    allowFallback = guidance == null && guide != null && frameSides >= 2,
                )
                var fire = fire0
                if (fire) {
                    // Never auto-capture a page that is already in this session.
                    val known = knownPages
                    val sig = if (known.isEmpty()) null
                    else com.rskusum.scanner.vision.PageMatcher.signature(gray, trackQuad ?: guide ?: Quad.inset(0.05f))
                    if (sig != null && known.any { com.rskusum.scanner.vision.PageMatcher.isSamePage(sig, it) }) {
                        fire = false
                        tracker.onCaptured(true) // mark this scene as done; re-arms when it changes
                        guidance = R.string.rs_scanner_already_scanned_live
                    }
                }
                lastQuad = state.quad
                onState(state.copy(rawQuad = quad, aligned = guide != null && trackQuad != null, guidance = guidance, textBoxes = textBoxes, spine = spine))
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

    private var snappedQuad: Quad? = null

    /** Spine line + centres of the two halves of the spread in [q]. */
    private fun liveSpine(gray: Mat, q: Quad): SpineGuide {
        val w = gray.cols(); val h = gray.rows()
        val t = DocumentDetector.findSpine(gray, q)
        val (a, b) = DocumentDetector.splitSpread(q, w, h, t)
        fun centre(x: Quad) = com.rskusum.scanner.vision.NPoint(x.points.map { it.x }.average().toFloat(), x.points.map { it.y }.average().toFloat())
        val (m0, m1) = DocumentDetector.spineLine(q, w, h, t)
        return SpineGuide(m0, m1, centre(a), centre(b))
    }

    private companion object {
        /** Max corner distance (normalized) between detection and guide frame to count as aligned. */
        const val ALIGN_TOLERANCE = 0.09f
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
