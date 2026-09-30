package com.rskusum.scanner.ui.erase

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material.icons.outlined.Compare
import androidx.compose.material.icons.outlined.ZoomOutMap
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rskusum.scanner.R
import com.rskusum.scanner.ScannerViewModel
import androidx.compose.ui.res.stringResource
import com.rskusum.scanner.data.Page
import com.rskusum.scanner.ui.ScanColors
import com.rskusum.scanner.vision.EraseStroke
import com.rskusum.scanner.vision.NPoint
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.min
import kotlin.math.roundToInt

private enum class Brush(@androidx.annotation.StringRes val label: Int, @androidx.annotation.StringRes val help: Int) {
    MARKS(R.string.rs_scanner_erase_marks, R.string.rs_scanner_erase_marks_help),
    EVERYTHING(R.string.rs_scanner_erase_everything, R.string.rs_scanner_erase_everything_help),
    RESTORE(R.string.rs_scanner_erase_restore, R.string.rs_scanner_erase_restore_help),
}

/**
 * Eraser: paint over the finished page and see the real result live.
 *  - One finger erases; two fingers zoom (up to 6x) and move the page for precise work.
 *  - Brushes: Marks only / Everything / Restore. Edges are feathered.
 *  - Hold "Compare" to see the page before erasing.
 * Strokes are stored on the page and applied at render time (non-destructive: undo / clear).
 */
