package com.rskusum.scanner.ui.crop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.CropFree
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rskusum.scanner.R
import com.rskusum.scanner.ScannerViewModel
import androidx.compose.ui.res.stringResource
import com.rskusum.scanner.data.Images
import com.rskusum.scanner.data.Page
import com.rskusum.scanner.ui.ScanColors
import com.rskusum.scanner.vision.NPoint
import com.rskusum.scanner.vision.Quad
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun CropScreen(vm: ScannerViewModel, page: Page?, onDone: () -> Unit) {
    if (page == null) {
        LaunchedEffect(Unit) { onDone() }
        return
    }
    val bitmap by produceState<ImageBitmap?>(null, page.id) {
        value = withContext(Dispatchers.IO) { Images.decodeFile(page.originalFile, 2000)?.asImageBitmap() }
    }
    var quad by remember(page.id) { mutableStateOf(page.quad) }
    var detecting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

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
            Text(
                stringResource(R.string.rs_scanner_adjust_corners),
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.Center),
            )
            IconButton(
                onClick = { vm.setQuad(page, quad); onDone() },
                modifier = Modifier.align(Alignment.CenterEnd),
            ) {
                Icon(Icons.Filled.Check, stringResource(R.string.rs_scanner_done), tint = ScanColors.Accent, modifier = Modifier.size(30.dp))
            }
        }

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(28.dp),
            contentAlignment = Alignment.Center,
        ) {
            val bmp = bitmap
            if (bmp != null) CropEditor(bmp, quad) { if (!detecting) quad = it }
            // Drawn on top of the photo so it's clearly visible while Auto detect works.
            if (bmp == null || detecting) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = if (bmp == null) 0f else 0.45f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = ScanColors.Accent, strokeWidth = 4.dp, modifier = Modifier.size(56.dp))
                        if (detecting) {
                            Spacer(Modifier.height(12.dp))
                            Text(stringResource(R.string.rs_scanner_finding_edges), color = Color.White, fontSize = 15.sp)
                        }
                    }
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            TextButton(onClick = {
                if (detecting) return@TextButton
                scope.launch {
                    detecting = true
                    // The current outline is used as a starting guess: roughly dragging the
                    // handles near the page and pressing Auto snaps them onto the real edges.
                    val found = vm.autoDetect(page, quad)
                    if (found != null) quad = found
                    else android.widget.Toast.makeText(context, context.getString(R.string.rs_scanner_no_clear_edges), android.widget.Toast.LENGTH_SHORT).show()
                    detecting = false
                }
            }) {
                if (detecting) CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                else Icon(Icons.Outlined.AutoFixHigh, null, tint = Color.White)
                Spacer(Modifier.size(8.dp))
                Text(stringResource(if (detecting) R.string.rs_scanner_detecting else R.string.rs_scanner_auto_detect), color = Color.White)
            }
            TextButton(onClick = { quad = Quad.FULL }) {
                Icon(Icons.Outlined.CropFree, null, tint = Color.White)
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.rs_scanner_no_crop), color = Color.White)
            }
        }
    }
}

