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
    /** -1 whole page, 0 left half of a book spread, 1 right half. */
    val bookHalf: Int = -1,
    val id: String = UUID.randomUUID().toString(),
) {
    var quad by mutableStateOf(quad)
    var filter by mutableStateOf(filter)
    var rotation by mutableIntStateOf(0)
    var processedFile by mutableStateOf<File?>(null)
    var thumbnail by mutableStateOf<ImageBitmap?>(null)
    var rendering by mutableStateOf(true)
    /** Bumped every time the rendered output changes, to invalidate cached images in the UI. */
    var version by mutableIntStateOf(0)
}
