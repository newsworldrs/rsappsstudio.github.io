package com.rskusum.scanner.ui.review

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.outlined.AddAPhoto
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.AutoFixNormal
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.TextSnippet
import androidx.compose.material.icons.outlined.WbShade
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.rskusum.scanner.R
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.rskusum.scanner.ScannerViewModel
import com.rskusum.scanner.data.Images
import com.rskusum.scanner.data.Page
import com.rskusum.scanner.ui.ScanColors
import com.rskusum.scanner.vision.ScanFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(
    vm: ScannerViewModel,
    initialPage: Int,
    onPageChanged: (Int) -> Unit,
    onAddPage: () -> Unit,
    onCrop: (Int) -> Unit,
    onErase: (Int) -> Unit,
    onText: (Int) -> Unit = {},
    onSaved: () -> Unit,
    onDiscard: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val pagerState = rememberPagerState(initialPage = initialPage.coerceIn(0, (vm.pages.size - 1).coerceAtLeast(0))) { vm.pages.size }
    LaunchedEffect(pagerState) { snapshotFlow { pagerState.currentPage }.collect { onPageChanged(it) } }
    // Jump to newly added pages (e.g. gallery import finishing while on this screen).
    LaunchedEffect(Unit) {
        var lastCount = vm.pages.size
        snapshotFlow { vm.pages.size }.collect { n ->
            if (n > lastCount) pagerState.animateScrollToPage(n - 1)
            lastCount = n
        }
    }

    var showFilters by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val current: Page? = vm.pages.getOrNull(pagerState.currentPage)

    fun doSave(asJpeg: Boolean) {
        scope.launch {
            saving = true
            snapshotFlow { vm.isRendering }.first { !it }
            val r = if (asJpeg) vm.saveJpegs() else vm.savePdf()
            saving = false
            r.onSuccess {
                if (vm.options.standalone || asJpeg) Toast.makeText(context, it, Toast.LENGTH_LONG).show()
                if (!asJpeg) onSaved()
            }.onFailure { snackbar.showSnackbar(context.getString(R.string.rs_scanner_save_failed, it.message ?: "")) }
        }
    }

    var pendingJpeg by remember { mutableStateOf(false) }
    var choosingSize by remember { mutableStateOf(false) }
    val legacyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) doSave(pendingJpeg) else scope.launch { snackbar.showSnackbar(context.getString(R.string.rs_scanner_storage_needed)) }
    }
    fun save(asJpeg: Boolean) {
        // Only the standalone app writes to public storage; embedded results stay app-private.
        if (vm.options.standalone && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingJpeg = asJpeg
            legacyPermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            doSave(asJpeg)
        }
    }

    Scaffold(
        containerColor = ScanColors.Surface,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black, titleContentColor = Color.White),
                navigationIcon = {
                    IconButton(onClick = onAddPage) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.rs_scanner_back_to_camera), tint = Color.White) }
                },
                title = {
                    Text(
                        vm.documentName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 17.sp,
                        modifier = Modifier.clickable { renaming = true },
                    )
                },
                actions = {
                    Button(
                        // Embedded without a PDF there is no size to choose: finish right away.
                        onClick = { if (vm.options.standalone || vm.options.returnPdf) choosingSize = true else doSave(asJpeg = false) },
                        enabled = !saving && vm.pages.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(containerColor = ScanColors.Accent),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                    ) { Text(stringResource(if (vm.options.standalone) R.string.rs_scanner_save_pdf else R.string.rs_scanner_done), fontWeight = FontWeight.SemiBold) }
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.rs_scanner_more), tint = Color.White) }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            if (vm.options.standalone) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.rs_scanner_save_jpg)) }, onClick = { menuOpen = false; save(asJpeg = true) })
                            }
                            DropdownMenuItem(text = { Text(stringResource(R.string.rs_scanner_share_pages)) }, onClick = {
                                menuOpen = false
                                runCatching { context.startActivity(vm.sessionShareIntent()) }
                            })
                            DropdownMenuItem(text = { Text(stringResource(R.string.rs_scanner_rename)) }, onClick = { menuOpen = false; renaming = true })
                            DropdownMenuItem(text = { Text(stringResource(R.string.rs_scanner_discard_scan)) }, onClick = { menuOpen = false; confirmDiscard = true })
                        }
                    }
                },
            )
        },
        bottomBar = {
            Column(Modifier.background(Color.Black).navigationBarsPadding()) {
                if (showFilters && current != null) FilterStrip(vm, current)
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    ToolButton(Icons.Outlined.AddAPhoto, stringResource(R.string.rs_scanner_tool_add)) { onAddPage() }
                    ToolButton(Icons.Outlined.Crop, stringResource(R.string.rs_scanner_tool_crop)) { current?.let { onCrop(vm.pages.indexOf(it)) } }
                    ToolButton(Icons.Filled.RotateRight, stringResource(R.string.rs_scanner_tool_rotate)) { current?.let { vm.rotate(it) } }
                    ToolButton(Icons.Outlined.AutoFixNormal, stringResource(R.string.rs_scanner_tool_erase)) { current?.let { onErase(vm.pages.indexOf(it)) } }
                    ToolButton(Icons.Outlined.AutoFixHigh, stringResource(R.string.rs_scanner_tool_filters), selected = showFilters) { showFilters = !showFilters }
                    ToolButton(Icons.Outlined.TextSnippet, stringResource(R.string.rs_scanner_tool_text)) { current?.let { onText(vm.pages.indexOf(it)) } }
                    ToolButton(Icons.Outlined.Delete, stringResource(R.string.rs_scanner_tool_delete)) {
                        current?.let {
                            vm.deletePage(it)
                            if (vm.pages.isEmpty()) onAddPage()
                        }
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (vm.pages.isEmpty()) {
                CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
            } else {
                var pageZoomed by remember { mutableStateOf(false) }
                HorizontalPager(
                    state = pagerState,
                    contentPadding = PaddingValues(horizontal = 28.dp, vertical = 20.dp),
                    pageSpacing = 16.dp,
                    key = { i -> vm.pages.getOrNull(i)?.id ?: i },
                    // While a page is zoomed in, one finger moves around the page instead of paging.
                    userScrollEnabled = !pageZoomed,
                    modifier = Modifier.fillMaxSize(),
                ) { i ->
                    vm.pages.getOrNull(i)?.let { p ->
                        PageView(p, onZoomed = { z -> if (i == pagerState.currentPage) pageZoomed = z })
                    }
                }
                val smart = vm.pages.getOrNull(pagerState.currentPage)?.smartLabel
                Text(
                    "${pagerState.currentPage + 1} / ${vm.pages.size}" + (smart?.let { "  ·  ✨ " + stringResource(R.string.rs_scanner_smart_chip, it) } ?: ""),
                    color = Color.White,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 4.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Black.copy(alpha = 0.6f))
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
            if (saving) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color.White)
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(R.string.rs_scanner_saving), color = Color.White)
                    }
                }
            }
        }
    }

    if (renaming) {
        var text by remember { mutableStateOf(vm.documentName) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text(stringResource(R.string.rs_scanner_rename)) },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = { if (text.isNotBlank()) vm.documentName = text.trim(); renaming = false }) { Text(stringResource(R.string.rs_scanner_ok)) }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text(stringResource(R.string.rs_scanner_cancel)) } },
        )
    }
    if (choosingSize) {
        AlertDialog(
            onDismissRequest = { choosingSize = false },
            title = { Text(stringResource(R.string.rs_scanner_pdf_size)) },
            text = {
                Column {
                    com.rskusum.scanner.data.PdfQuality.entries.forEach { q ->
                        val perPage = when (q) {
                            com.rskusum.scanner.data.PdfQuality.SMALL -> stringResource(R.string.rs_scanner_pdf_small_hint)
                            com.rskusum.scanner.data.PdfQuality.BALANCED -> stringResource(R.string.rs_scanner_pdf_balanced_hint)
                            com.rskusum.scanner.data.PdfQuality.HIGH -> stringResource(R.string.rs_scanner_pdf_high_hint)
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { vm.pdfQuality = q }
                                .padding(vertical = 6.dp),
                        ) {
                            androidx.compose.material3.RadioButton(selected = vm.pdfQuality == q, onClick = { vm.pdfQuality = q })
                            Column {
                                Text(stringResource(q.labelRes), color = Color.White)
                                Text(perPage, color = ScanColors.TextDim, fontSize = 12.sp)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { choosingSize = false; save(asJpeg = false) }) { Text(stringResource(R.string.rs_scanner_save_pdf)) } },
            dismissButton = { TextButton(onClick = { choosingSize = false }) { Text(stringResource(R.string.rs_scanner_cancel)) } },
        )
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.rs_scanner_discard_title)) },
            text = { Text(pluralStringResource(R.plurals.rs_scanner_discard_message, vm.pages.size, vm.pages.size)) },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; onDiscard() }) { Text(stringResource(R.string.rs_scanner_discard)) } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.rs_scanner_cancel)) } },
        )
    }
}

