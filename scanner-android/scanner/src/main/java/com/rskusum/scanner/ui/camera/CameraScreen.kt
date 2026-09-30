package com.rskusum.scanner.ui.camera

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaActionSound
import android.net.Uri
import android.os.SystemClock
import android.util.Size
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.ContactPage
import androidx.compose.material.icons.outlined.CropFree
import androidx.compose.material.icons.outlined.CropLandscape
import androidx.compose.material.icons.outlined.CropPortrait
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.ImportContacts
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rskusum.scanner.R
import com.rskusum.scanner.ScannerViewModel
import com.rskusum.scanner.BRAND_LINE
import com.rskusum.scanner.camera.CapturePhase
import com.rskusum.scanner.camera.DocumentAnalyzer
import com.rskusum.scanner.data.Images
import com.rskusum.scanner.data.ScanMode
import com.rskusum.scanner.ui.ScanColors
import com.rskusum.scanner.vision.Quad
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

private class CameraHolder {
    var capture: ImageCapture? = null
    var camera: Camera? = null
    var previewView: PreviewView? = null
}

private enum class Flash(val mode: Int) {
    AUTO(ImageCapture.FLASH_MODE_AUTO), ON(ImageCapture.FLASH_MODE_ON), OFF(ImageCapture.FLASH_MODE_OFF);

    fun next() = entries[(ordinal + 1) % entries.size]
}

