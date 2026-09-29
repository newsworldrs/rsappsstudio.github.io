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
    var autoCapture by mutableStateOf(true)

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
    val captureAllowed: Boolean get() = !(mode == ScanMode.ID_CARD && idStep == IdStep.DONE)

    /** Fingerprints of all pages in this session, for live duplicate prevention in the camera. */
    val sessionFingerprints: List<FloatArray>
        get() = pages.mapNotNull { it.fingerprint } + listOfNotNull(idFront?.fingerprint)

    /** The on-screen guide frame (normalized), or null in free-detection mode. */
    val guideFrame: Quad?
        get() = when (orientation) {
            com.rskusum.scanner.data.FrameOrientation.PORTRAIT -> DocumentDetector.guideFrame(1 / mode.frameRatio)
            com.rskusum.scanner.data.FrameOrientation.LANDSCAPE -> DocumentDetector.guideFrame(mode.frameRatio)
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
            _messages.tryEmit("ID card complete - open the scan to review it")
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
                val created = withContext(Dispatchers.Default) { createPages(photo, hint, mode, frame, idGroup, idSide, deviceRotation) }
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
                pages.addAll(created)
                created.forEach { p -> launchRender(p, pickSmartFilter = smart) }
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
        idGroup: String? = null, idSide: Int = -1, deviceRotation: Int = 0,
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
                DocumentDetector.refine(gray, snap.quad)
            } else {
                // Learned edge model on the sharp still first, then the classic detector at two scales.
                val model = com.rskusum.scanner.vision.EdgeModel.get(getApplication())
                val fromModel = model?.let {
                    val rgb = Images.toRgbMat(photo)
                    val prob = it.run(rgb)
                    rgb.release()
                    DocumentDetector.detectFromEdgeMap(prob, hint).also { prob.release() }
                }
                val detected = fromModel
                    ?: DocumentDetector.detect(gray, 640, prev = hint)
                    ?: DocumentDetector.detect(gray, 1000, prev = hint)
                when {
                    detected != null -> DocumentDetector.refine(gray, detected)
                    hint != null -> DocumentDetector.refine(gray, hint)
                    else -> { sidesFound = 0; Quad.FULL }
                }
            }

            // Duplicate / empty-surface check against the most recent page.
            val fp = DocumentDetector.fingerprint(gray, quad)
            val blank = DocumentDetector.isBlank(fp)
            // In frame mode a real document shows at least two of its edges near the frame.
            val noDocument = if (frame != null) sidesFound == 0 || (sidesFound <= 1 && blank) else sidesFound == 0 && blank
            if (noDocument) throw Rejected("No document found - place a document inside the frame")
            // Duplicate check against every page already scanned in this session.
            if (sessionFingerprints.any { DocumentDetector.fingerprintSimilarity(fp, it) >= DUPLICATE_SIMILARITY }) {
                throw Rejected("This page is already scanned - place another document")
            }

            if (mode.splitBook) {
                // Open book: two independent A4 pages, each with its own crop outline so they can be
                // cropped/edited separately. With the phone held across the book (spread taller than
                // wide in the photo) each half is turned upright using the phone's orientation.
                val (a, b) = DocumentDetector.splitSpread(quad, photoW, photoH)
                val (sw, sh) = DocumentDetector.naiveSize(quad, photoW, photoH)
                val acrossBook = sh > sw
                val topIsRight = deviceRotation == 90 // phone top pointing to the book's right side
                val first = if (acrossBook && topIsRight) b else a
                val second = if (first === a) b else a
                val turn = if (!acrossBook) 0 else if (topIsRight) 90 else 270
                return listOf(first, second).map { q ->
                    Page(file, q, mode.defaultFilter, mode.forcedAspect, fingerprint = fp).apply { rotation = turn }
                }
            }
            return listOf(
                Page(
                    file, quad, mode.defaultFilter, mode.forcedAspect, forcedOrientation = mode.pageOrientation,
                    frame = frame, fingerprint = fp, idGroup = idGroup, idSide = idSide,
                )
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
                    val (file, thumb, filter) = result
                    page.processedFile?.takeIf { it != file }?.delete()
                    page.filter = filter
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

    /** Warps + enhances the original. Returns (file, thumbnail, filter used). */
    private fun render(page: Page, pickSmartFilter: Boolean): Triple<File, ImageBitmap, ScanFilter>? {
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
        val filter = if (pickSmartFilter) SmartFilter.choose(flat) else page.filter
        val enhanced = ImageEnhancer.apply(flat, filter)
        flat.release()
        com.rskusum.scanner.vision.Eraser.apply(enhanced, page.erasures.toList())
        val out = Images.toBitmap(enhanced)
        enhanced.release()
        val file = File(renderDir, "${page.id}_${System.nanoTime()}.jpg")
        Images.saveJpeg(out, file, 95)
        val thumb = Images.scaleDown(out, 360).asImageBitmap()
        return Triple(file, thumb, filter)
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
        val map = ScanFilter.entries.associateWith { f ->
            val m = ImageEnhancer.apply(flat, f)
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

    // --- Page editing ----------------------------------------------------------------------

    fun setFilter(page: Page, filter: ScanFilter) {
        if (page.filter == filter) return
        page.filter = filter
        launchRender(page)
    }

    fun applyFilterToAll(filter: ScanFilter) {
        pages.forEach { setFilter(it, filter) }
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
        val enhanced = ImageEnhancer.apply(flat, page.filter)
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
