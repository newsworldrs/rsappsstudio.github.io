package com.rskusum.scanner

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import com.rskusum.scanner.ui.crop.CropScreen
import com.rskusum.scanner.ui.erase.EraseScreen
import com.rskusum.scanner.ui.home.HomeScreen
import com.rskusum.scanner.ui.review.ReviewScreen

/** Full-screen scanner. Open it with [ScanDocument] (or [RsScanner.createIntent]). */
class ScannerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.BLACK),
            navigationBarStyle = SystemBarStyle.dark(Color.BLACK),
        )
        super.onCreate(savedInstanceState)
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

private enum class Screen { CAMERA, REVIEW, CROP, ERASE, HOME, AI_TEXT }

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
    // Opens straight into the camera.
    var screen by rememberSaveable { mutableStateOf(Screen.CAMERA) }
    var cropIndex by rememberSaveable { mutableIntStateOf(0) }
    var reviewIndex by rememberSaveable { mutableIntStateOf(0) }
    var aiPageId by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

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

    // First run: the button tour, once per install.
    var showIntro by remember { mutableStateOf(options.showIntro && !com.rskusum.scanner.ui.intro.IntroPrefs.shown(context)) }

    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
    AnimatedContent(
        targetState = screen,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "nav",
    ) { s ->
        when (s) {
            Screen.CAMERA -> CameraScreen(
                vm = vm,
                onOpenReview = { reviewIndex = vm.pages.lastIndex.coerceAtLeast(0); screen = Screen.REVIEW },
                onHome = if (options.standalone) ({ vm.refreshDocuments(); screen = Screen.HOME }) else null,
            )
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
