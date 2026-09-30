package com.rskusum.scanner.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import com.rskusum.scanner.vision.Quad
import com.rskusum.scanner.vision.ScanFilter
import java.io.File
import java.util.UUID

/**
 * One scanned page. The untouched upright photo is kept so crop / rotation / filter can be
 * changed at any time without quality loss; [processedFile] is the rendered result.
 */
class Page(
    val originalFile: File,
    quad: Quad,
    filter: ScanFilter,
    val forcedAspect: Double?,
    /** Orientation forced together with [forcedAspect] (DocumentDetector.ORIENT_*). */
    val forcedOrientation: Int = 0,
    /** -1 whole page, 0 left half of a book spread, 1 right half. */
    val bookHalf: Int = -1,
    /** Guide frame used at capture (normalized), if any: a prior for Auto detect. */
    val frame: Quad? = null,
    /** Content fingerprint of the page at capture, for duplicate detection. */
    val fingerprint: FloatArray? = null,
    /** ID card sides share a group id; they are edited separately and combined on one PDF page. */
    val idGroup: String? = null,
    /** 0 = front, 1 = back (only for ID card pages). */
    val idSide: Int = -1,
    val id: String = UUID.randomUUID().toString(),
) {
    var quad by mutableStateOf(quad)
    var filter by mutableStateOf(filter)
    /** Shadow / uneven-lighting removal, applied before [filter] (works with every filter). */
    var removeShadow by mutableStateOf(false)
    /** What the Smart filter chose for this page (null = picked by the user). */
    var smartLabel by mutableStateOf<String?>(null)
    var rotation by mutableIntStateOf(0)
    /** Layout + feature signature of the page at capture, for "same page again?" checks. */
    var signature: com.rskusum.scanner.vision.PageSignature? = null
    var processedFile by mutableStateOf<File?>(null)
    var thumbnail by mutableStateOf<ImageBitmap?>(null)
    var rendering by mutableStateOf(true)
    /** Bumped every time the rendered output changes, to invalidate cached images in the UI. */
    var version by mutableIntStateOf(0)
    /** Eraser strokes, in the coordinates of the finished (rotated) page. */
    val erasures = androidx.compose.runtime.mutableStateListOf<com.rskusum.scanner.vision.EraseStroke>()

    // --- AI Text (OCR) ---
    /** Recognised text of the finished page (null = not read yet). Editable by the user. */
    var ocrText by mutableStateOf<String?>(null)
    /** Tesseract's mean confidence 0-100 for [ocrText]. */
    var ocrConfidence by mutableIntStateOf(0)
    /** What the text looks like: document type and key details (links, phones, dates, ...). */
    var insights by mutableStateOf<com.rskusum.scanner.ocr.DocInsights?>(null)
    var ocrBusy by mutableStateOf(false)
    var ocrError by mutableStateOf<String?>(null)
}