@Composable
fun CameraScreen(vm: ScannerViewModel, onOpenReview: () -> Unit, onHome: (() -> Unit)? = null) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) permissionLauncher.launch(Manifest.permission.CAMERA) }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(20)) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            vm.importFromGallery(uris)
            onOpenReview()
        }
    }

    val state by vm.camera.collectAsStateWithLifecycle()
    val holder = remember { CameraHolder() }
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val mainExecutor = remember { ContextCompat.getMainExecutor(context) }
    val captureExecutor = remember { Executors.newSingleThreadExecutor() }
    val sound = remember { MediaActionSound().apply { load(MediaActionSound.SHUTTER_CLICK) } }
    DisposableEffect(Unit) { onDispose { captureExecutor.shutdown(); sound.release() } }

    var flash by remember { mutableStateOf(Flash.AUTO) }
    var qrMode by remember { mutableStateOf(false) }
    var qrText by remember { mutableStateOf<String?>(null) }
    var capturing by remember { mutableStateOf(false) }
    val flashOverlay = remember { Animatable(0f) }
    var toast by remember { mutableStateOf<String?>(null) }

    lateinit var analyzer: DocumentAnalyzer

    // Physical phone orientation (the app UI stays portrait). Used to turn book pages upright when
    // the phone is held across an open book. Flat phones report "unknown": keep the last value.
    val deviceRotation = remember { java.util.concurrent.atomic.AtomicInteger(270) }
    DisposableEffect(Unit) {
        val listener = object : android.view.OrientationEventListener(context) {
            override fun onOrientationChanged(o: Int) {
                if (o == ORIENTATION_UNKNOWN) return
                when (o) {
                    in 45 until 135 -> deviceRotation.set(90)
                    in 225 until 315 -> deviceRotation.set(270)
                }
            }
        }
        if (listener.canDetectOrientation()) listener.enable()
        onDispose { listener.disable() }
    }

    fun takePhoto(ic: ImageCapture, hint: Quad?, frame: Quad?) {
        ic.takePicture(captureExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                // The photo is fully captured at this point: only now give the shutter feedback,
                // so moving the phone after hearing it can't disturb the image.
                mainExecutor.execute {
                    sound.play(MediaActionSound.SHUTTER_CLICK)
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    scope.launch {
                        flashOverlay.snapTo(0.8f)
                        flashOverlay.animateTo(0f, tween(320))
                    }
                }
                val bmp: Bitmap? = try {
                    val buf = image.planes[0].buffer
                    val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
                    Images.decodeBytes(bytes, image.imageInfo.rotationDegrees, ScannerViewModel.MAX_PHOTO_SIDE)
                } catch (t: Throwable) {
                    null
                } finally {
                    image.close()
                }
                mainExecutor.execute {
                    capturing = false
                    vm.tracker.onCaptured(bmp != null)
                    if (bmp != null) vm.addPhoto(bmp, hint, frame, deviceRotation.get()) else toast = context.getString(R.string.rs_scanner_capture_failed)
                }
            }

            override fun onError(exception: ImageCaptureException) {
                mainExecutor.execute {
                    capturing = false
                    vm.tracker.onCaptured(false)
                    toast = context.getString(R.string.rs_scanner_capture_failed_code, exception.imageCaptureError)
                }
            }
        })
    }

    fun capture(manual: Boolean) {
        val ic = holder.capture ?: return
        if (capturing) return
        capturing = true
        if (manual) vm.tracker.onManualCaptureStarted(SystemClock.elapsedRealtime())
        val hint = analyzer.lastQuad
        val frame = vm.guideFrame

        // Focus on the page before shooting (sharp text), then capture. Never wait > 1.2 s.
        // Shutter sound/flash are played in takePhoto once the image is actually captured.
        var shot = false
        fun shoot() {
            if (shot) return
            shot = true
            takePhoto(ic, hint, frame)
        }
        val cam = holder.camera
        val pv = holder.previewView
        val target = hint ?: frame
        if (cam != null && pv != null && pv.width > 0 && target != null) {
            val cx = target.points.map { it.x }.average().toFloat() * pv.width
            val cy = target.points.map { it.y }.average().toFloat() * pv.height
            val action = FocusMeteringAction.Builder(pv.meteringPointFactory.createPoint(cx, cy), FocusMeteringAction.FLAG_AF)
                .disableAutoCancel()
                .build()
            runCatching { cam.cameraControl.startFocusAndMetering(action).addListener({ shoot() }, mainExecutor) }
                .onFailure { shoot() }
            scope.launch { kotlinx.coroutines.delay(1200); shoot() }
        } else {
            shoot()
        }
    }

    analyzer = remember {
        DocumentAnalyzer(
            tracker = vm.tracker,
            edgeModel = com.rskusum.scanner.vision.EdgeModel.get(context),
            onState = vm::postCameraState,
            onAutoCapture = { mainExecutor.execute { capture(manual = false) } },
            onQr = { text -> mainExecutor.execute { if (qrText == null) qrText = text } },
        )
    }
    SideEffect {
        analyzer.autoCapture = vm.autoCapture
        analyzer.qrMode = qrMode
        analyzer.textMode = vm.mode.extractText && !qrMode
        analyzer.bookMode = vm.mode == ScanMode.BOOK && !qrMode
        analyzer.paused = qrText != null
        analyzer.frame = if (qrMode) null else vm.guideFrame
        analyzer.knownPages = vm.sessionSignatures
        analyzer.captureAllowed = vm.captureAllowed
        holder.capture?.flashMode = flash.mode
    }

    LaunchedEffect(toast) {
        if (toast != null) {
            kotlinx.coroutines.delay(2200)
            toast = null
        }
    }
    // ID card: once both sides are on the page, go straight to review.
    LaunchedEffect(vm.idStep) {
        if (vm.mode == ScanMode.ID_CARD && vm.idStep == ScannerViewModel.IdStep.DONE) {
            kotlinx.coroutines.delay(900)
            onOpenReview()
        }
    }
    // Page limit set by the calling app: once reached, go to review.
    val limitReached = vm.pageLimitReached
    LaunchedEffect(limitReached) {
        if (limitReached) {
            kotlinx.coroutines.delay(900)
            onOpenReview()
        }
    }
    // Pipeline messages, e.g. "Same page as the last scan - place another document".
    LaunchedEffect(Unit) {
        vm.messages.collect {
            toast = it
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        TopBar(
            qrMode = qrMode,
            onHome = onHome,
            // The QR scanner belongs to the standalone app, not to an embedded document scanner.
            onQr = if (vm.options.standalone) ({
                qrMode = !qrMode
                toast = context.getString(if (qrMode) R.string.rs_scanner_qr_scanner else R.string.rs_scanner_document_scanner)
            }) else null,
        )

        // Preview area. The camera streams are 4:3, so a 3:4 portrait box shows the full sensor
        // field-of-view with no cropping, making overlay coordinates exact.
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .aspectRatio(3f / 4f)
                    .clipToBounds()
                    .pointerModeSwipe(vm)
            ) {
                if (granted) {
                    CameraPreview(holder, analyzer, flash.mode)
                    val guide = vm.guideFrame
                    val book = vm.mode == ScanMode.BOOK && !qrMode
                    when {
                        qrMode -> QrFrame()
                        guide != null -> GuideFrameOverlay(guide, state.aligned)
                        else -> QuadOverlay(state.quad, state.phase)
                    }
                    // AI Text: live highlight of the text lines found on the page.
                    if (vm.mode.extractText && !qrMode) TextBoxesOverlay(state.textBoxes)
                    // Book guide: dashed divider across the whole preview, sideways
                    // page numbers 1 / 2 and a swap button. Same in frame and Free mode.
                    if (book) {
                        if (guide != null) {
                            BookOverlay((guide.tl.y + guide.br.y) / 2f, vm.bookSwap)
                        } else {
                            // Free mode: the spine line follows the book the camera actually sees.
                            state.spine?.let { FreeSpineOverlay(it, vm.bookSwap) }
                        }
                        BookSwapButton(
                            swapped = vm.bookSwap,
                            onSwap = {
                                vm.bookSwap = !vm.bookSwap
                                toast = context.getString(if (vm.bookSwap) R.string.rs_scanner_book_second_is_one else R.string.rs_scanner_book_first_is_one)
                            },
                            modifier = Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 8.dp),
                        )
                    }
                } else {
                    PermissionRationale { permissionLauncher.launch(Manifest.permission.CAMERA) }
                }

                val words = modeWords(vm.mode, vm.idStep)
                val hint = when {
                    !granted -> null
                    qrMode -> stringResource(R.string.rs_scanner_point_at_qr)
                    !vm.captureAllowed -> stringResource(words.done)
                    state.phase == CapturePhase.CAPTURING -> stringResource(R.string.rs_scanner_capturing)
                    state.guidance != null -> stringResource(state.guidance!!)
                    vm.mode.extractText && state.textBoxes.size >= 3 && state.phase != CapturePhase.NEXT_PAGE ->
                        pluralStringResource(R.plurals.rs_scanner_text_lines_found, state.textBoxes.size, state.textBoxes.size)
                    vm.processingCaptures > 0 && state.phase != CapturePhase.HOLD_STEADY -> stringResource(R.string.rs_scanner_processing)
                    else -> when (state.phase) {
                        CapturePhase.SEARCHING -> when {
                            vm.autoCapture && state.progress > 0f -> stringResource(R.string.rs_scanner_hold_still_capture)
                            vm.guideFrame != null -> stringResource(words.place)
                            else -> stringResource(words.looking)
                        }
                        CapturePhase.TOO_SMALL -> stringResource(R.string.rs_scanner_move_closer)
                        CapturePhase.HOLD_STEADY -> stringResource(if (vm.autoCapture) R.string.rs_scanner_hold_steady else R.string.rs_scanner_tap_shutter)
                        CapturePhase.CAPTURING -> stringResource(R.string.rs_scanner_capturing)
                        CapturePhase.NEXT_PAGE -> stringResource(words.next)
                    }
                }
                if (hint != null) {
                    if (vm.mode == ScanMode.BOOK && !qrMode) {
                        // Book mode is used with the phone held sideways: turn the text with it.
                        HintChip(hint, Modifier.align(Alignment.Center).rotate(90f))
                    } else {
                        HintChip(hint, Modifier.align(Alignment.TopCenter).padding(top = 14.dp))
                    }
                }
                if (granted && !qrMode && vm.mode == ScanMode.ID_CARD) {
                    val step = when (vm.idStep) {
                        ScannerViewModel.IdStep.FRONT -> stringResource(R.string.rs_scanner_id_front_step)
                        ScannerViewModel.IdStep.BACK -> stringResource(R.string.rs_scanner_id_back_step)
                        ScannerViewModel.IdStep.DONE -> stringResource(R.string.rs_scanner_id_both_captured)
                    }
                    Text(
                        step,
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 58.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(ScanColors.Accent)
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
                toast?.let { HintChip(it, Modifier.align(Alignment.Center)) }
                // Smart filter on: show what it picked for the latest page.
                if (granted && !qrMode && vm.aiAssist) {
                    val last = vm.pages.lastOrNull()
                    val what = when {
                        last == null -> stringResource(R.string.rs_scanner_smart_waiting)
                        last.smartLabel != null -> last.smartLabel!!
                        else -> stringResource(R.string.rs_scanner_smart_choosing)
                    }
                    Row(
                        Modifier
                            .align(Alignment.TopStart)
                            .padding(start = 8.dp, top = if (vm.mode == ScanMode.ID_CARD) 92.dp else 58.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(ScanColors.Bar.copy(alpha = 0.8f))
                            .border(1.dp, ScanColors.Marigold, RoundedCornerShape(10.dp))
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.AutoAwesome, null, tint = ScanColors.Marigold, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.size(6.dp))
                        Text(stringResource(R.string.rs_scanner_smart_chip, what), color = Color.White, fontSize = 12.sp)
                    }
                }
                if (granted && !qrMode) {
                    ToolRail(
                        flash = flash,
                        onFlash = {
                            flash = flash.next()
                            toast = context.getString(
                                when (flash) {
                                    Flash.AUTO -> R.string.rs_scanner_flash_auto
                                    Flash.ON -> R.string.rs_scanner_flash_on
                                    Flash.OFF -> R.string.rs_scanner_flash_off
                                }
                            )
                        },
                        autoCapture = vm.autoCapture,
                        onToggleAuto = {
                            vm.autoCapture = !vm.autoCapture
                            toast = context.getString(if (vm.autoCapture) R.string.rs_scanner_auto_capture_on else R.string.rs_scanner_auto_capture_off)
                        },
                        smartFilter = vm.aiAssist,
                        onToggleSmart = {
                            vm.aiAssist = !vm.aiAssist
                            toast = context.getString(if (vm.aiAssist) R.string.rs_scanner_smart_on else R.string.rs_scanner_smart_off)
                        },
                        frame = vm.orientation,
                        onFrame = {
                            vm.orientation = it
                            toast = context.getString(
                                when (it) {
                                    com.rskusum.scanner.data.FrameOrientation.PORTRAIT -> R.string.rs_scanner_frame_portrait
                                    com.rskusum.scanner.data.FrameOrientation.LANDSCAPE -> R.string.rs_scanner_frame_landscape
                                    com.rskusum.scanner.data.FrameOrientation.FREE -> R.string.rs_scanner_frame_free
                                }
                            )
                        },
                        modifier = Modifier.align(Alignment.CenterEnd).padding(end = 8.dp),
                    )
                }
                if (granted) Diagnostics(analyzer, Modifier.align(Alignment.BottomEnd).padding(8.dp))

                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.White.copy(alpha = flashOverlay.value))
                )
            }
        }

        ModeCarousel(vm.options.modes, vm.mode, onSelect = { vm.selectMode(it) })

        BottomControls(
            progress = if (vm.autoCapture && !qrMode) state.progress else 0f,
            capturing = capturing,
            autoCapture = vm.autoCapture,
            flash = flash,
            thumbnail = vm.pages.lastOrNull(),
            pageCount = vm.pages.size,
            processing = vm.processingCaptures > 0,
            onGallery = if (vm.options.galleryImport) ({
                galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }) else null,
            onShutter = {
                when {
                    qrMode -> Unit
                    !vm.captureAllowed -> toast = context.getString(R.string.rs_scanner_capture_complete)
                    else -> capture(manual = true)
                }
            },
            onThumbnail = { if (vm.pages.isNotEmpty() || vm.processingCaptures > 0) onOpenReview() },
        )
    }

    qrText?.let { text -> QrResultDialog(text, onDismiss = { qrText = null }) }
}

