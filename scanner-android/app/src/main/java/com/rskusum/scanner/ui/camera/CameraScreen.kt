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
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.CropFree
import androidx.compose.material.icons.outlined.CropLandscape
import androidx.compose.material.icons.outlined.CropPortrait
import androidx.compose.material.icons.outlined.DocumentScanner
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
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rskusum.scanner.ScannerViewModel
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
fun CameraScreen(vm: ScannerViewModel, onOpenReview: () -> Unit, onHome: () -> Unit) {
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
                    if (bmp != null) vm.addPhoto(bmp, hint, frame, deviceRotation.get()) else toast = "Capture failed, try again"
                }
            }

            override fun onError(exception: ImageCaptureException) {
                mainExecutor.execute {
                    capturing = false
                    vm.tracker.onCaptured(false)
                    toast = "Capture failed: ${exception.imageCaptureError}"
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
        analyzer.paused = qrText != null
        analyzer.frame = if (qrMode) null else vm.guideFrame
        analyzer.knownPages = vm.sessionFingerprints
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
            onQr = {
                qrMode = !qrMode
                toast = if (qrMode) "QR code scanner" else "Document scanner"
            },
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
                    when {
                        qrMode -> QrFrame()
                        guide != null -> GuideFrameOverlay(guide, state.aligned, spine = vm.mode == ScanMode.BOOK)
                        else -> QuadOverlay(state.quad, state.phase)
                    }
                } else {
                    PermissionRationale { permissionLauncher.launch(Manifest.permission.CAMERA) }
                }

                val words = modeWords(vm.mode, vm.idStep)
                val hint = when {
                    !granted -> null
                    qrMode -> "Point at a QR code"
                    !vm.captureAllowed -> words.done
                    state.phase == CapturePhase.CAPTURING -> "Capturing…"
                    state.guidance != null -> state.guidance
                    vm.processingCaptures > 0 && state.phase != CapturePhase.HOLD_STEADY -> "Processing…"
                    else -> when (state.phase) {
                        CapturePhase.SEARCHING -> when {
                            vm.autoCapture && state.progress > 0f -> "Hold still to capture"
                            vm.guideFrame != null -> words.place
                            else -> words.looking
                        }
                        CapturePhase.TOO_SMALL -> "Move closer"
                        CapturePhase.HOLD_STEADY -> if (vm.autoCapture) "Hold steady" else "Tap the shutter to capture"
                        CapturePhase.CAPTURING -> "Capturing…"
                        CapturePhase.NEXT_PAGE -> words.next
                    }
                }
                if (hint != null) HintChip(hint, Modifier.align(Alignment.TopCenter).padding(top = 14.dp))
                if (granted && !qrMode && vm.mode == ScanMode.ID_CARD) {
                    val step = when (vm.idStep) {
                        ScannerViewModel.IdStep.FRONT -> "Front side  1 / 2"
                        ScannerViewModel.IdStep.BACK -> "Back side  2 / 2"
                        ScannerViewModel.IdStep.DONE -> "Both sides captured"
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
                if (granted && !qrMode) {
                    OrientationToggle(
                        selected = vm.orientation,
                        onSelect = {
                            vm.orientation = it
                            toast = when (it) {
                                com.rskusum.scanner.data.FrameOrientation.PORTRAIT -> "Portrait frame"
                                com.rskusum.scanner.data.FrameOrientation.LANDSCAPE -> "Landscape frame"
                                com.rskusum.scanner.data.FrameOrientation.FREE -> "Free detection"
                            }
                        },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
                    )
                }
                if (granted) Diagnostics(analyzer, Modifier.align(Alignment.BottomStart).padding(8.dp))

                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.White.copy(alpha = flashOverlay.value))
                )
            }

            ScanAiPill(
                aiAssist = vm.aiAssist,
                onChange = {
                    vm.aiAssist = it
                    toast = if (it) "AI assist: best filter picked for each page" else "Scan"
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 18.dp),
            )
        }

        ModeCarousel(vm.mode, onSelect = { vm.selectMode(it) })

        BottomControls(
            progress = if (vm.autoCapture && !qrMode) state.progress else 0f,
            capturing = capturing,
            autoCapture = vm.autoCapture,
            flash = flash,
            thumbnail = vm.pages.lastOrNull(),
            pageCount = vm.pages.size,
            processing = vm.processingCaptures > 0,
            onGallery = {
                galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            onToggleAuto = {
                vm.autoCapture = !vm.autoCapture
                toast = if (vm.autoCapture) "Auto-capture on" else "Auto-capture off"
            },
            onShutter = {
                when {
                    qrMode -> Unit
                    !vm.captureAllowed -> toast = "ID card complete - tap the thumbnail to review"
                    else -> capture(manual = true)
                }
            },
            onFlash = { flash = flash.next() },
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
private class ModeWords(val place: String, val looking: String, val next: String, val done: String)

private fun modeWords(mode: ScanMode, idStep: ScannerViewModel.IdStep): ModeWords = when (mode) {
    ScanMode.DOCUMENT -> ModeWords("Place the page inside the frame", "Looking for document", "Ready for next page", "")
    ScanMode.WHITEBOARD -> ModeWords("Fit the whiteboard inside the frame", "Looking for whiteboard", "Ready for the next board", "")
    ScanMode.BOOK -> ModeWords(
        "Hold the phone across the open book - spine on the centre line", "Looking for an open book",
        "Turn the page", "",
    )
    ScanMode.BOOK_COVER -> ModeWords("Fit the book cover inside the frame", "Looking for the book cover", "Ready for the next cover", "")
    ScanMode.BUSINESS_CARD -> ModeWords("Place the business card inside the frame", "Looking for a business card", "Ready for the next card", "")
    ScanMode.ID_CARD -> when (idStep) {
        ScannerViewModel.IdStep.FRONT -> ModeWords("Place the FRONT of the ID card in the frame", "Looking for the ID card front", "Flip the card", "")
        ScannerViewModel.IdStep.BACK -> ModeWords("Flip the card - place the BACK side in the frame", "Looking for the ID card back", "Flip the card", "")
        ScannerViewModel.IdStep.DONE -> ModeWords("", "", "", "ID card complete - tap the thumbnail to review")
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

@Composable
private fun OrientationToggle(
    selected: com.rskusum.scanner.data.FrameOrientation,
    onSelect: (com.rskusum.scanner.data.FrameOrientation) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(22.dp))
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        listOf(
            com.rskusum.scanner.data.FrameOrientation.PORTRAIT to Icons.Outlined.CropPortrait,
            com.rskusum.scanner.data.FrameOrientation.LANDSCAPE to Icons.Outlined.CropLandscape,
            com.rskusum.scanner.data.FrameOrientation.FREE to Icons.Outlined.CropFree,
        ).forEach { (o, icon) ->
            val isSel = o == selected
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(if (isSel) ScanColors.Accent else Color.Transparent)
                    .clickable { onSelect(o) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = o.name.lowercase(), tint = Color.White, modifier = Modifier.size(24.dp))
            }
        }
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
            .clip(RoundedCornerShape(20.dp))
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/**
 * Small status line: analyzed frames per second and frame size, or the error that is stopping
 * detection. Makes "nothing is detected" distinguishable from "the detector isn't running".
 */
@Composable
private fun Diagnostics(analyzer: DocumentAnalyzer, modifier: Modifier = Modifier) {
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
            val err = com.rskusum.scanner.ScannerApp.openCvError ?: analyzer.lastError
                ?: com.rskusum.scanner.vision.EdgeModel.loadError
            isError = err != null || (fps == 0L && ticks >= 3 && !analyzer.paused && !analyzer.qrMode)
            text = when {
                err != null -> "Detector error: $err"
                isError -> "No camera frames reaching the detector"
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
        Text("Camera access is needed to scan documents.", color = Color.White)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onGrant) { Text("Allow camera") }
    }
}

// ------------------------------------------------------------------------------------------
// Chrome

@Composable
private fun TopBar(qrMode: Boolean, onHome: () -> Unit, onQr: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .background(ScanColors.Bar)
            .padding(horizontal = 8.dp)
    ) {
        IconButton(onClick = onHome, modifier = Modifier.align(Alignment.CenterStart)) {
            Icon(Icons.Filled.Home, contentDescription = "Home", tint = Color.White, modifier = Modifier.size(30.dp))
        }
        Box(Modifier.align(Alignment.Center).size(44.dp)) {
            Icon(
                Icons.Outlined.DocumentScanner,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.align(Alignment.Center).size(30.dp),
            )
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(ScanColors.Accent),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Star, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
            }
        }
        IconButton(onClick = onQr, modifier = Modifier.align(Alignment.CenterEnd)) {
            Icon(
                Icons.Outlined.QrCodeScanner,
                contentDescription = "QR code",
                tint = if (qrMode) ScanColors.Accent else Color.White,
                modifier = Modifier.size(30.dp),
            )
        }
    }
}

