package com.rskusum.scanner

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rskusum.scanner.camera.AutoCaptureTracker
import com.rskusum.scanner.camera.TrackerState
import com.rskusum.scanner.data.DocumentStore
import com.rskusum.scanner.data.Images
import com.rskusum.scanner.data.Page
import com.rskusum.scanner.data.SavedDocument
import com.rskusum.scanner.data.ScanMode
import com.rskusum.scanner.vision.DocumentDetector
import com.rskusum.scanner.vision.ImageEnhancer
import com.rskusum.scanner.vision.Quad
import com.rskusum.scanner.vision.ScanFilter
import com.rskusum.scanner.vision.SmartFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.imgproc.Imgproc
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScannerViewModel(app: Application) : AndroidViewModel(app) {

    val store = DocumentStore(app)
    val tracker = AutoCaptureTracker()

    private val _camera = MutableStateFlow(TrackerState())
    val camera: StateFlow<TrackerState> = _camera
    fun postCameraState(s: TrackerState) { _camera.value = s }

    /** One-off user messages from the pipeline (e.g. duplicate capture rejected). */
    private val _messages = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: kotlinx.coroutines.flow.SharedFlow<String> = _messages

    // --- Capture settings ------------------------------------------------------------------
    var mode by mutableStateOf(ScanMode.DOCUMENT)
        private set
    var orientation by mutableStateOf(com.rskusum.scanner.data.FrameOrientation.PORTRAIT)
    var aiAssist by mutableStateOf(false)
    /** Book mode: false = top half is page 1 (default), true = bottom half is page 1. */
    var bookSwap by mutableStateOf(false)
    var autoCapture by mutableStateOf(true)

    // --- Configuration from the calling app ---------------------------------------------------
    var options by mutableStateOf(ScannerOptions())
        private set
    private var configured = false

    /** Applies the caller's [ScannerOptions] once per scanner session. */
    fun configure(o: ScannerOptions) {
        if (configured) return
        configured = true
        options = o
        autoCapture = o.autoCapture
        pdfQuality = o.pdfQuality
        orientation = o.startMode.defaultOrientation
        mode = o.startMode
    }

    /** Finished scans for the calling app (embedded mode). */
    private val _results = kotlinx.coroutines.flow.MutableSharedFlow<ScanResult>(extraBufferCapacity = 1)
    val results: kotlinx.coroutines.flow.SharedFlow<ScanResult> = _results

    /** True when [ScannerOptions.pageLimit] pages have been scanned. */
    val pageLimitReached: Boolean get() = options.pageLimit > 0 && pages.size >= options.pageLimit

    fun selectMode(m: ScanMode) {
        if (m != mode) resetIdCard()
        mode = m
        if (orientation != com.rskusum.scanner.data.FrameOrientation.FREE) orientation = m.defaultOrientation
    }

    // --- ID card: front, then back, both on one page ---------------------------------------
    enum class IdStep { FRONT, BACK, DONE }

    /** Front side captured in ID-card mode, waiting for the back. */
    private var idFront: Page? = null
    var idStep by mutableStateOf(IdStep.FRONT)
        private set

    private fun resetIdCard() {
        idFront = null
        idStep = IdStep.FRONT
    }

    /** False when the current mode must not take more photos (ID card already has both sides). */
    val captureAllowed: Boolean get() = !(mode == ScanMode.ID_CARD && idStep == IdStep.DONE) && !pageLimitReached

    /** Fingerprints of all pages in this session, for live duplicate prevention in the camera. */
    val sessionFingerprints: List<FloatArray>
        get() = pages.mapNotNull { it.fingerprint } + listOfNotNull(idFront?.fingerprint)

    /** Signatures of every page in this session (newest first), for duplicate prevention. */
    val sessionSignatures: List<com.rskusum.scanner.vision.PageSignature>
        get() = (pages.reversed().mapNotNull { it.signature } + listOfNotNull(idFront?.signature)).distinct()

    /** The on-screen guide frame (normalized), or null in free-detection mode. */
    val guideFrame: Quad?
        get() = when (orientation) {
            com.rskusum.scanner.data.FrameOrientation.PORTRAIT -> DocumentDetector.guideFrame(1 / mode.frameRatio)
            // An open book is always framed along the phone's long side (spread split top/bottom).
            com.rskusum.scanner.data.FrameOrientation.LANDSCAPE ->
                if (mode == ScanMode.BOOK) DocumentDetector.guideFrame(1 / mode.frameRatio) else DocumentDetector.guideFrame(mode.frameRatio)
            com.rskusum.scanner.data.FrameOrientation.FREE -> null
        }

    // --- Current scan session --------------------------------------------------------------
    val pages = mutableStateListOf<Page>()
    var documentName by mutableStateOf(defaultName())
    var processingCaptures by mutableStateOf(0)
        private set
    var importing by mutableStateOf(false)
        private set

    var documents by mutableStateOf<List<SavedDocument>>(emptyList())
        private set

    private val sessionDir = File(app.filesDir, "session").apply { mkdirs() }
    private val renderDir = File(app.cacheDir, "rendered").apply { mkdirs() }
    /** Full-resolution rendering is memory heavy: one page at a time. */
    private val renderGate = Semaphore(1)

    init {
        refreshDocuments()
    }

    // --- Pipeline --------------------------------------------------------------------------

    /**
     * Entry point for both camera captures and gallery imports.
     * [hint] is the live-preview quad, used if the still photo is ambiguous.
     */
    /**
     * @param deviceRotation physical phone orientation at capture (OrientationEventListener
     *   degrees snapped to 0/90/180/270); used to turn book pages upright.
     */
    fun addPhoto(photo: Bitmap, hint: Quad?, frame: Quad? = null, deviceRotation: Int = 0) {
        if (!captureAllowed) {
            photo.recycle()
            _messages.tryEmit(
                if (pageLimitReached) "Page limit reached (${options.pageLimit}) - open the scan to review it"
                else "ID card complete - open the scan to review it"
            )
            return
        }
        processingCaptures++
        val mode = mode
        val smart = aiAssist
        viewModelScope.launch {
            try {
                val isId = mode == ScanMode.ID_CARD
                val front = idFront
                val idGroup = if (isId) front?.idGroup ?: java.util.UUID.randomUUID().toString() else null
                val idSide = if (isId) (if (front == null) 0 else 1) else -1
                val created = withContext(Dispatchers.Default) { createPages(photo, hint, mode, frame, idGroup, idSide, deviceRotation, bookSwap) }
                if (isId && created.size == 1) {
                    // Front and back stay separate pages (each can be cropped/edited); they are
                    // placed together on one A4 page only when the PDF is created.
                    pages.add(created[0])
                    launchRender(created[0])
                    if (front == null) {
                        idFront = created[0]
                        idStep = IdStep.BACK
                        _messages.tryEmit("Front captured - now flip the card and scan the BACK side")
                    } else {
                        idFront = null
                        idStep = IdStep.DONE
                        _messages.tryEmit("ID card complete - both sides will be placed on one PDF page")
                    }
                    return@launch
                }
                // Respect the caller's page limit (a book spread may bring two pages at once).
                val room = if (options.pageLimit > 0) (options.pageLimit - pages.size).coerceAtLeast(0) else created.size
                val kept = created.take(room)
                created.drop(room).forEach { deletePage(it) }
                pages.addAll(kept)
                kept.forEach { p -> launchRender(p, pickSmartFilter = smart) }
                // AI Text: read the new page and open it in the AI Text screen.
                if (mode.extractText) kept.firstOrNull()?.let { openAiText(it) }
            } catch (r: Rejected) {
                _messages.tryEmit(r.message ?: "Capture rejected")
            } catch (t: Throwable) {
                Log.e(TAG, "capture processing failed", t)
            } finally {
                processingCaptures--
            }
        }
    }

    fun importFromGallery(uris: List<Uri>) {
        importing = true
        viewModelScope.launch {
            try {
                for (uri in uris) {
                    val bmp = withContext(Dispatchers.IO) {
                        runCatching { Images.decodeUri(getApplication(), uri, MAX_PHOTO_SIDE) }.getOrNull()
                    } ?: continue
                    addPhoto(bmp, null)
                }
            } finally {
                importing = false
            }
        }
    }

    /** Thrown when a capture is rejected (duplicate / nothing to scan); message is shown to the user. */
    private class Rejected(message: String) : Exception(message)

    private fun createPages(
        photo: Bitmap, hint: Quad?, mode: ScanMode, frame: Quad?,
        idGroup: String? = null, idSide: Int = -1, deviceRotation: Int = 0, swap: Boolean = false,
    ): List<Page> {
        val photoW = photo.width
        val photoH = photo.height
        val file = File(sessionDir, "${System.currentTimeMillis()}_${(0..9999).random()}.jpg")
        Images.saveJpeg(photo, file, 95)
        val gray = Images.toGrayMat(photo)
        try {
            var sidesFound = 4
            val quad = if (frame != null) {
                // Guided frame: edges are searched only near the frame sides on the full-res photo,
                // then line-fitted; undetectable sides fall back to the frame + 1%.
                val snap = DocumentDetector.snapToQuad(gray, frame)
                sidesFound = snap.sidesFound
                val snapped = DocumentDetector.refine(gray, snap.quad)
                if (snap.sidesFound >= 4) {
                    snapped
                } else {
                    // Some sides missing: let every detector compete (scored on real edge support)
                    // and take a better outline if it still matches the frame.
                    val alt = DocumentDetector.autoDetect(gray, null, listOf(snapped, frame))
                    if (alt != null && alt.distanceTo(frame) < 0.15f &&
                        DocumentDetector.scoreQuad(gray, alt) > DocumentDetector.scoreQuad(gray, snapped) + 0.05
                    ) alt else snapped
                }
            } else {
                // Free mode: the learned edge model, the classic detector (two scales) and the live
                // outline all compete; each is refined on the full photo and ranked by how well its
                // four sides lie on real edges - the best one wins (not simply the first found).
                val prob = com.rskusum.scanner.vision.EdgeModel.get(getApplication())?.let { model ->
                    val rgb = Images.toRgbMat(photo)
                    model.run(rgb).also { rgb.release() }
                }
                val best = try {
                    DocumentDetector.autoDetect(gray, prob, listOfNotNull(hint))
                } finally {
                    prob?.release()
                }
                when {
                    best != null -> best
                    else -> {
                        val detected = DocumentDetector.detect(gray, 640, prev = hint) ?: DocumentDetector.detect(gray, 1000, prev = hint)
                        when {
                            detected != null -> DocumentDetector.refine(gray, detected)
                            hint != null -> DocumentDetector.refine(gray, hint)
                            else -> { sidesFound = 0; Quad.FULL }
                        }
                    }
                }
            }

            // Duplicate / empty-surface check against the most recent page.
            val fp = DocumentDetector.fingerprint(gray, quad)
            val blank = DocumentDetector.isBlank(fp)
            // In frame mode a real document shows at least two of its edges near the frame.
            val noDocument = if (frame != null) sidesFound == 0 || (sidesFound <= 1 && blank) else sidesFound == 0 && blank
            if (noDocument) throw Rejected("No document found - place a document inside the frame")
            // Duplicate check against every page already scanned in this session.
            // Same page as one already in this session? Layout + feature matching: works whatever the
            // crop, tilt, lighting, or if the page was turned sideways / upside down.
            val sig = if (blank) null else com.rskusum.scanner.vision.PageMatcher.signature(gray, quad)
            val duplicate = if (sig == null) {
                sessionFingerprints.any { DocumentDetector.fingerprintSimilarity(fp, it) >= DUPLICATE_SIMILARITY }
            } else {
                sessionSignatures.any { com.rskusum.scanner.vision.PageMatcher.isSamePage(sig, it) }
            }
            if (duplicate) {
                throw Rejected("This page is already scanned - place another document")
            }

            if (mode.splitBook) {
                // Open book: two independent A4 pages, each with its own crop outline so they can be
                // cropped/edited separately. With the phone held across the book (spread taller than
                // wide in the photo) each half is turned upright using the phone's orientation.
                // Cut at the real spine (gutter shadow / blank band), not just the geometric middle.
                val spine = DocumentDetector.findSpine(gray, quad)
                val (a, b) = DocumentDetector.splitSpread(quad, photoW, photoH, spine)
                val (sw, sh) = DocumentDetector.naiveSize(quad, photoW, photoH)
                val acrossBook = sh > sw
                // Page 1 = top (or left) half as shown on screen, unless the user swapped the order.
                val first = if (swap) b else a
                val second = if (swap) a else b
                // Phone held across the book: halves are sideways - turn them upright. Default is
                // the phone's top pointing left (matching the sideways page labels).
                val turn = if (!acrossBook) 0 else if (deviceRotation == 90) 90 else 270
                // Detach the pages: each gets its OWN upright image cut at the spine, so crop,
                // filters and eraser only ever see that one page.
                val pagesOut = listOf(first, second).mapIndexed { i, q ->
                    val (pageFile, pageQuad) = detachBookPage(photo, q, tall = acrossBook, firstHalf = q === a, turn = turn, index = i)
                    Page(pageFile, pageQuad, mode.defaultFilter, mode.forcedAspect, fingerprint = fp).also { it.signature = sig; autoUpright(it) }
                }
                file.delete() // the full spread photo is no longer needed
                return pagesOut
            }
            return listOf(
                Page(
                    file, quad, mode.defaultFilter, mode.forcedAspect, forcedOrientation = mode.pageOrientation,
                    frame = frame, fingerprint = fp, idGroup = idGroup, idSide = idSide,
                ).also { it.signature = sig; autoUpright(it) }
            )
        } catch (r: Rejected) {
            file.delete()
            throw r
        } finally {
            gray.release()
            photo.recycle()
        }
    }

    /**
     * Reads the text direction of the flattened page with the on-device orientation model and
     * turns the page upright (upside-down or sideways shots), before the first render, so the
     * thumbnail and every edit screen already show it the right way up. Modes with a fixed page
     * orientation (ID card, whiteboard) only get the 180 degree fix.
     */
    private fun autoUpright(page: Page) {
        try {
            val model = com.rskusum.scanner.vision.OrientationModel.get(getApplication()) ?: return
            val bmp = Images.decodeFile(page.originalFile, 1600) ?: return
            val rgb = Images.toRgbMat(bmp)
            bmp.recycle()
            val flat = DocumentDetector.warp(rgb, page.quad, page.forcedAspect, maxSide = 1200, forcedOrientation = page.forcedOrientation)
            rgb.release()
            val turn = model.uprightRotation(flat)
            flat.release()
            val fixedOrientation = page.forcedOrientation != DocumentDetector.ORIENT_AUTO
            if (turn == 180 || (!fixedOrientation && turn != 0)) page.rotation = turn
        } catch (t: Throwable) {
            Log.e(TAG, "auto orientation failed", t)
        }
    }

    /**
     * Cuts one page of an open book out of the spread photo: the page's bounding box plus a small
     * margin on the outer sides, but nothing beyond the spine, turned upright by [turn] degrees
     * (clockwise). Returns the new image file and the page outline in its coordinates.
     */
    private fun detachBookPage(photo: Bitmap, q: Quad, tall: Boolean, firstHalf: Boolean, turn: Int, index: Int): Pair<File, Quad> {
        val w = photo.width.toFloat(); val h = photo.height.toFloat()
        val xs = q.points.map { it.x * w }; val ys = q.points.map { it.y * h }
        val mx = 0.03f * w; val my = 0.03f * h
        var l = xs.min() - mx; var r = xs.max() + mx; var t = ys.min() - my; var b = ys.max() + my
        // Spine side: cut exactly at the spine (no sliver of the other page).
        when {
            tall && firstHalf -> b = maxOf(q.bl.y, q.br.y) * h   // top page: spine is its bottom edge
            tall -> t = minOf(q.tl.y, q.tr.y) * h                // bottom page: spine is its top edge
            firstHalf -> r = maxOf(q.tr.x, q.br.x) * w           // left page: spine is its right edge
            else -> l = minOf(q.tl.x, q.bl.x) * w               // right page: spine is its left edge
        }
        val x0 = l.coerceIn(0f, w - 2).toInt(); val y0 = t.coerceIn(0f, h - 2).toInt()
        val x1 = r.coerceIn(x0 + 1f, w).toInt(); val y1 = b.coerceIn(y0 + 1f, h).toInt()
        var crop = Bitmap.createBitmap(photo, x0, y0, x1 - x0, y1 - y0)
        if (crop === photo) crop = photo.copy(Bitmap.Config.ARGB_8888, false) // never recycle the source
        val cw = (x1 - x0).toFloat(); val ch = (y1 - y0).toFloat()
        var pts = q.points.map { com.rskusum.scanner.vision.NPoint(((it.x * w - x0) / cw).coerceIn(0f, 1f), ((it.y * h - y0) / ch).coerceIn(0f, 1f)) }
        if (turn % 360 != 0) {
            crop = Images.rotate(crop, turn)
            pts = pts.map { p ->
                if (turn == 90) com.rskusum.scanner.vision.NPoint(1f - p.y, p.x) // clockwise
                else com.rskusum.scanner.vision.NPoint(p.y, 1f - p.x)          // 270 = counter-clockwise
            }
        }
        val file = File(sessionDir, "book_${System.currentTimeMillis()}_$index.jpg")
        Images.saveJpeg(crop, file, 95)
        crop.recycle()
        return file to orderQuad(pts)
    }

    /** Orders 4 points clockwise from the one nearest the top-left corner. */
    private fun orderQuad(p: List<com.rskusum.scanner.vision.NPoint>): Quad {
        val cx = p.map { it.x }.average(); val cy = p.map { it.y }.average()
        val sorted = p.sortedBy { kotlin.math.atan2(it.y - cy, it.x - cx) }
        val start = sorted.indices.minBy { sorted[it].x + sorted[it].y }
        val o = List(4) { sorted[(start + it) % 4] }
        return Quad(o[0], o[1], o[2], o[3])
    }

    /**
     * PDF page for an ID card: the edited (rendered) front and back placed on one A4 page at
     * 300 dpi, front above back, each 10% larger than the real 85.6 x 54 mm card.
     */
    private fun composeIdPage(sides: List<Page>): File? {
        val a4w = 2480; val a4h = 3508
        val cardW = (1011 * 1.1).toInt(); val cardH = (638 * 1.1).toInt()
        val gap = 260
        val canvas = Mat(a4h, a4w, org.opencv.core.CvType.CV_8UC3, org.opencv.core.Scalar(255.0, 255.0, 255.0))
        var top = (a4h - (cardH * 2 + gap)) / 3 // a little above centre, like a photocopy
        var placed = 0
        for (page in sides.sortedBy { it.idSide }) {
            val f = page.processedFile ?: continue
            val bmp = Images.decodeFile(f, MAX_PHOTO_SIDE) ?: continue
            var card = Images.toRgbMat(bmp)
            bmp.recycle()
            if (card.rows() > card.cols()) { val r = Mat(); Core.rotate(card, r, Core.ROTATE_90_COUNTERCLOCKWISE); card.release(); card = r }
            val sized = Mat()
            Imgproc.resize(card, sized, org.opencv.core.Size(cardW.toDouble(), cardH.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            card.release()
            sized.copyTo(canvas.submat(Rect((a4w - cardW) / 2, top, cardW, cardH)))
            sized.release()
            top += cardH + gap
            placed++
        }
        if (placed == 0) { canvas.release(); return null }
        val file = File(renderDir, "idpage_${System.nanoTime()}.jpg")
        val out = Images.toBitmap(canvas)
        canvas.release()
        Images.saveJpeg(out, file, 95)
        out.recycle()
        return file
    }

    /** Page images for the PDF, in order; ID card sides are merged onto one page. */
    private fun pdfPageFiles(): List<File> {
        val out = ArrayList<File>()
        val doneGroups = HashSet<String>()
        for (p in pages) {
            val g = p.idGroup
            if (g == null) { p.processedFile?.let { out += it }; continue }
            if (!doneGroups.add(g)) continue
            composeIdPage(pages.filter { it.idGroup == g })?.let { out += it }
        }
        return out
    }

    private fun launchRender(page: Page, pickSmartFilter: Boolean = false) {
        page.rendering = true
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.Default) {
                    renderGate.withPermit { render(page, pickSmartFilter) }
                }
                if (result != null) {
                    val (file, thumb, pick) = result
                    page.processedFile?.takeIf { it != file }?.delete()
                    page.filter = pick.filter
                    page.removeShadow = pick.removeShadow
                    if (pickSmartFilter) {
                        page.smartLabel = pick.label
                        _messages.tryEmit("Smart filter: ${pick.label}")
                    }
                    page.processedFile = file
                    page.thumbnail = thumb
                    page.version++
                }
            } catch (t: Throwable) {
                Log.e(TAG, "render failed", t)
            } finally {
                page.rendering = false
            }
        }
    }

    /** Warps + enhances the original. Returns (file, thumbnail, filter + shadow setting used). */
    private fun render(page: Page, pickSmartFilter: Boolean): Triple<File, ImageBitmap, com.rskusum.scanner.vision.SmartPick>? {
        val bmp = Images.decodeFile(page.originalFile, MAX_PHOTO_SIDE) ?: return null
        val rgb = Images.toRgbMat(bmp)
        bmp.recycle()
        var flat = DocumentDetector.warp(rgb, page.quad, page.forcedAspect, forcedOrientation = page.forcedOrientation)
        rgb.release()
        if (page.bookHalf >= 0) {
            val half = flat.cols() / 2
            val roi = if (page.bookHalf == 0) Rect(0, 0, half, flat.rows()) else Rect(half, 0, flat.cols() - half, flat.rows())
            val sub = Mat(flat, roi).clone()
            flat.release()
            flat = sub
        }
        rotate(flat, page.rotation)?.let { flat.release(); flat = it }
        val pick = if (pickSmartFilter) SmartFilter.pick(flat) else com.rskusum.scanner.vision.SmartPick(page.filter, page.removeShadow)
        val enhanced = ImageEnhancer.apply(flat, pick.filter, pick.removeShadow)
        flat.release()
        com.rskusum.scanner.vision.Eraser.apply(enhanced, page.erasures.toList())
        val out = Images.toBitmap(enhanced)
        enhanced.release()
        val file = File(renderDir, "${page.id}_${System.nanoTime()}.jpg")
        Images.saveJpeg(out, file, 95)
        val thumb = Images.scaleDown(out, 360).asImageBitmap()
        return Triple(file, thumb, pick)
    }

    /** Small previews of every filter for the filter strip. */
    suspend fun filterPreviews(page: Page): Map<ScanFilter, ImageBitmap> = withContext(Dispatchers.Default) {
        val bmp = Images.decodeFile(page.originalFile, 900) ?: return@withContext emptyMap()
        val rgb = Images.toRgbMat(bmp)
        bmp.recycle()
        var flat = DocumentDetector.warp(rgb, page.quad, page.forcedAspect, maxSide = 320, forcedOrientation = page.forcedOrientation)
        rgb.release()
        if (page.bookHalf >= 0) {
            val half = flat.cols() / 2
            val roi = if (page.bookHalf == 0) Rect(0, 0, half, flat.rows()) else Rect(half, 0, flat.cols() - half, flat.rows())
            val sub = Mat(flat, roi).clone(); flat.release(); flat = sub
        }
        rotate(flat, page.rotation)?.let { flat.release(); flat = it }
        val map = ScanFilter.choices.associateWith { f ->
            val m = ImageEnhancer.apply(flat, f, page.removeShadow)
            val b = Images.toBitmap(m).asImageBitmap()
            m.release()
            b
        }
        flat.release()
        map
    }

    private fun rotate(m: Mat, degrees: Int): Mat? {
        val code = when (((degrees % 360) + 360) % 360) {
            90 -> Core.ROTATE_90_CLOCKWISE
            180 -> Core.ROTATE_180
            270 -> Core.ROTATE_90_COUNTERCLOCKWISE
            else -> return null
        }
        return Mat().also { Core.rotate(m, it, code) }
    }

    // --- AI Text (on-device OCR) --------------------------------------------------------------

    /** Pages to show in the AI Text screen (the flow navigates when one arrives). */
    private val _aiRequests = kotlinx.coroutines.flow.MutableSharedFlow<Page>(extraBufferCapacity = 4)
    val aiRequests: kotlinx.coroutines.flow.SharedFlow<Page> = _aiRequests

    /** True while the OCR language model is being downloaded (first use only). */
    var ocrDownloading by mutableStateOf(false)
        private set

    /** Reads [page] (if not read yet) and shows it in the AI Text screen. */
    fun openAiText(page: Page) {
        extractText(page)
        _aiRequests.tryEmit(page)
    }

    /**
     * Runs OCR on the finished page (waits for its render), then works out the document type and
     * key details. Results land in [Page.ocrText] / [Page.insights].
     */
    fun extractText(page: Page, force: Boolean = false) {
        if (page.ocrBusy || (!force && page.ocrText != null)) return
        page.ocrBusy = true
        page.ocrError = null
        viewModelScope.launch {
            try {
                // Wait for the flattened, cleaned page.
                androidx.compose.runtime.snapshotFlow { page.rendering }.first { !it }
                val file = page.processedFile ?: error("The page could not be prepared")
                val langs = options.ocrLanguages
                if (!com.rskusum.scanner.ocr.RsOcr.isReady(getApplication(), langs)) {
                    ocrDownloading = true
                    com.rskusum.scanner.ocr.RsOcr.prepare(getApplication(), langs)
                    ocrDownloading = false
                }
                val result = com.rskusum.scanner.ocr.RsOcr.recognize(getApplication(), Uri.fromFile(file), langs)
                page.ocrText = result.text
                page.ocrConfidence = result.confidence
                page.insights = withContext(Dispatchers.Default) { com.rskusum.scanner.ocr.TextInsights.analyze(result.text) }
            } catch (t: Throwable) {
                Log.e(TAG, "OCR failed", t)
                page.ocrError = if (ocrDownloading) {
                    "Couldn't download the text model - check the internet connection and try again"
                } else {
                    t.message ?: "Text recognition failed"
                }
            } finally {
                ocrDownloading = false
                page.ocrBusy = false
            }
        }
    }

    /** The user edited the recognised text. */
    fun setOcrText(page: Page, text: String) {
        page.ocrText = text
        page.insights = com.rskusum.scanner.ocr.TextInsights.analyze(text)
    }

    // --- Page editing ----------------------------------------------------------------------

    @Suppress("DEPRECATION")
    fun setFilter(page: Page, filter: ScanFilter) {
        // The old "No shadow" filter = Auto colour with shadow removal switched on.
        if (filter == ScanFilter.NO_SHADOW) {
            setFilter(page, ScanFilter.AUTO); setRemoveShadow(page, true); return
        }
        if (page.filter == filter) return
        page.filter = filter
        page.smartLabel = null
        launchRender(page)
    }

    /** Shadow removal on/off for [page]; kept together with whatever filter is selected. */
    fun setRemoveShadow(page: Page, on: Boolean) {
        if (page.removeShadow == on) return
        page.removeShadow = on
        page.smartLabel = null
        launchRender(page)
    }

    /** Same filter (and shadow-removal setting) on every page of the scan. */
    fun applyFilterToAll(filter: ScanFilter, removeShadow: Boolean? = null) {
        pages.forEach { p ->
            val changed = p.filter != filter || (removeShadow != null && p.removeShadow != removeShadow)
            p.filter = filter
            if (removeShadow != null) p.removeShadow = removeShadow
            if (changed) { p.smartLabel = null; launchRender(p) }
        }
    }

    fun rotate(page: Page) {
        page.rotation = (page.rotation + 90) % 360
        // Eraser strokes live on the finished page: turn them with it.
        val turned = page.erasures.map { it.rotatedCw() }
        page.erasures.clear(); page.erasures.addAll(turned)
        launchRender(page)
    }

    fun setQuad(page: Page, quad: Quad) {
        if (quad != page.quad) page.erasures.clear() // strokes no longer line up with a new crop
        page.quad = quad
        launchRender(page)
    }

    /**
     * Eraser screen base image: the finished page (crop, rotation, filter) at preview size,
     * WITHOUT any eraser strokes, so every stroke can be re-applied live on top of it.
     */
    suspend fun eraserBase(page: Page, maxSide: Int = 1600): Bitmap? = withContext(Dispatchers.Default) {
        val bmp = Images.decodeFile(page.originalFile, maxSide * 2) ?: return@withContext null
        val rgb = Images.toRgbMat(bmp)
        bmp.recycle()
        var flat = DocumentDetector.warp(rgb, page.quad, page.forcedAspect, maxSide = maxSide, forcedOrientation = page.forcedOrientation)
        rgb.release()
        rotate(flat, page.rotation)?.let { flat.release(); flat = it }
        val enhanced = ImageEnhancer.apply(flat, page.filter, page.removeShadow)
        flat.release()
        Images.toBitmap(enhanced).also { enhanced.release() }
    }

    /** Live eraser preview: [strokes] applied to a copy of [base] (same algorithm as the final render). */
    suspend fun eraserPreview(base: Bitmap, strokes: List<com.rskusum.scanner.vision.EraseStroke>): Bitmap =
        withContext(Dispatchers.Default) {
            val rgb = Images.toRgbMat(base)
            com.rskusum.scanner.vision.Eraser.apply(rgb, strokes)
            Images.toBitmap(rgb).also { rgb.release() }
        }

    /** Eraser screen "Done": store the strokes and re-render the page. */
    fun setErasures(page: Page, strokes: List<com.rskusum.scanner.vision.EraseStroke>) {
        page.erasures.clear()
        page.erasures.addAll(strokes)
        launchRender(page)
    }

    /**
     * Crop screen "Auto detect": multi-candidate search on the original photo. Priors are the
     * user's current outline, the guide frame used at capture and the stored crop; plus the
     * learned edge model and classic detector. Best candidate by edge evidence wins.
     */
    suspend fun autoDetect(page: Page, current: Quad?): Quad? = withContext(Dispatchers.Default) {
        val bmp = Images.decodeFile(page.originalFile, 2000) ?: return@withContext null
        val gray = Images.toGrayMat(bmp)
        val edgeMap = com.rskusum.scanner.vision.EdgeModel.get(getApplication())?.let { model ->
            val rgb = Images.toRgbMat(bmp)
            model.run(rgb).also { rgb.release() }
        }
        bmp.recycle()
        try {
            val priors = listOfNotNull(current, page.frame, page.quad).distinct()
            DocumentDetector.autoDetect(gray, edgeMap, priors)
        } finally {
            gray.release()
            edgeMap?.release()
        }
    }

    fun deletePage(page: Page) {
        pages.remove(page)
        page.processedFile?.delete()
        if (pages.none { it.originalFile == page.originalFile }) page.originalFile.delete()
    }

    fun movePage(from: Int, to: Int) {
        if (from !in pages.indices || to !in pages.indices) return
        pages.add(to, pages.removeAt(from))
    }

    fun discardSession() {
        pages.toList().forEach { deletePage(it) }
        sessionDir.listFiles()?.forEach { it.delete() }
        documentName = defaultName()
        tracker.reset()
        resetIdCard()
    }

    val isRendering: Boolean get() = processingCaptures > 0 || importing || pages.any { it.rendering }

    // --- Saving ----------------------------------------------------------------------------

    /** Saves the session as a PDF in the library and in Downloads. Returns a user message. */
    var pdfQuality by mutableStateOf(com.rskusum.scanner.data.PdfQuality.BALANCED)

    suspend fun savePdf(): Result<String> = withContext(Dispatchers.IO) {
        if (!options.standalone) return@withContext finishForCaller()
        runCatching {
            val files = pdfPageFiles()
            require(files.isNotEmpty()) { "Nothing to save" }
            val pdf = store.savePdf(documentName, files, pdfQuality)
            val where = store.exportToDownloads(pdf)
            withContext(Dispatchers.Main) {
                discardSession()
                refreshDocuments()
            }
            "Saved to $where"
        }
    }

    /**
     * Embedded mode: writes the PDF and/or page JPEGs into `files/rsscanner/<scan>/` and hands a
     * [ScanResult] to the calling app through [results].
     */
    private suspend fun finishForCaller(): Result<String> = runCatching {
        val pageFiles = pages.mapNotNull { it.processedFile }
        require(pageFiles.isNotEmpty()) { "Nothing to save" }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outDir = File(getApplication<Application>().filesDir, "rsscanner/scan_$stamp").apply { mkdirs() }
        val pdfUri = if (options.returnPdf) {
            store.uriFor(store.savePdf(documentName, pdfPageFiles(), pdfQuality, into = outDir))
        } else {
            null
        }
        val jpegUris = if (options.returnJpegs) {
            pageFiles.mapIndexed { i, f ->
                val target = File(outDir, "page_${i + 1}.jpg")
                f.copyTo(target, overwrite = true)
                store.uriFor(target)
            }
        } else {
            emptyList()
        }
        val text = pages.mapNotNull { it.ocrText?.takeIf(String::isNotBlank) }.joinToString("\n\n").ifEmpty { null }
        val result = ScanResult(documentName, pdfUri, jpegUris, pageFiles.size, text)
        withContext(Dispatchers.Main) {
            discardSession()
            _results.tryEmit(result)
        }
        "Done"
    }

    suspend fun saveJpegs(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val files = pages.mapNotNull { it.processedFile }
            require(files.isNotEmpty()) { "Nothing to save" }
            "Saved ${files.size} image(s) to ${store.exportJpegs(documentName, files)}"
        }
    }

    fun sessionShareIntent() = store.shareIntent(pages.mapNotNull { it.processedFile }, "image/jpeg")

    fun refreshDocuments() {
        viewModelScope.launch { documents = withContext(Dispatchers.IO) { store.list() } }
    }

    fun deleteDocument(doc: SavedDocument) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.delete(doc) }
            refreshDocuments()
        }
    }

    fun renameDocument(doc: SavedDocument, name: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.rename(doc, name) }
            refreshDocuments()
        }
    }

    private fun defaultName() = "Scan " + SimpleDateFormat("dd MMM yyyy HH.mm", Locale.getDefault()).format(Date())

    companion object {
        const val TAG = "ScannerVM"
        const val MAX_PHOTO_SIDE = 4800
        /** Fingerprint correlation above which a capture counts as the same page (tested: same >= 0.976, different <= 0.75). */
        const val DUPLICATE_SIMILARITY = 0.88
    }
}