// ------------------------------------------------------------------------------------------
// Camera

@Composable
private fun CameraPreview(holder: CameraHolder, analyzer: DocumentAnalyzer, flashMode: Int) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var focusKey by remember { mutableIntStateOf(0) }

    DisposableEffect(lifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        future.addListener({
            val p = future.get()
            provider = p
            fun selector(target: Size?, rule: Int) = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .apply { if (target != null) setResolutionStrategy(ResolutionStrategy(target, rule)) }
                .build()

            val preview = Preview.Builder()
                .setResolutionSelector(selector(null, 0))
                .build()
            preview.setSurfaceProvider(previewView.surfaceProvider)

            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                // Highest 4:3 resolution the camera offers: sharper text in the final scan.
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                        .build()
                )
                .setFlashMode(flashMode)
                .build()

            val analysis = ImageAnalysis.Builder()
                // Higher than needed on purpose: the detector area-downsamples it, which averages
                // away sensor noise that otherwise hides faint paper edges.
                .setResolutionSelector(selector(Size(960, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                // Colour frames: the learned edge model needs RGB, not just luminance.
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(analysisExecutor, analyzer)

            try {
                p.unbindAll()
                holder.camera = p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture, analysis)
                holder.capture = capture
                holder.previewView = previewView
            } catch (t: Throwable) {
                android.util.Log.e("CameraScreen", "bind failed", t)
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            provider?.unbindAll()
            holder.capture = null
            holder.camera = null
        }
    }
    DisposableEffect(Unit) { onDispose { analysisExecutor.shutdown() } }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        val cam = holder.camera ?: return@detectTapGestures
                        val point = previewView.meteringPointFactory.createPoint(offset.x, offset.y)
                        val action = FocusMeteringAction.Builder(point)
                            .setAutoCancelDuration(3, TimeUnit.SECONDS)
                            .build()
                        cam.cameraControl.startFocusAndMetering(action)
                        focusPoint = offset
                        focusKey++
                    }
                },
        )
        focusPoint?.let { FocusRing(it, focusKey) { focusPoint = null } }
    }
}