@Composable
fun EraseScreen(vm: ScannerViewModel, page: Page?, onDone: () -> Unit) {
    if (page == null) {
        LaunchedEffect(Unit) { onDone() }
        return
    }
    // Clean base (page without any erasing) + live preview with all strokes applied.
    val base by produceState<android.graphics.Bitmap?>(null, page.id, page.quad, page.rotation, page.filter, page.removeShadow) {
        value = vm.eraserBase(page)
    }
    val baseImage = remember(base) { base?.asImageBitmap() }
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
    var mode by remember { mutableStateOf(Brush.MARKS) }
    var brush by remember { mutableFloatStateOf(0.03f) }   // on-screen radius, fraction of page width
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var comparing by remember { mutableStateOf(false) }
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
                Icon(Icons.Filled.Close, stringResource(R.string.rs_scanner_cancel), tint = Color.White)
            }
            Text(stringResource(R.string.rs_scanner_eraser), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.Center))
            Row(Modifier.align(Alignment.CenterEnd)) {
                IconButton(onClick = { if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex) }, enabled = strokes.isNotEmpty()) {
                    Icon(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.rs_scanner_undo), tint = if (strokes.isNotEmpty()) Color.White else Color.Gray)
                }
                IconButton(onClick = { vm.setErasures(page, strokes.toList()); onDone() }) {
                    Icon(Icons.Filled.Check, stringResource(R.string.rs_scanner_apply), tint = ScanColors.AccentBright, modifier = Modifier.size(30.dp))
                }
            }
        }

        BoxWithConstraints(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(12.dp)
                .clipToBounds(),
            contentAlignment = Alignment.Center,
        ) {
            val img = if (comparing) baseImage else preview
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
                val centre = Offset(cw / 2, ch / 2)
                // Screen -> page (0..1), undoing zoom (around the centre) and pan.
                fun norm(o: Offset): NPoint {
                    val p = (o - pan - centre) / zoom + centre
                    return NPoint(((p.x - ox) / dw).coerceIn(0f, 1f), ((p.y - oy) / dh).coerceIn(0f, 1f))
                }
                Canvas(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(img.width, img.height, mode, brush) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                live.clear(); live.add(norm(down.position)); finger = down.position
                                var multi = false
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val pressed = event.changes.filter { it.pressed }
                                    if (pressed.isEmpty()) break
                                    if (pressed.size >= 2) {
                                        // Two fingers: zoom + move, never erase.
                                        if (!multi) { multi = true; live.clear(); finger = null }
                                        val newZoom = (zoom * event.calculateZoom()).coerceIn(1f, 6f)
                                        val maxPan = Offset(cw * (newZoom - 1) / 2, ch * (newZoom - 1) / 2)
                                        val p = pan + event.calculatePan()
                                        pan = Offset(p.x.coerceIn(-maxPan.x, maxPan.x), p.y.coerceIn(-maxPan.y, maxPan.y))
                                        zoom = newZoom
                                        event.changes.forEach { it.consume() }
                                    } else if (!multi) {
                                        val c = pressed[0]
                                        live.add(norm(c.position)); finger = c.position
                                        c.consume()
                                    }
                                }
                                if (!multi && live.isNotEmpty()) {
                                    // Brush size is on-screen: at 3x zoom the stroke is 3x finer on the page.
                                    strokes.add(
                                        EraseStroke(
                                            live.toList(), brush / zoom,
                                            keepText = mode == Brush.MARKS, restore = mode == Brush.RESTORE,
                                        )
                                    )
                                }
                                live.clear(); finger = null
                            }
                        }
                ) {
                    withTransform({
                        translate(pan.x, pan.y)
                        scale(zoom, zoom, centre)
                    }) {
                        drawImage(img, dstOffset = IntOffset(ox.roundToInt(), oy.roundToInt()), dstSize = IntSize(dw.roundToInt(), dh.roundToInt()))
                        // Stroke in progress: faint outline, the page stays visible underneath.
                        if (live.size > 1) {
                            val path = androidx.compose.ui.graphics.Path()
                            live.forEachIndexed { i, p ->
                                val o = Offset(ox + p.x * dw, oy + p.y * dh)
                                if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
                            }
                            val tint = if (mode == Brush.RESTORE) ScanColors.Marigold else Color.White
                            drawPath(
                                path, tint.copy(alpha = 0.35f),
                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = brush / zoom * dw * 2, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round),
                            )
                        }
                    }
                    // Brush ring under the finger (screen size).
                    finger?.let {
                        val rpx = brush * dw
                        drawCircle(Color.Black.copy(alpha = 0.6f), rpx, it, style = androidx.compose.ui.graphics.drawscope.Stroke(3.dp.toPx()))
                        drawCircle(if (mode == Brush.RESTORE) ScanColors.Marigold else Color.White, rpx, it, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
                    }
                }
                if (updating) {
                    CircularProgressIndicator(
                        color = ScanColors.Accent, strokeWidth = 2.dp,
                        modifier = Modifier.align(Alignment.TopEnd).size(22.dp),
                    )
                }
                // Hold to compare with the page before erasing.
                Row(
                    Modifier
                        .align(Alignment.BottomStart)
                        .clip(RoundedCornerShape(16.dp))
                        .background(ScanColors.Bar.copy(alpha = 0.8f))
                        .pointerInput(Unit) {
                            detectTapGestures(onPress = { comparing = true; tryAwaitRelease(); comparing = false })
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Compare, null, tint = Color.White, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(if (comparing) R.string.rs_scanner_before else R.string.rs_scanner_hold_before), color = Color.White, fontSize = 12.sp)
                }
                if (zoom > 1.01f) {
                    Row(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .clip(RoundedCornerShape(16.dp))
                            .background(ScanColors.Bar.copy(alpha = 0.8f))
                            .clickable { zoom = 1f; pan = Offset.Zero }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.ZoomOutMap, null, tint = Color.White, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.rs_scanner_zoom_fit, zoom), color = Color.White, fontSize = 12.sp)
                    }
                }
            }
        }

        // Brush type + size
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Brush.entries.forEach { b -> ModeChip(stringResource(b.label), mode == b, Modifier.weight(1f)) { mode = b } }
        }
        Text(
            stringResource(mode.help) + "  " + stringResource(R.string.rs_scanner_pinch_zoom),
            color = ScanColors.TextDim,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.rs_scanner_brush), color = Color.White, fontSize = 14.sp)
            Spacer(Modifier.width(12.dp))
            Slider(
                value = brush,
                onValueChange = { brush = it },
                valueRange = 0.008f..0.08f,
                colors = SliderDefaults.colors(thumbColor = ScanColors.AccentBright, activeTrackColor = ScanColors.Accent),
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { strokes.clear() }) { Text(stringResource(R.string.rs_scanner_clear), color = Color.White) }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ModeChip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(ScanColors.buttonBackground(selected, ScanColors.SurfaceHigh))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, fontSize = 13.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}
