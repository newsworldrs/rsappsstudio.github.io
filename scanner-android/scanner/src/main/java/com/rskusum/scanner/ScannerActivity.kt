package com.rskusum.scanner

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.IntentCompat
import kotlinx.coroutines.launch
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rskusum.scanner.ui.ScannerTheme
import com.rskusum.scanner.ui.camera.CameraScreen
import com.rskusum.scanner.ui.camera.maxGalleryPick
import androidx.compose.foundation.background
import com.rskusum.scanner.ui.crop.CropScreen
import com.rskusum.scanner.ui.erase.EraseScreen
import com.rskusum.scanner.ui.home.HomeScreen
import com.rskusum.scanner.ui.review.ReviewScreen

/** Full-screen scanner. Open it with [ScanDocument] (or [RsScanner.createIntent]). */
class ScannerActivity : ComponentActivity() {
    /**
     * Edge-to-edge without androidx's enableEdgeToEdge(), which still sets the display-cutout
     * mode SHORT_EDGES (deprecated in Android 15 and reported by Google Play). The content draws
     * behind the system bars (the screens pad themselves with the window insets), the bars stay
     * black with light icons, and on Android 11+ the cutout area is used in every orientation
     * (ALWAYS, the Android 15 behaviour). Android 8-10 keep the system's default cutout mode.
     */
    private fun drawEdgeToEdge() {
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }

    @android.annotation.SuppressLint("SourceLockedOrientationActivity") // deliberate: see below
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        drawEdgeToEdge()
        // Portrait only, also when the manifest lock is overridden: the scanner layout never
        // rotates; its icons turn with the phone instead (see rememberIconTurn).
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        RsScanner.init(this)
        val options = IntentCompat.getParcelableExtra(intent, RsScanner.EXTRA_OPTIONS, ScannerOptions::class.java)
            ?: ScannerOptions()
        setContent {
            ScannerTheme {
                ScannerFlow(
                    options = options,
                    onResult = { result ->
                        val data = Intent().putExtra(RsScanner.EXTRA_RESULT, result)
                        if (result.pdfUri != null || result.pageUris.isNotEmpty()) {
                            data.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        setResult(Activity.RESULT_OK, data)
                        finish()
                    },
                )
            }
        }
    }
}

private enum class Screen { CAMERA, REVIEW, CROP, ERASE, HOME, AI_TEXT, IMPORT }

/** Back presses further apart than this start the exit sequence again. */
private const val EXIT_WINDOW_MS = 2500L

/**
 * The whole scanner as one composable: camera -> review -> crop / erase -> result.
 * Use it to host the scanner inside your own Compose navigation instead of [ScannerActivity];
 * wrap it in [ScannerTheme]. [onResult] is called once the user taps Done (never in
 * [ScannerOptions.standalone] mode, which saves to its own library instead).
 */
/**
 * @param onExit called when the user leaves with Back (the unsaved scan is discarded). Default:
 *   finishes the hosting activity (result: cancelled).
 */