@Composable
private fun FocusRing(at: Offset, key: Int, onDone: () -> Unit) {
    val anim = remember(key) { Animatable(1.4f) }
    LaunchedEffect(key) {
        anim.animateTo(1f, tween(250))
        kotlinx.coroutines.delay(700)
        onDone()
    }
    Canvas(Modifier.fillMaxSize()) {
        drawCircle(Color.White, radius = 32.dp.toPx() * anim.value, center = at, style = Stroke(2.dp.toPx()))
    }
}

// ------------------------------------------------------------------------------------------
// Overlays

@Composable
private fun QuadOverlay(quad: Quad?, phase: CapturePhase) {
    val alpha by animateFloatAsState(if (quad != null) 1f else 0f, label = "quadAlpha")
    var last by remember { mutableStateOf<Quad?>(null) }
    if (quad != null) last = quad
    val q = last ?: return
    val color = if (phase == CapturePhase.NEXT_PAGE) Color(0xFF34C759) else ScanColors.Accent
    Canvas(Modifier.fillMaxSize()) {
        val pts = q.points.map { Offset(it.x * size.width, it.y * size.height) }
        val path = Path().apply {
            moveTo(pts[0].x, pts[0].y)
            for (i in 1 until 4) lineTo(pts[i].x, pts[i].y)
            close()
        }
        drawPath(path, color.copy(alpha = 0.22f * alpha))
        drawPath(path, color.copy(alpha = alpha), style = Stroke(3.dp.toPx(), join = StrokeJoin.Round))
        pts.forEach {
            drawCircle(Color.White.copy(alpha = alpha), 7.dp.toPx(), it)
            drawCircle(color.copy(alpha = alpha), 5.dp.toPx(), it)
        }
    }
}

/** Mode-specific wording for the camera guidance. */
private class ModeWords(
    @androidx.annotation.StringRes val place: Int,
    @androidx.annotation.StringRes val looking: Int,
    @androidx.annotation.StringRes val next: Int,
    @androidx.annotation.StringRes val done: Int = R.string.rs_scanner_scan_complete_review,
)

private fun modeWords(mode: ScanMode, idStep: ScannerViewModel.IdStep): ModeWords = when (mode) {
    ScanMode.DOCUMENT -> ModeWords(R.string.rs_scanner_doc_place, R.string.rs_scanner_doc_looking, R.string.rs_scanner_doc_next)
    ScanMode.WHITEBOARD -> ModeWords(R.string.rs_scanner_wb_place, R.string.rs_scanner_wb_looking, R.string.rs_scanner_wb_next)
    ScanMode.BOOK -> ModeWords(R.string.rs_scanner_book_place, R.string.rs_scanner_book_looking, R.string.rs_scanner_book_next)
    ScanMode.BOOK_COVER -> ModeWords(R.string.rs_scanner_cover_place, R.string.rs_scanner_cover_looking, R.string.rs_scanner_cover_next)
    ScanMode.BUSINESS_CARD -> ModeWords(R.string.rs_scanner_bcard_place, R.string.rs_scanner_bcard_looking, R.string.rs_scanner_bcard_next)
    ScanMode.AI_TEXT -> ModeWords(R.string.rs_scanner_text_place, R.string.rs_scanner_text_looking, R.string.rs_scanner_text_next)
    ScanMode.ID_CARD -> when (idStep) {
        ScannerViewModel.IdStep.FRONT -> ModeWords(R.string.rs_scanner_id_front_place, R.string.rs_scanner_id_front_looking, R.string.rs_scanner_id_flip)
        ScannerViewModel.IdStep.BACK -> ModeWords(R.string.rs_scanner_id_back_place, R.string.rs_scanner_id_back_looking, R.string.rs_scanner_id_flip)
        ScannerViewModel.IdStep.DONE -> ModeWords(R.string.rs_scanner_id_done, R.string.rs_scanner_id_done, R.string.rs_scanner_id_done, R.string.rs_scanner_id_done)
    }
}