/** Handles: 0..3 corners (TL, TR, BR, BL), 4..7 edge midpoints (edge i joins corner i and i+1). */
@Composable
private fun CropEditor(bmp: ImageBitmap, quad: Quad, onChange: (Quad) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val cw = constraints.maxWidth.toFloat()
        val ch = constraints.maxHeight.toFloat()
        val s = min(cw / bmp.width, ch / bmp.height)
        val dw = bmp.width * s
        val dh = bmp.height * s
        val ox = (cw - dw) / 2
        val oy = (ch - dh) / 2

        fun toScreen(p: NPoint) = Offset(ox + p.x * dw, oy + p.y * dh)

        var active by remember { mutableIntStateOf(-1) }
        val current by rememberUpdatedState(quad)
        val change by rememberUpdatedState(onChange)

        fun handles(q: Quad): List<Offset> {
            val c = q.points.map { toScreen(it) }
            return c + List(4) { i -> (c[i] + c[(i + 1) % 4]) / 2f }
        }

        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(bmp, cw, ch) {
                    val grab = 44.dp.toPx()
                    detectDragGestures(
                        onDragStart = { pos ->
                            val hs = handles(current)
                            val i = hs.indices.minBy { (hs[it] - pos).getDistance() }
                            active = if ((hs[i] - pos).getDistance() <= grab) i else -1
                        },
                        onDragEnd = { active = -1 },
                        onDragCancel = { active = -1 },
                    ) { ev, drag ->
                        val a = active
                        if (a < 0) return@detectDragGestures
                        ev.consume()
                        val dx = drag.x / dw
                        val dy = drag.y / dh
                        val q = current
                        fun moved(p: NPoint) = NPoint((p.x + dx).coerceIn(0f, 1f), (p.y + dy).coerceIn(0f, 1f))
                        val next = if (a < 4) {
                            q.with(a, moved(q.points[a]))
                        } else {
                            val e = a - 4
                            q.with(e, moved(q.points[e])).let { it.with((e + 1) % 4, moved(q.points[(e + 1) % 4])) }
                        }
                        if (isConvex(next)) change(next)
                    }
                }
        ) {
            drawImage(
                bmp,
                dstOffset = IntOffset(ox.roundToInt(), oy.roundToInt()),
                dstSize = IntSize(dw.roundToInt(), dh.roundToInt()),
            )
            val c = quad.points.map { toScreen(it) }
            val quadPath = Path().apply {
                moveTo(c[0].x, c[0].y); for (i in 1 until 4) lineTo(c[i].x, c[i].y); close()
            }
            val shade = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(Rect(ox, oy, ox + dw, oy + dh))
                addPath(quadPath)
            }
            drawPath(shade, Color.Black.copy(alpha = 0.55f))
            drawPath(quadPath, ScanColors.Accent, style = Stroke(2.5.dp.toPx()))

            handles(quad).forEachIndexed { i, h ->
                val r = if (i < 4) 11.dp.toPx() else 7.dp.toPx()
                drawCircle(Color.White, r + 2.dp.toPx(), h)
                drawCircle(if (i == active) ScanColors.Accent else Color.White, r, h)
                drawCircle(ScanColors.Accent, r, h, style = Stroke(2.dp.toPx()))
            }

            // Magnifier loupe while dragging a corner.
            val a = active
            if (a in 0..3) {
                val focus = c[a]
                val r = 58.dp.toPx()
                val margin = 12.dp.toPx()
                val leftSide = focus.x > size.width / 2 || focus.y > size.height / 2
                val center = Offset(if (leftSide) r + margin else size.width - r - margin, r + margin)
                val zoom = 2.5f
                val bx = (focus.x - ox) / s
                val by = (focus.y - oy) / s
                val srcSide = (2 * r / (s * zoom))
                val clip = Path().apply { addOval(Rect(center, r)) }
                clipPath(clip) {
                    drawRect(Color.Black, topLeft = center - Offset(r, r), size = androidx.compose.ui.geometry.Size(2 * r, 2 * r))
                    // Source window around the finger, clipped to the bitmap; destination follows.
                    val scaleF = 2 * r / srcSide
                    val sl = bx - srcSide / 2
                    val st = by - srcSide / 2
                    val cl = sl.coerceAtLeast(0f)
                    val ct = st.coerceAtLeast(0f)
                    val cr = (sl + srcSide).coerceAtMost(bmp.width.toFloat())
                    val cb = (st + srcSide).coerceAtMost(bmp.height.toFloat())
                    if (cr - cl >= 1f && cb - ct >= 1f) {
                        drawImage(
                            bmp,
                            srcOffset = IntOffset(cl.toInt(), ct.toInt()),
                            srcSize = IntSize((cr - cl).toInt(), (cb - ct).toInt()),
                            dstOffset = IntOffset(
                                (center.x - r + (cl - sl) * scaleF).roundToInt(),
                                (center.y - r + (ct - st) * scaleF).roundToInt(),
                            ),
                            dstSize = IntSize(((cr - cl) * scaleF).roundToInt(), ((cb - ct) * scaleF).roundToInt()),
                        )
                    }
                    // quad edges inside the loupe
                    val k = zoom
                    fun toLoupe(p: Offset) = center + (p - focus) * k
                    val prev = c[(a + 3) % 4]
                    val next = c[(a + 1) % 4]
                    drawLine(ScanColors.Accent, toLoupe(prev), toLoupe(focus), 2.dp.toPx())
                    drawLine(ScanColors.Accent, toLoupe(focus), toLoupe(next), 2.dp.toPx())
                }
                drawCircle(Color.White, r, center, style = Stroke(3.dp.toPx()))
                drawLine(Color.White, center - Offset(10.dp.toPx(), 0f), center + Offset(10.dp.toPx(), 0f), 1.5.dp.toPx())
                drawLine(Color.White, center - Offset(0f, 10.dp.toPx()), center + Offset(0f, 10.dp.toPx()), 1.5.dp.toPx())
            }
        }
    }
}

private fun isConvex(q: Quad): Boolean {
    val p = q.points
    var sign = 0
    for (i in 0 until 4) {
        val a = p[i]; val b = p[(i + 1) % 4]; val c = p[(i + 2) % 4]
        val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
        val s = if (cross > 0) 1 else if (cross < 0) -1 else 0
        if (s == 0) return false
        if (sign == 0) sign = s else if (s != sign) return false
    }
    // reject degenerate slivers
    val minSide = (0 until 4).minOf { i -> hypot(p[i].x - p[(i + 1) % 4].x, p[i].y - p[(i + 1) % 4].y) }
    return minSide > 0.03f
}
