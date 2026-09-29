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
import com.rskusum.scanner.data.Page
import com.rskusum.scanner.ui.ScanColors
import com.rskusum.scanner.vision.EraseStroke
import com.rskusum.scanner.vision.NPoint
import kotlinx.coroutines.flow.collectLatest
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
    // Clean base (page without any erasing) + live preview with all strokes applied. The user sees
    // the real result of every stroke: marks disappear, nothing is painted over the page.
    val base by produceState<android.graphics.Bitmap?>(null, page.id, page.quad, page.rotation, page.filter) {
        value = vm.eraserBase(page)
    }
    val strokes = remember(page.id) { mutableStateListOf<EraseStroke>().apply { addAll(page.erasures) } }
    var preview by remember { mutableStateOf<ImageBitmap?>(null) }
    var updating by remember { mutableStateOf(false) }
    LaunchedEffect(base) {
        val b = base ?: return@LaunchedEffect
        androidx.compose.runtime.snapshotFlow { strokes.toList() }.collectLatest { list ->
            updating = true
            preview = vm.eraserPreview(b, list).asImageBitmap()
            updating = false
        }
    }
    var keepText by remember { mutableStateOf(true) }
    var brush by remember { mutableFloatStateOf(0.03f) }
    val live = remember { mutableStateListOf<NPoint>() }
    var finger by remember { mutableStateOf<Offset?>(null) }

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
            val img = preview
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
                        .pointerInput(img.width, img.height, keepText, brush) {
                            detectDragGestures(
                                onDragStart = { live.clear(); live.add(norm(it)); finger = it },
                                onDragEnd = {
                                    if (live.isNotEmpty()) strokes.add(EraseStroke(live.toList(), brush, keepText))
                                    live.clear(); finger = null
                                },
                                onDragCancel = { live.clear(); finger = null },
                            ) { change, _ ->
                                change.consume()
                                live.add(norm(change.position))
                                finger = change.position
                            }
                        }
                ) {
                    drawImage(img, dstOffset = IntOffset(ox.roundToInt(), oy.roundToInt()), dstSize = IntSize(dw.roundToInt(), dh.roundToInt()))
                    val rpx = brush * dw
                    // Stroke in progress: only a faint outline (the page stays visible underneath).
                    if (live.size > 1) {
                        val path = androidx.compose.ui.graphics.Path()
                        live.forEachIndexed { i, p ->
                            val o = Offset(ox + p.x * dw, oy + p.y * dh)
                            if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
                        }
                        drawPath(
                            path, Color.White.copy(alpha = 0.35f),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = rpx * 2, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round),
                        )
                    }
                    // Brush ring under the finger.
                    finger?.let {
                        drawCircle(Color.Black.copy(alpha = 0.6f), rpx, it, style = androidx.compose.ui.graphics.drawscope.Stroke(3.dp.toPx()))
                        drawCircle(Color.White, rpx, it, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
                    }
                }
                if (updating) {
                    CircularProgressIndicator(
                        color = ScanColors.Accent, strokeWidth = 2.dp,
                        modifier = Modifier.align(Alignment.TopEnd).size(22.dp),
                    )
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
            if (keepText) "Rub over marks: pen, pencil, highlighter and stains disappear, printed text stays. Repeat as often as you like."
            else "Rub over an area to wipe it back to clean paper, text included.",
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