/** AI Text mode: translucent teal bars over the text lines found in the live preview. */
@Composable
private fun TextBoxesOverlay(boxes: List<com.rskusum.scanner.vision.NRect>) {
    Canvas(Modifier.fillMaxSize()) {
        val r = 3.dp.toPx()
        boxes.forEach { b ->
            val l = b.l * size.width
            val t = b.t * size.height
            val w = (b.r - b.l) * size.width
            val h = (b.b - b.t) * size.height
            drawRoundRect(
                ScanColors.AccentBright.copy(alpha = 0.28f),
                topLeft = Offset(l, t),
                size = androidx.compose.ui.geometry.Size(w, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(r),
            )
            drawRoundRect(
                ScanColors.AccentBright.copy(alpha = 0.8f),
                topLeft = Offset(l, t),
                size = androidx.compose.ui.geometry.Size(w, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(r),
                style = Stroke(1.dp.toPx()),
            )
        }
    }
}

/**
 * Fixed document-shaped guide frame. Outside is dimmed; corner brackets turn green when the
 * live detection lines up with the frame. (The detection outline itself is only drawn in Free mode.)
 * [spine]: Book mode's dashed centre line to align the book's spine with.
 */
@Composable
private fun GuideFrameOverlay(frame: Quad, aligned: Boolean, spine: Boolean = false) {
    val color = if (aligned) Color(0xFF34C759) else Color.White
    Canvas(Modifier.fillMaxSize()) {
        val l = frame.tl.x * size.width
        val t = frame.tl.y * size.height
        val r = frame.br.x * size.width
        val b = frame.br.y * size.height
        val outside = Path().apply {
            fillType = androidx.compose.ui.graphics.PathFillType.EvenOdd
            addRect(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height))
            addRoundRect(androidx.compose.ui.geometry.RoundRect(l, t, r, b, 12.dp.toPx(), 12.dp.toPx()))
        }
        drawPath(outside, Color.Black.copy(alpha = 0.45f))
        drawRoundRect(
            color.copy(alpha = 0.5f),
            topLeft = Offset(l, t),
            size = androidx.compose.ui.geometry.Size(r - l, b - t),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(12.dp.toPx()),
            style = Stroke(1.5.dp.toPx()),
        )
        val c = 34.dp.toPx()
        val sw = 5.dp.toPx()
        listOf(
            Triple(Offset(l, t), 1f, 1f), Triple(Offset(r, t), -1f, 1f),
            Triple(Offset(r, b), -1f, -1f), Triple(Offset(l, b), 1f, -1f),
        ).forEach { (p, sx, sy) ->
            drawLine(color, p, p + Offset(c * sx, 0f), sw, StrokeCap.Round)
            drawLine(color, p, p + Offset(0f, c * sy), sw, StrokeCap.Round)
        }
        if (spine) {
            // The spine splits the frame across its long side: horizontal in a tall frame (phone
            // held across the book), vertical in a wide one.
            val dash = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(14.dp.toPx(), 10.dp.toPx()))
            val (p1, p2) = if (b - t > r - l) {
                val y = (t + b) / 2
                Offset(l, y) to Offset(r, y)
            } else {
                val x = (l + r) / 2
                Offset(x, t) to Offset(x, b)
            }
            drawLine(color, p1, p2, 2.5.dp.toPx(), pathEffect = dash)
            drawCircle(color, 5.dp.toPx(), p1)
            drawCircle(color, 5.dp.toPx(), p2)
        }
    }
}

/**
 * Book mode guide: the phone is held sideways across the open book, the spine
 * lies on a dashed line across the preview, the left page shows as "1" (top) and the right page as
 * "2" (bottom). Numbers are drawn sideways so they read correctly with the phone turned.
 */
@Composable
private fun BookOverlay(dividerY: Float, swapped: Boolean) {
    val guide = ScanColors.Marigold
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            val y = dividerY * size.height
            // Spine guide: thin solid line with a soft glow band and end caps.
            drawLine(guide.copy(alpha = 0.25f), Offset(0f, y), Offset(size.width, y), 12.dp.toPx())
            drawLine(guide, Offset(0f, y), Offset(size.width, y), 2.dp.toPx())
            drawCircle(guide, 5.dp.toPx(), Offset(10.dp.toPx(), y))
            drawCircle(guide, 5.dp.toPx(), Offset(size.width - 10.dp.toPx(), y))
        }
        val top = stringResource(if (swapped) R.string.rs_scanner_page_2 else R.string.rs_scanner_page_1)
        val bottom = stringResource(if (swapped) R.string.rs_scanner_page_1 else R.string.rs_scanner_page_2)
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
            val h = maxHeight
            // Page badges, turned sideways so they read correctly with the phone held across the book.
            PageBadge(top, Modifier.align(Alignment.TopCenter).offset(y = h * dividerY / 2 - 14.dp).rotate(90f))
            PageBadge(bottom, Modifier.align(Alignment.TopCenter).offset(y = h * dividerY + h * (1 - dividerY) / 2 - 14.dp).rotate(90f))
        }
    }
}