@Composable
private fun ScanAiPill(aiAssist: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(ScanColors.Pill.copy(alpha = 0.92f))
            .padding(4.dp),
    ) {
        listOf(false to "Scan", true to "AI assist").forEach { (value, label) ->
            val selected = aiAssist == value
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (selected) ScanColors.Accent else Color.Transparent)
                    .clickable { onChange(value) }
                    .padding(horizontal = 30.dp, vertical = 11.dp),
            ) {
                Text(label, color = Color.White, fontSize = 17.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

/** Horizontal swipe on the preview switches mode, like Adobe Scan. */
private fun Modifier.pointerModeSwipe(vm: ScannerViewModel): Modifier = pointerInput(Unit) {
    var total = 0f
    detectHorizontalDragGestures(
        onDragStart = { total = 0f },
        onDragEnd = {
            val modes = ScanMode.entries
            val i = modes.indexOf(vm.mode)
            if (total < -120 && i < modes.lastIndex) vm.selectMode(modes[i + 1])
            if (total > 120 && i > 0) vm.selectMode(modes[i - 1])
        },
    ) { _, dx -> total += dx }
}

@Composable
private fun ModeCarousel(selected: ScanMode, onSelect: (ScanMode) -> Unit) {
    val centers = remember { mutableStateMapOf<ScanMode, Float>() }
    var width by remember { mutableFloatStateOf(0f) }
    val target = centers[selected]?.let { width / 2f - it } ?: 0f
    val offset by animateFloatAsState(target, tween(260), label = "carousel")
    var drag by remember { mutableFloatStateOf(0f) }

    Box(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(ScanColors.Bar)
            .onSizeChanged { width = it.width.toFloat() }
            .clipToBounds()
            .pointerInput(selected) {
                detectHorizontalDragGestures(
                    onDragStart = { drag = 0f },
                    onDragEnd = {
                        val modes = ScanMode.entries
                        val i = modes.indexOf(selected)
                        if (drag < -60 && i < modes.lastIndex) onSelect(modes[i + 1])
                        if (drag > 60 && i > 0) onSelect(modes[i - 1])
                    },
                ) { _, dx -> drag += dx }
            },
    ) {
        Row(
            Modifier
                .align(Alignment.CenterStart)
                .wrapContentWidth(align = Alignment.Start, unbounded = true)
                .offset { IntOffset(offset.roundToInt(), 0) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ScanMode.entries.forEach { m ->
                val isSel = m == selected
                Text(
                    m.label,
                    color = if (isSel) ScanColors.Accent else Color.White,
                    fontSize = 17.sp,
                    fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier
                        .onGloballyPositioned { c -> centers[m] = c.positionInParent().x + c.size.width / 2f }
                        .clickable { onSelect(m) }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
    }
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
    onGallery: () -> Unit,
    onToggleAuto: () -> Unit,
    onShutter: () -> Unit,
    onFlash: () -> Unit,
    onThumbnail: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(ScanColors.Bar)
            .padding(horizontal = 12.dp, vertical = 18.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onGallery) {
            Icon(Icons.Outlined.PhotoLibrary, "Import from gallery", tint = Color.White, modifier = Modifier.size(32.dp))
        }
        IconButton(onClick = onToggleAuto) {
            AutoCaptureIcon(autoCapture)
        }
        Shutter(progress, capturing, onShutter)
        IconButton(onClick = onFlash) {
            Icon(
                when (flash) {
                    Flash.AUTO -> Icons.Filled.FlashAuto
                    Flash.ON -> Icons.Filled.FlashOn
                    Flash.OFF -> Icons.Filled.FlashOff
                },
                "Flash",
                tint = Color.White,
                modifier = Modifier.size(32.dp),
            )
        }
        ThumbnailStack(thumbnail, pageCount, processing, onThumbnail)
    }
}

/** Frame-with-sparkle icon (Adobe's auto-capture toggle), drawn so it can show on/off state. */
@Composable
private fun AutoCaptureIcon(on: Boolean) {
    val c = if (on) Color.White else Color.White.copy(alpha = 0.4f)
    Canvas(Modifier.size(32.dp)) {
        val w = size.width
        val sw = 2.4.dp.toPx()
        val k = w * 0.28f
        // corner brackets
        drawLine(c, Offset(0f, k), Offset(0f, 0f), sw); drawLine(c, Offset(0f, 0f), Offset(k, 0f), sw)
        drawLine(c, Offset(w - k, 0f), Offset(w, 0f), sw); drawLine(c, Offset(w, 0f), Offset(w, k), sw)
        drawLine(c, Offset(w, w - k), Offset(w, w), sw); drawLine(c, Offset(w, w), Offset(w - k, w), sw)
        drawLine(c, Offset(k, w), Offset(0f, w), sw); drawLine(c, Offset(0f, w), Offset(0f, w - k), sw)
        // sparkle
        val cx = w / 2; val cy = w / 2; val r = w * 0.3f; val t = w * 0.07f
        val star = Path().apply {
            moveTo(cx, cy - r); lineTo(cx + t, cy - t); lineTo(cx + r, cy); lineTo(cx + t, cy + t)
            lineTo(cx, cy + r); lineTo(cx - t, cy + t); lineTo(cx - r, cy); lineTo(cx - t, cy - t); close()
        }
        drawPath(star, c)
        if (!on) drawLine(c, Offset(w * 0.12f, w * 0.88f), Offset(w * 0.88f, w * 0.12f), sw)
    }
}

@Composable
private fun Shutter(progress: Float, capturing: Boolean, onClick: () -> Unit) {
    val inner by animateFloatAsState(if (capturing) 0.82f else 0f, tween(120), label = "shutterInner")
    val shownProgress by animateFloatAsState(progress, tween(120), label = "shutterProgress")
    Box(
        Modifier
            .size(84.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val ring = 5.dp.toPx()
            val r = size.minDimension / 2 - ring
            drawCircle(Color.White, r, style = Stroke(ring))
            if (shownProgress > 0f) {
                drawArc(
                    ScanColors.Accent,
                    startAngle = -90f,
                    sweepAngle = -360f * shownProgress,
                    useCenter = false,
                    topLeft = Offset(ring, ring),
                    size = androidx.compose.ui.geometry.Size(size.width - 2 * ring, size.height - 2 * ring),
                    style = Stroke(ring, cap = StrokeCap.Round),
                )
            }
            if (inner > 0f) drawCircle(Color.White, r * inner)
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
    Box(Modifier.size(width = 52.dp, height = 70.dp).scale(pulse.value)) {
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .size(width = 46.dp, height = 64.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFF2A2A2A))
                .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(4.dp))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            val thumb = page?.thumbnail
            if (thumb != null) {
                Image(thumb, contentDescription = "Scanned pages", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
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
                    .clip(CircleShape)
                    .background(ScanColors.Accent),
                contentAlignment = Alignment.Center,
            ) {
                Text("$count", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
        title = { Text("QR code") },
        text = { Text(text) },
        confirmButton = {
            if (isUrl) {
                TextButton(onClick = {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(text))) }
                    onDismiss()
                }) { Text("Open") }
            } else {
                TextButton(onClick = { copy(context, text); onDismiss() }) { Text("Copy") }
            }
        },
        dismissButton = {
            Row {
                if (isUrl) TextButton(onClick = { copy(context, text); onDismiss() }) { Text("Copy") }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
    )
}

private fun copy(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("QR", text))
    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}
