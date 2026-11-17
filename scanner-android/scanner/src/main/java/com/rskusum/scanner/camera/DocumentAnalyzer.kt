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
    /** The learned edge model once it is loaded (loads in the background; null = not ready / off). */
    private val edgeModel: () -> EdgeModel?,
    private val onState: (TrackerState) -> Unit,
    /** Fires an auto capture, with the live signature of the page (null if none could be made). */
    private val onAutoCapture: (com.rskusum.scanner.vision.PageSignature?) -> Unit,
    private val onQr: (String) -> Unit,
) : ImageAnalysis.Analyzer {

    @Volatile var autoCapture = true
    @Volatile var qrMode = false
    @Volatile var paused = false
    /**
     * Photos are being turned into pages: only every third frame is analysed meanwhile, leaving
     * the processor to them (the live outline stays smooth enough, and pages appear sooner).
     */
    @Volatile var busy = false
    private var skipped = 0L
    /** How long the last photo took, start to finish, to become a ready page (ms), for the status line. */
    @Volatile var lastCaptureMs = 0L
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
            if (busy && ++skipped % 3L != 0L) return
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
                val guide = frame
                var quad: Quad? = null
                // The page outline itself is looked for in every mode: in frame mode too the
                // shutter waits for a real outline (see below), not for the frame alone.
                val model = edgeModel()
                if (model != null) {
                    val prob = model.run(rgb)
                    quad = DocumentDetector.detectFromEdgeMap(prob, prev)
                    prob.release()
                    if (quad != null) lastSource = "ml"
                }
                if (quad == null) {
                    quad = DocumentDetector.detect(gray, prev = prev)
                    lastSource = if (quad != null) "cv" else "-"
                }
                var trackQuad = quad
                var guidance: Int? = null
                if (guide != null && frameCount % 2 == 0L) {
                    // Frame mode: look for the page's edges near each side of the frame (the same
                    // band search used on the final photo). Sides that aren't found are completed
                    // from the found ones and the frame, so 2 real sides (or 2-3 corners) are
                    // enough - the page is in place even if a global detector sees only part of it
                    // (e.g. one page of an open book). A wider band catches a page larger than the
                    // frame (user very close).
                    var snap = DocumentDetector.snapToQuad(gray, guide, maxDim = 720)
                    if (snap.sidesFound < 2) {
                        val wide = DocumentDetector.snapToQuad(gray, guide, maxDim = 720, bandFraction = 0.16)
                        if (wide.sidesFound > snap.sidesFound) snap = wide
                    }
                    frameSides = snap.sidesFound
                    snappedQuad = when {
                        // All four sides on real edges near the frame: an outline in its own right.
                        snap.sidesFound >= 4 -> snap.quad
                        // Open book: the detectors often see one page only; three real sides near
                        // the frame are the spread (the capture searches it again on the photo).
                        bookMode && snap.sidesFound >= 3 -> snap.quad
                        else -> null
                    }
                }
                if (guide == null) { frameSides = 0; snappedQuad = null }
                if (guide != null) {
                    // Identify the page first, capture second: the page counts as "in the frame"
                    // only with a real four-sided outline - the detected outline when it sits in
                    // the frame (about its size, centred in it), else the four-side frame search.
                    // Two or three edges near the frame used to be enough, and the missing sides
                    // were filled in from the frame: a capture without a real outline, cropped on
                    // the frame instead of the page.
                    val detected = quad?.takeIf { q ->
                        val cx = q.points.map { it.x }.average().toFloat(); val cy = q.points.map { it.y }.average().toFloat()
                        cx in guide.points.minOf { it.x }..guide.points.maxOf { it.x } &&
                            cy in guide.points.minOf { it.y }..guide.points.maxOf { it.y } &&
                            q.area() in guide.area() * 0.5f..guide.area() * 1.35f
                    }
                    val snapped = detected ?: snappedQuad
                    if (snapped != null) {
                        // Page is in the frame: track its outline.
                        trackQuad = snapped
                    } else {
                        trackQuad = null
                        // Nothing near the frame: only then use the global detection for advice.
                        val q = quad
                        if (q != null) {
                            val ratio = q.area() / guide.area()
                            guidance = when {
                                ratio < 0.45f -> R.string.rs_scanner_move_closer
                                ratio > 1.35f -> R.string.rs_scanner_move_back
                                else -> R.string.rs_scanner_align_with_frame
                            }
                        }
                    }
                }
                if (!captureAllowed) guidance = R.string.rs_scanner_scan_complete_review
                // Book, Free mode: if only one page was found, complete the open book around it.
                if (bookMode && guide == null && quad != null) {
                    val cached = spreadCache
                    val expanded = if (cached != null && cached.first.distanceTo(quad) < 0.04f && frameCount % 6 != 0L) {
                        cached.second
                    } else {
                        DocumentDetector.expandToSpread(gray, quad, maxDim = 640).also { spreadCache = quad to it }
                    }
                    if (trackQuad === quad) trackQuad = expanded
                    quad = expanded
                } else {
                    spreadCache = null
                }
                var spreadQuad = trackQuad ?: quad
                if (bookMode && guide == null && spreadQuad != null) {
                    val sq0 = spreadQuad
                    // Only one page found (the other isn't fully visible or its edges are faint):
                    // when its side on the dotted line is the spine, the other page is its mirror
                    // image across it. The book can then be held close, filling the screen.
                    if (!DocumentDetector.isTallSpread(sq0, gray.cols(), gray.rows())) {
                        val m = mirrorAcrossSpine(sq0)
                        if (m != null) { spreadQuad = m; trackQuad = m; quad = m }
                    }
                    // Auto capture only for an open book lined up with the dotted line: two pages
                    // (taller than wide on screen), big enough, the line through its middle.
                    val sq = spreadQuad ?: sq0
                    val top = (sq.tl.y + sq.tr.y) / 2; val bottom = (sq.bl.y + sq.br.y) / 2
                    val lineAt = if (bottom > top) (0.5f - top) / (bottom - top) else -1f
                    when {
                        !DocumentDetector.isTallSpread(sq, gray.cols(), gray.rows()) -> {
                            trackQuad = null; guidance = R.string.rs_scanner_book_turn_phone
                        }
                        sq.area() < 0.2f -> { trackQuad = null; guidance = R.string.rs_scanner_move_closer }
                        lineAt !in 0.35f..0.65f -> { trackQuad = null; guidance = R.string.rs_scanner_book_place }
                    }
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
                    // Auto capture needs a tracked outline in every mode: no "held still, capture
                    // anyway" without one.
                    allowFallback = false,
                )
                var fire = fire0
                var pageSig: com.rskusum.scanner.vision.PageSignature? = null
                if (fire) {
                    // Never auto-capture a page that is already in this session or still being
                    // processed. The signature also goes with the photo, so this page counts as
                    // known from now on, not only once its processing ends.
                    val known = knownPages
                    val sig = runCatching {
                        com.rskusum.scanner.vision.PageMatcher.signature(gray, trackQuad ?: guide ?: Quad.inset(0.05f))
                    }.getOrNull()
                    pageSig = sig
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
                if (fire) onAutoCapture(pageSig)
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
    /** Book / Free mode: last (detected page, completed spread), reused while the page doesn't move. */
    private var spreadCache: Pair<Quad, Quad>? = null

    /**
     * The open book around one found page whose top or bottom side lies on the dotted spine line
     * (the middle of the screen): that page plus its mirror image across the side. Null when
     * neither side is near the line.
     */
    private fun mirrorAcrossSpine(q: Quad): Quad? {
        fun p(x: Float, y: Float) = com.rskusum.scanner.vision.NPoint(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
        val top = (q.tl.y + q.tr.y) / 2; val bottom = (q.bl.y + q.br.y) / 2
        return when {
            // The top page: its bottom side is the spine.
            kotlin.math.abs(bottom - 0.5f) < 0.08f && top < 0.5f ->
                Quad(q.tl, q.tr, p(2 * q.br.x - q.tr.x, 2 * q.br.y - q.tr.y), p(2 * q.bl.x - q.tl.x, 2 * q.bl.y - q.tl.y))
            // The bottom page: its top side is the spine.
            kotlin.math.abs(top - 0.5f) < 0.08f && bottom > 0.5f ->
                Quad(p(2 * q.tl.x - q.bl.x, 2 * q.tl.y - q.bl.y), p(2 * q.tr.x - q.br.x, 2 * q.tr.y - q.br.y), q.br, q.bl)
            else -> null
        }
    }

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