/** Free-mode book guide: detected spine line across the spread + PAGE 1 / PAGE 2 on the halves. */
@Composable
private fun FreeSpineOverlay(spine: com.rskusum.scanner.camera.SpineGuide, swapped: Boolean) {
    val guide = ScanColors.Marigold
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            val a = Offset(spine.a.x * size.width, spine.a.y * size.height)
            val b = Offset(spine.b.x * size.width, spine.b.y * size.height)
            drawLine(guide.copy(alpha = 0.3f), a, b, 12.dp.toPx(), cap = StrokeCap.Round)
            drawLine(guide, a, b, 2.5.dp.toPx(), cap = StrokeCap.Round)
            drawCircle(guide, 5.dp.toPx(), a)
            drawCircle(guide, 5.dp.toPx(), b)
        }
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
            val w = maxWidth; val h = maxHeight
            val one = if (swapped) spine.second else spine.first
            val two = if (swapped) spine.first else spine.second
            PageBadge(stringResource(R.string.rs_scanner_page_1), Modifier.offset(x = w * one.x - 34.dp, y = h * one.y - 14.dp).rotate(90f))
            PageBadge(stringResource(R.string.rs_scanner_page_2), Modifier.offset(x = w * two.x - 34.dp, y = h * two.y - 14.dp).rotate(90f))
        }
    }
}

