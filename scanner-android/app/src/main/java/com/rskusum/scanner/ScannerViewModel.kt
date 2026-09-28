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

    // --- Capture settings ------------------------------------------------------------------
    var mode by mutableStateOf(ScanMode.DOCUMENT)
    var aiAssist by mutableStateOf(false)
    var autoCapture by mutableStateOf(true)

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
    fun addPhoto(photo: Bitmap, hint: Quad?) {
        processingCaptures++
        val mode = mode
        val smart = aiAssist
        viewModelScope.launch {
            try {
                val created = withContext(Dispatchers.Default) { createPages(photo, hint, mode) }
                pages.addAll(created)
                created.forEach { p -> launchRender(p, pickSmartFilter = smart) }
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

    private fun createPages(photo: Bitmap, hint: Quad?, mode: ScanMode): List<Page> {
        val file = File(sessionDir, "${System.currentTimeMillis()}_${(0..9999).random()}.jpg")
        Images.saveJpeg(photo, file, 95)
        val gray = Images.toGrayMat(photo)
        val quad = try {
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
                else -> Quad.FULL
            }
        } finally {
            gray.release()
            photo.recycle()
        }
        return if (mode.splitBook) {
            listOf(
                Page(file, quad, mode.defaultFilter, mode.forcedAspect, bookHalf = 0),
                Page(file, quad, mode.defaultFilter, mode.forcedAspect, bookHalf = 1),
            )
        } else {
            listOf(Page(file, quad, mode.defaultFilter, mode.forcedAspect))
        }
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
        var flat = DocumentDetector.warp(rgb, page.quad, page.forcedAspect)
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
        val out = Images.toBitmap(enhanced)
        enhanced.release()
        val file = File(renderDir, "${page.id}_${System.nanoTime()}.jpg")
        Images.saveJpeg(out, file, 92)
        val thumb = Images.scaleDown(out, 360).asImageBitmap()
        return Triple(file, thumb, filter)
    }

    /** Small previews of every filter for the filter strip. */
    suspend fun filterPreviews(page: Page): Map<ScanFilter, ImageBitmap> = withContext(Dispatchers.Default) {
        val bmp = Images.decodeFile(page.originalFile, 900) ?: return@withContext emptyMap()
        val rgb = Images.toRgbMat(bmp)
        bmp.recycle()
        var flat = DocumentDetector.warp(rgb, page.quad, page.forcedAspect, maxSide = 320)
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
        launchRender(page)
    }

    fun setQuad(page: Page, quad: Quad) {
        page.quad = quad
        launchRender(page)
    }

    /** Re-runs detection on the original photo (Crop screen "Auto" button). */
    suspend fun autoDetect(page: Page): Quad? = withContext(Dispatchers.Default) {
        val bmp = Images.decodeFile(page.originalFile, 1600) ?: return@withContext null
        val gray = Images.toGrayMat(bmp)
        bmp.recycle()
        val q = DocumentDetector.detect(gray, 640)?.let { DocumentDetector.refine(gray, it) }
        gray.release()
        q
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
    }

    val isRendering: Boolean get() = processingCaptures > 0 || importing || pages.any { it.rendering }

    // --- Saving ----------------------------------------------------------------------------

    /** Saves the session as a PDF in the library and in Downloads. Returns a user message. */
    suspend fun savePdf(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val files = pages.mapNotNull { it.processedFile }
            require(files.isNotEmpty()) { "Nothing to save" }
            val pdf = store.savePdf(documentName, files)
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
        const val MAX_PHOTO_SIDE = 4200
    }
}
