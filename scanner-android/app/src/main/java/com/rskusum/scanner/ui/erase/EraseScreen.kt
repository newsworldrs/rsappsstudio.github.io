package com.rskusum.scanner.ui.erase

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rskusum.scanner.ScannerViewModel
import com.rskusum.scanner.data.Images
import com.rskusum.scanner.data.Page
import com.rskusum.scanner.ui.ScanColors
import com.rskusum.scanner.vision.EraseStroke
import com.rskusum.scanner.vision.NPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Eraser: paint over the finished page.
 *  - "Marks only": pen / pencil / highlighter marks and stains disappear, printed text under
 *    them is kept.
 *  - "Everything": the painted area becomes clean paper (text included).
 * Strokes are stored on the page and applied at render time, so they are non-destructive
 * (undo / clear any time).
 */
@Composable
fun EraseScreen(vm: ScannerViewModel, page: Page?, onDone: () -> Unit) {
    if (page == null) {
        LaunchedEffect(Unit) { onDone() }
        return
    }
    // The rendered page; previous strokes are drawn on top as a translucent overlay for reference.
    val image by produceState<ImageBitmap?>(null, page.version) {
        val f = page.processedFile
        value = if (f == null) null else withContext(Dispatchers.IO) { Images.decodeFile(f, 2000)?.asImageBitmap() }
    }
    val strokes = remember(page.id) { mutableStateListOf<EraseStroke>().apply { addAll(page.erasures) } }
    var keepText by remember { mutableStateOf(true) }
    var brush by remember { mutableFloatStateOf(0.03f) }
    val live = remember { mutableStateListOf<NPoint>() }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Box(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 8.dp)) {
            IconButton(onClick = onDone, modifier = Modifier.align(Alignment.CenterStart)) {
                Icon(Icons.Filled.Close, "Cancel", tint = Color.White)
            }
            Text("Eraser", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.Center))
            Row(Modifier.align(Alignment.CenterEnd)) {
                IconButton(onClick = { if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex) }, enabled = strokes.isNotEmpty()) {
                    Icon(Icons.AutoMirrored.Filled.Undo, "Undo", tint = if (strokes.isNotEmpty()) Color.White else Color.Gray)
                }
                IconButton(onClick = { vm.setErasures(page, strokes.toList()); onDone() }) {
                    Icon(Icons.Filled.Check, "Apply", tint = ScanColors.Accent, modifier = Modifier.size(30.dp))
                }
            }
        }

        BoxWithConstraints(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            val img = image
            if (img == null) {
                CircularProgressIndicator(color = ScanColors.Accent)
            } else {
                val cw = constraints.maxWidth.toFloat()
                val ch = constraints.maxHeight.toFloat()
                val s = min(cw / img.width, ch / img.height)
                val dw = img.width * s
                val dh = img.height * s
                val ox = (cw - dw) / 2
                val oy = (ch - dh) / 2
                fun norm(o: Offset) = NPoint(((o.x - ox) / dw).coerceIn(0f, 1f), ((o.y - oy) / dh).coerceIn(0f, 1f))
                Canvas(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(img, keepText, brush) {
                            detectDragGestures(
                                onDragStart = { live.clear(); live.add(norm(it)) },
                                onDragEnd = {
                                    if (live.isNotEmpty()) strokes.add(EraseStroke(live.toList(), brush, keepText))
                                    live.clear()
                                },
                                onDragCancel = { live.clear() },
                            ) { change, _ ->
                                change.consume()
                                live.add(norm(change.position))
                            }
                        }
                ) {
                    drawImage(img, dstOffset = IntOffset(ox.roundToInt(), oy.roundToInt()), dstSize = IntSize(dw.roundToInt(), dh.roundToInt()))
                    fun drawStroke(pts: List<NPoint>, radius: Float, keep: Boolean) {
                        val color = if (keep) Color(0x6600A2FF) else Color(0x66FF3B30)
                        val wpx = radius * dw * 2
                        val o = pts.map { Offset(ox + it.x * dw, oy + it.y * dh) }
                        o.forEach { drawCircle(color, wpx / 2, it) }
                        for (i in 1 until o.size) drawLine(color, o[i - 1], o[i], wpx, StrokeCap.Round)
                    }
                    strokes.forEach { drawStroke(it.points, it.radius, it.keepText) }
                    if (live.isNotEmpty()) drawStroke(live.toList(), brush, keepText)
                }
            }
        }

        // Mode + brush size
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ModeChip("Marks only (keep text)", keepText, Modifier.weight(1f)) { keepText = true }
            ModeChip("Everything", !keepText, Modifier.weight(1f)) { keepText = false }
        }
        Text(
            if (keepText) "Removes pen, pencil and highlighter marks and stains - printed text underneath stays."
            else "Wipes the painted area back to clean paper, text included.",
            color = ScanColors.TextDim,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Brush", color = Color.White, fontSize = 14.sp)
            Spacer(Modifier.width(12.dp))
            Slider(
                value = brush,
                onValueChange = { brush = it },
                valueRange = 0.008f..0.08f,
                colors = SliderDefaults.colors(thumbColor = ScanColors.Accent, activeTrackColor = ScanColors.Accent),
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { strokes.clear() }) { Text("Clear", color = Color.White) }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ModeChip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) ScanColors.Accent else ScanColors.SurfaceHigh)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, fontSize = 13.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}