@Composable
private fun PageBadge(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = Color.Black,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(ScanColors.Marigold.copy(alpha = 0.92f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** "Swap pages" chip: swaps which half of the spread becomes page 1. */
@Composable
private fun BookSwapButton(swapped: Boolean, onSwap: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(ScanColors.Bar.copy(alpha = 0.72f))
            .border(1.dp, ScanColors.Marigold, RoundedCornerShape(12.dp))
            .clickable(onClick = onSwap)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.SwapVert, contentDescription = null, tint = ScanColors.Marigold, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(4.dp))
        Text(stringResource(if (swapped) R.string.rs_scanner_pages_swapped else R.string.rs_scanner_swap_pages), color = Color.White, fontSize = 12.sp)
    }
}

@Composable
private fun QrFrame() {
    Canvas(Modifier.fillMaxSize()) {
        val s = size.minDimension * 0.62f
        val l = (size.width - s) / 2
        val t = (size.height - s) / 2
        val c = 28.dp.toPx()
        val sw = 4.dp.toPx()
        val corners = listOf(
            Offset(l, t) to Pair(Offset(c, 0f), Offset(0f, c)),
            Offset(l + s, t) to Pair(Offset(-c, 0f), Offset(0f, c)),
            Offset(l + s, t + s) to Pair(Offset(-c, 0f), Offset(0f, -c)),
            Offset(l, t + s) to Pair(Offset(c, 0f), Offset(0f, -c)),
        )
        corners.forEach { (p, d) ->
            drawLine(Color.White, p, p + d.first, sw, StrokeCap.Round)
            drawLine(Color.White, p, p + d.second, sw, StrokeCap.Round)
        }
    }
}

@Composable
private fun HintChip(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = Color.White,
        fontSize = 14.sp,
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(ScanColors.Bar.copy(alpha = 0.78f))
            .border(1.dp, ScanColors.Accent, RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/**
 * Small status line: analyzed frames per second and frame size, or the error that is stopping
 * detection. Makes "nothing is detected" distinguishable from "the detector isn't running".
 */
@Composable
private fun Diagnostics(analyzer: DocumentAnalyzer, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var text by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }
    LaunchedEffect(analyzer) {
        var lastCount = analyzer.framesAnalyzed
        var ticks = 0
        while (true) {
            kotlinx.coroutines.delay(1000)
            ticks++
            val count = analyzer.framesAnalyzed
            val fps = count - lastCount
            lastCount = count
            val err = com.rskusum.scanner.RsScanner.openCvError ?: analyzer.lastError
                ?: com.rskusum.scanner.vision.EdgeModel.loadError
            isError = err != null || (fps == 0L && ticks >= 3 && !analyzer.paused && !analyzer.qrMode)
            text = when {
                err != null -> context.getString(R.string.rs_scanner_detector_error, err)
                isError -> context.getString(R.string.rs_scanner_no_frames)
                else -> "$fps fps · ${analyzer.frameSize} · ${analyzer.lastSource}"
            }
        }
    }
    if (text.isNotEmpty()) {
        Text(
            text,
            color = if (isError) Color(0xFFFF6B6B) else Color.White.copy(alpha = 0.55f),
            fontSize = if (isError) 13.sp else 10.sp,
            modifier = modifier
                .clip(RoundedCornerShape(6.dp))
                .background(Color.Black.copy(alpha = if (isError) 0.75f else 0.3f))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun PermissionRationale(onGrant: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.rs_scanner_camera_needed), color = Color.White)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onGrant) { Text(stringResource(R.string.rs_scanner_allow_camera)) }
    }
}

// ------------------------------------------------------------------------------------------
// Chrome

@Composable
private fun TopBar(qrMode: Boolean, onHome: (() -> Unit)?, onQr: (() -> Unit)?) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(ScanColors.Bar)
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Brand (fixed, deliberately not a string resource so it can't be overridden).
        Column {
            Text("Scan", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(BRAND_LINE, color = ScanColors.AccentBright, fontSize = 11.sp)
        }
        Spacer(Modifier.weight(1f))
        if (onQr != null) {
            IconButton(onClick = onQr) {
                Icon(
                    Icons.Outlined.QrCodeScanner,
                    contentDescription = stringResource(R.string.rs_scanner_qr_code),
                    tint = if (qrMode) ScanColors.AccentBright else Color.White,
                    modifier = Modifier.size(26.dp),
                )
            }
        }
        if (onHome != null) {
            IconButton(onClick = onHome) {
                Icon(Icons.Outlined.FolderOpen, contentDescription = stringResource(R.string.rs_scanner_my_scans), tint = Color.White, modifier = Modifier.size(26.dp))
            }
        }
    }
}

/**
 * Vertical tool rail on the right edge of the preview: flash, auto capture, smart filter and the
 * frame shape, each a round translucent button.
 */
@Composable
private fun ToolRail(
    flash: Flash,
    onFlash: () -> Unit,
    autoCapture: Boolean,
    onToggleAuto: () -> Unit,
    smartFilter: Boolean,
    onToggleSmart: () -> Unit,
    frame: com.rskusum.scanner.data.FrameOrientation?,
    onFrame: (com.rskusum.scanner.data.FrameOrientation) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(ScanColors.Bar.copy(alpha = 0.62f))
            .padding(vertical = 6.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        RailButton(selected = flash != Flash.OFF, label = stringResource(when (flash) { Flash.AUTO -> R.string.rs_scanner_auto; Flash.ON -> R.string.rs_scanner_on; Flash.OFF -> R.string.rs_scanner_off }), onClick = onFlash) {
            Icon(
                when (flash) {
                    Flash.AUTO -> Icons.Filled.FlashAuto
                    Flash.ON -> Icons.Filled.FlashOn
                    Flash.OFF -> Icons.Filled.FlashOff
                },
                stringResource(R.string.rs_scanner_flash), tint = Color.White, modifier = Modifier.size(22.dp),
            )
        }
        RailButton(selected = autoCapture, label = stringResource(R.string.rs_scanner_auto), onClick = onToggleAuto) {
            Text("A", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Black)
        }
        RailButton(selected = smartFilter, label = stringResource(R.string.rs_scanner_smart), onClick = onToggleSmart) {
            Icon(Icons.Filled.AutoAwesome, stringResource(R.string.rs_scanner_smart_filter), tint = Color.White, modifier = Modifier.size(20.dp))
        }
        if (frame != null) {
            Box(Modifier.padding(vertical = 2.dp).size(width = 24.dp, height = 1.dp).background(Color.White.copy(alpha = 0.25f)))
            listOf(
                com.rskusum.scanner.data.FrameOrientation.PORTRAIT to Icons.Outlined.CropPortrait,
                com.rskusum.scanner.data.FrameOrientation.LANDSCAPE to Icons.Outlined.CropLandscape,
                com.rskusum.scanner.data.FrameOrientation.FREE to Icons.Outlined.CropFree,
            ).forEach { (o, icon) ->
                RailButton(selected = o == frame, label = null, onClick = { onFrame(o) }) {
                    Icon(icon, contentDescription = o.name.lowercase(), tint = Color.White, modifier = Modifier.size(22.dp))
                }
            }
        }
    }
}

@Composable
private fun RailButton(selected: Boolean, label: String?, onClick: () -> Unit, content: @Composable () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (selected) ScanColors.Accent else Color.Transparent)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { content() }
        if (label != null) Text(label, color = Color.White.copy(alpha = 0.8f), fontSize = 9.sp)
    }
}

/** Horizontal swipe on the preview switches mode. */
private fun Modifier.pointerModeSwipe(vm: ScannerViewModel): Modifier = pointerInput(Unit) {
    var total = 0f
    detectHorizontalDragGestures(
        onDragStart = { total = 0f },
        onDragEnd = {
            val modes = vm.options.modes
            val i = modes.indexOf(vm.mode)
            if (total < -120 && i < modes.lastIndex) vm.selectMode(modes[i + 1])
            if (total > 120 && i > 0) vm.selectMode(modes[i - 1])
        },
    ) { _, dx -> total += dx }
}