@Composable
private fun PageView(page: Page, onZoomed: (Boolean) -> Unit = {}) {
    val image by produceState<ImageBitmap?>(null, page.version, page.processedFile) {
        val f = page.processedFile
        value = if (f == null) null else withContext(Dispatchers.IO) { Images.decodeFile(f, 3000)?.asImageBitmap() }
    }
    // Zoom: pinch (up to 5x), drag to move while zoomed, double-tap to zoom in / back out.
    var scale by remember(page.id) { mutableFloatStateOf(1f) }
    var offset by remember(page.id) { mutableStateOf(Offset.Zero) }
    DisposableEffect(page.id) { onDispose { onZoomed(false) } }
    Box(
        Modifier
            .fillMaxSize()
            .clipToBounds()
            .pointerInput(page.id) {
                detectTapGestures(onDoubleTap = { tap ->
                    if (scale > 1.05f) {
                        scale = 1f; offset = Offset.Zero
                    } else {
                        scale = 2.5f
                        val c = Offset(size.width / 2f, size.height / 2f)
                        val max = Offset(size.width * (scale - 1) / 2f, size.height * (scale - 1) / 2f)
                        val o = (c - tap) * (scale - 1)
                        offset = Offset(o.x.coerceIn(-max.x, max.x), o.y.coerceIn(-max.y, max.y))
                    }
                    onZoomed(scale > 1.05f)
                })
            }
            .pointerInput(page.id) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val fingers = event.changes.count { it.pressed }
                        // Two fingers always zoom; one finger pans only while zoomed (otherwise it
                        // swipes to the next page).
                        if (fingers >= 2 || scale > 1.01f) {
                            scale = (scale * event.calculateZoom()).coerceIn(1f, 5f)
                            val max = Offset(size.width * (scale - 1) / 2f, size.height * (scale - 1) / 2f)
                            val o = offset + event.calculatePan()
                            offset = if (scale <= 1.01f) Offset.Zero else Offset(o.x.coerceIn(-max.x, max.x), o.y.coerceIn(-max.y, max.y))
                            onZoomed(scale > 1.01f)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        image?.let {
            Image(
                it,
                contentDescription = stringResource(R.string.rs_scanner_page),
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .graphicsLayer {
                        scaleX = scale; scaleY = scale
                        translationX = offset.x; translationY = offset.y
                    }
                    .shadow(8.dp)
                    .background(Color.White),
            )
        }
        if (page.rendering || image == null) CircularProgressIndicator(color = ScanColors.Accent)
        if (page.idSide >= 0) {
            Text(
                stringResource(if (page.idSide == 0) R.string.rs_scanner_id_front else R.string.rs_scanner_id_back),
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .clip(RoundedCornerShape(12.dp))
                    .background(ScanColors.Accent)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun FilterStrip(vm: ScannerViewModel, page: Page) {
    val previews by produceState<Map<ScanFilter, ImageBitmap>>(emptyMap(), page.id, page.quad, page.rotation, page.removeShadow) {
        value = vm.filterPreviews(page)
    }
    val context = LocalContext.current
    Column {
        // Shadow removal is its own switch: it stays on whatever filter is chosen.
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (page.removeShadow) ScanColors.Accent else ScanColors.SurfaceHigh)
                    .clickable { vm.setRemoveShadow(page, !page.removeShadow) }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.WbShade, null, tint = Color.White, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(if (page.removeShadow) R.string.rs_scanner_shadow_removed else R.string.rs_scanner_remove_shadow), color = Color.White, fontSize = 13.sp)
            }
            Spacer(Modifier.weight(1f))
            Row(
                Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .border(1.dp, ScanColors.AccentBright, RoundedCornerShape(20.dp))
                    .clickable {
                        vm.applyFilterToAll(page.filter, page.removeShadow)
                        val what = com.rskusum.scanner.vision.SmartPick(page.filter, page.removeShadow).label(context)
                        Toast.makeText(context, context.resources.getQuantityString(R.plurals.rs_scanner_applied_to_all, vm.pages.size, what, vm.pages.size), Toast.LENGTH_SHORT).show()
                    }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.DoneAll, null, tint = ScanColors.AccentBright, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.rs_scanner_apply_to_all), color = ScanColors.AccentBright, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        page.smartLabel?.let {
            Text(
                "✨ " + stringResource(R.string.rs_scanner_smart_picked, it),
                color = ScanColors.Marigold, fontSize = 12.sp,
                modifier = Modifier.padding(start = 14.dp, top = 6.dp),
            )
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(ScanFilter.choices) { f ->
                val selected = page.filter == f
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clickable { vm.setFilter(page, f) },
                ) {
                    Box(
                        Modifier
                            .size(width = 66.dp, height = 86.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF2A2A2A))
                            .border(if (selected) 3.dp else 1.dp, if (selected) ScanColors.Accent else Color(0x33FFFFFF), RoundedCornerShape(6.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        val p = previews[f]
                        if (p != null) Image(p, stringResource(f.labelRes), contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        else CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(f.labelRes), color = if (selected) ScanColors.AccentBright else Color.White, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun ToolButton(icon: ImageVector, label: String, selected: Boolean = false, onClick: () -> Unit) {
    val tint = if (selected) ScanColors.Accent else Color.White
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(26.dp))
        Spacer(Modifier.height(2.dp))
        Text(label, color = tint, fontSize = 12.sp)
    }
}