@Composable
fun ScannerFlow(
    options: ScannerOptions = ScannerOptions(),
    onResult: (ScanResult) -> Unit = {},
    onExit: (() -> Unit)? = null,
) {
    RsScanner.init(androidx.compose.ui.platform.LocalContext.current)
    val vm: ScannerViewModel = viewModel()
    vm.configure(options)
    // Opens straight into the camera, or into the photo picker for "image to PDF".
    var screen by rememberSaveable { mutableStateOf(if (options.startWithGallery) Screen.IMPORT else Screen.CAMERA) }
    var cropIndex by rememberSaveable { mutableIntStateOf(0) }
    var reviewIndex by rememberSaveable { mutableIntStateOf(0) }
    var aiPageId by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    // Image to PDF: the system photo picker opens first; the picked pictures are cropped,
    // straightened and enhanced like camera pages and land in the editor. Cancel = leave.
    var galleryAsked by rememberSaveable { mutableStateOf(false) }
    val pickerContext = androidx.compose.ui.platform.LocalContext.current
    val startPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia(maxGalleryPick()),
    ) { uris: List<android.net.Uri> ->
        if (uris.isNotEmpty()) {
            vm.importFromGallery(uris)
            reviewIndex = 0
            screen = Screen.REVIEW
        } else if (vm.pages.isEmpty() && !vm.isRendering) {
            vm.discardSession()
            if (onExit != null) onExit() else (pickerContext as? Activity)?.finish()
        } else {
            screen = Screen.REVIEW
        }
    }
    LaunchedEffect(screen) {
        if (screen == Screen.IMPORT && !galleryAsked) {
            galleryAsked = true
            startPicker.launch(
                androidx.activity.result.PickVisualMediaRequest(
                    androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly,
                ),
            )
        }
    }

    // Process death loses the in-memory session; never land on an empty editor.
    if ((screen == Screen.REVIEW || screen == Screen.CROP || screen == Screen.ERASE || screen == Screen.AI_TEXT) && vm.pages.isEmpty() && !vm.isRendering) {
        screen = Screen.CAMERA
    }

    // Embedded use: hand the finished scan back to the caller.
    LaunchedEffect(vm) {
        vm.results.collect { onResult(it) }
    }
    // AI Text: a page to read was captured (or picked in review) - show it.
    LaunchedEffect(vm) {
        vm.aiRequests.collect { page -> aiPageId = page.id; screen = Screen.AI_TEXT }
    }

    // Leaving from the camera: Back once = "press again to exit"; with unsaved pages a second
    // Back warns that the scan is not saved; the next Back exits without saving.
    val context = androidx.compose.ui.platform.LocalContext.current
    var exitStep by remember { mutableIntStateOf(0) }
    var exitAt by remember { mutableLongStateOf(0L) }
    var exitToast by remember { mutableStateOf<Toast?>(null) }
    fun toast(@androidx.annotation.StringRes id: Int) {
        exitToast?.cancel()
        exitToast = Toast.makeText(context, id, Toast.LENGTH_SHORT).also { it.show() }
    }
    fun exit() {
        exitToast?.cancel()
        vm.discardSession()
        if (onExit != null) onExit() else (context as? Activity)?.finish()
    }
    BackHandler {
        if (screen != Screen.CAMERA) {
            exitStep = 0
            screen = when (screen) {
                Screen.CROP, Screen.ERASE -> Screen.REVIEW
                else -> Screen.CAMERA
            }
            return@BackHandler
        }
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - exitAt > EXIT_WINDOW_MS) exitStep = 0
        exitAt = now
        val unsaved = vm.pages.isNotEmpty()
        when {
            exitStep == 0 -> { exitStep = 1; toast(R.string.rs_scanner_press_back_exit) }
            exitStep == 1 && unsaved -> { exitStep = 2; toast(R.string.rs_scanner_exit_unsaved) }
            else -> { exitStep = 0; exit() }
        }
    }

    // Screen stays on while scanning (camera open) and while pages are still being prepared, so
    // a long batch is never interrupted by the screen timeout.
    val hostView = androidx.compose.ui.platform.LocalView.current
    val keepAwake = screen == Screen.CAMERA || vm.isRendering
    androidx.compose.runtime.DisposableEffect(keepAwake) {
        hostView.keepScreenOn = keepAwake
        onDispose { hostView.keepScreenOn = false }
    }

    // First run: the button tour, once per install.
    var showIntro by remember { mutableStateOf(options.showIntro && !com.rskusum.scanner.ui.intro.IntroPrefs.shown(context)) }

    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
    AnimatedContent(
        targetState = screen,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "nav",
    ) { s ->
        when (s) {
            Screen.IMPORT -> androidx.compose.foundation.layout.Box(
                androidx.compose.ui.Modifier
                    .fillMaxSize()
                    .then(androidx.compose.ui.Modifier.background(androidx.compose.ui.graphics.Color.Black)),
            )
            Screen.CAMERA -> androidx.compose.runtime.CompositionLocalProvider(
                com.rskusum.scanner.ui.camera.LocalIconTurn provides com.rskusum.scanner.ui.camera.rememberIconTurn(),
            ) {
                CameraScreen(
                    vm = vm,
                    onOpenReview = { reviewIndex = vm.pages.lastIndex.coerceAtLeast(0); screen = Screen.REVIEW },
                    onHome = if (options.standalone) ({ vm.refreshDocuments(); screen = Screen.HOME }) else null,
                )
            }
            Screen.REVIEW -> ReviewScreen(
                vm = vm,
                initialPage = reviewIndex,
                onPageChanged = { reviewIndex = it },
                onAddPage = { screen = Screen.CAMERA },
                onCrop = { cropIndex = it; screen = Screen.CROP },
                onErase = { cropIndex = it; screen = Screen.ERASE },
                onText = { i -> vm.pages.getOrNull(i)?.let { vm.openAiText(it) } },
                onSaved = { screen = if (options.standalone) Screen.HOME else Screen.CAMERA },
                onDiscard = { vm.discardSession(); screen = Screen.CAMERA },
            )
            Screen.AI_TEXT -> com.rskusum.scanner.ui.ai.AiTextScreen(
                vm = vm,
                page = vm.pages.firstOrNull { it.id == aiPageId },
                onBack = { screen = Screen.CAMERA },
                onScanMore = { screen = Screen.CAMERA },
                onDone = {
                    if (options.standalone) {
                        reviewIndex = vm.pages.indexOfFirst { it.id == aiPageId }.coerceAtLeast(0)
                        screen = Screen.REVIEW
                    } else {
                        scope.launch { vm.savePdf() } // hands the ScanResult (with text) to the caller
                    }
                },
            )
            Screen.CROP -> CropScreen(
                vm = vm,
                page = vm.pages.getOrNull(cropIndex),
                onDone = { screen = Screen.REVIEW },
            )
            Screen.ERASE -> EraseScreen(
                vm = vm,
                page = vm.pages.getOrNull(cropIndex),
                onDone = { screen = Screen.REVIEW },
            )
            Screen.HOME -> HomeScreen(
                vm = vm,
                onScan = { screen = Screen.CAMERA },
            )
        }
    }
    if (showIntro) {
        com.rskusum.scanner.ui.intro.IntroScreen(onDone = {
            com.rskusum.scanner.ui.intro.IntroPrefs.markShown(context)
            showIntro = false
        })
    }
    }
}