@Composable
private fun ModeCarousel(modes: List<ScanMode>, selected: ScanMode, onSelect: (ScanMode) -> Unit) {
    // Mode chips: icon + label in rounded tiles, scrollable; the selected one is filled.
    val scroll = androidx.compose.foundation.rememberScrollState()
    Row(
        Modifier
            .fillMaxWidth()
            .background(ScanColors.Bar)
            .horizontalScroll(scroll)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        modes.forEach { m ->
            val isSel = m == selected
            Row(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isSel) ScanColors.Accent else ScanColors.SurfaceHigh)
                    .border(1.dp, if (isSel) ScanColors.AccentBright else Color.Transparent, RoundedCornerShape(12.dp))
                    .clickable { onSelect(m) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(modeIcon(m), contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text(stringResource(m.labelRes), color = Color.White, fontSize = 14.sp, fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

private fun modeIcon(m: ScanMode) = when (m) {
    ScanMode.WHITEBOARD -> Icons.Outlined.Dashboard
    ScanMode.BOOK -> Icons.Outlined.ImportContacts
    ScanMode.BOOK_COVER -> Icons.Outlined.Book
    ScanMode.DOCUMENT -> Icons.Outlined.Description
    ScanMode.ID_CARD -> Icons.Outlined.Badge
    ScanMode.AI_TEXT -> Icons.Filled.AutoAwesome
    ScanMode.BUSINESS_CARD -> Icons.Outlined.ContactPage
}

@Composable
private fun BottomControls(
    progress: Float,
    capturing: Boolean,
    autoCapture: Boolean,
    flash: Flash,
    thumbnail: com.rskusum.scanner.data.Page?,
    pageCount: Int,
    processing: Boolean,
    onGallery: (() -> Unit)?,
    onShutter: () -> Unit,
    onThumbnail: () -> Unit,
) {
    // Pages on the left, shutter in the middle, gallery on the right.
    Box(
        Modifier
            .fillMaxWidth()
            .background(ScanColors.Bar)
            .padding(horizontal = 28.dp, vertical = 16.dp),
    ) {
        Box(Modifier.align(Alignment.CenterStart)) {
            ThumbnailStack(thumbnail, pageCount, processing, onThumbnail)
        }
        Box(Modifier.align(Alignment.Center)) {
            Shutter(progress, capturing, autoCapture, onShutter)
        }
        if (onGallery != null) {
            Column(
                Modifier
                    .align(Alignment.CenterEnd)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onGallery)
                    .padding(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Outlined.PhotoLibrary, stringResource(R.string.rs_scanner_import_gallery), tint = Color.White, modifier = Modifier.size(28.dp))
                Text(stringResource(R.string.rs_scanner_import), color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp)
            }
        }
    }
}

/**
 * Shutter: a teal disc with a camera glyph inside a thin track ring. With auto capture on, the
 * ring fills clockwise as the page holds steady; while capturing the disc shrinks briefly.
 */
@Composable
private fun Shutter(progress: Float, capturing: Boolean, autoCapture: Boolean, onClick: () -> Unit) {
    val press by animateFloatAsState(if (capturing) 0.78f else 1f, tween(120), label = "shutterPress")
    val shownProgress by animateFloatAsState(progress, tween(120), label = "shutterProgress")
    Box(
        Modifier
            .size(80.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val track = 3.dp.toPx()
            val inset = track / 2
            val arcSize = androidx.compose.ui.geometry.Size(size.width - track, size.height - track)
            drawCircle(Color.White.copy(alpha = 0.25f), size.minDimension / 2 - inset, style = Stroke(track))
            if (shownProgress > 0f) {
                drawArc(
                    ScanColors.AccentBright,
                    startAngle = -90f,
                    sweepAngle = 360f * shownProgress,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(track, cap = StrokeCap.Round),
                )
            }
            drawCircle(ScanColors.Accent, (size.minDimension / 2 - 9.dp.toPx()) * press)
        }
        Icon(Icons.Filled.CameraAlt, contentDescription = stringResource(R.string.rs_scanner_capture), tint = Color.White, modifier = Modifier.size(28.dp).scale(press))
        if (autoCapture) {
            Text(
                stringResource(R.string.rs_scanner_auto_badge),
                color = Color.White,
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp),
            )
        }
    }
}

@Composable
private fun ThumbnailStack(page: com.rskusum.scanner.data.Page?, count: Int, processing: Boolean, onClick: () -> Unit) {
    val pulse = remember { Animatable(1f) }
    LaunchedEffect(count) {
        if (count > 0) {
            pulse.snapTo(1.25f)
            pulse.animateTo(1f, tween(300))
        }
    }
    Box(Modifier.size(width = 60.dp, height = 60.dp).scale(pulse.value)) {
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .size(52.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(ScanColors.SurfaceHigh)
                .border(2.dp, ScanColors.Accent, RoundedCornerShape(14.dp))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            val thumb = page?.thumbnail
            if (thumb != null) {
                Image(thumb, contentDescription = stringResource(R.string.rs_scanner_scanned_pages), contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            if (processing || (page != null && thumb == null)) {
                CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
            }
        }
        if (count > 0) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .size(22.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(ScanColors.Marigold),
                contentAlignment = Alignment.Center,
            ) {
                Text("$count", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ------------------------------------------------------------------------------------------
// QR

@Composable
private fun QrResultDialog(text: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val isUrl = remember(text) { text.startsWith("http://", true) || text.startsWith("https://", true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rs_scanner_qr_code)) },
        text = { Text(text) },
        confirmButton = {
            if (isUrl) {
                TextButton(onClick = {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(text))) }
                    onDismiss()
                }) { Text(stringResource(R.string.rs_scanner_open)) }
            } else {
                TextButton(onClick = { copy(context, text); onDismiss() }) { Text(stringResource(R.string.rs_scanner_copy)) }
            }
        },
        dismissButton = {
            Row {
                if (isUrl) TextButton(onClick = { copy(context, text); onDismiss() }) { Text(stringResource(R.string.rs_scanner_copy)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.rs_scanner_close)) }
            }
        },
    )
}

private fun copy(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("QR", text))
    Toast.makeText(context, context.getString(R.string.rs_scanner_copied), Toast.LENGTH_SHORT).show()
}
