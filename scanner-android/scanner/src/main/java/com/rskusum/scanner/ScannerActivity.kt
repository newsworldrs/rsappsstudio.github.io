package com.rskusum.scanner

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.IntentCompat
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

private enum class Screen { CAMERA, REVIEW, CROP, ERASE, HOME }

/**
 * The whole scanner as one composable: camera -> review -> crop / erase -> result.
 * Use it to host the scanner inside your own Compose navigation instead of [ScannerActivity];
 * wrap it in [ScannerTheme]. [onResult] is called once the user taps Done (never in
 * [ScannerOptions.standalone] mode, which saves to its own library instead).
 */
@Composable
fun ScannerFlow(options: ScannerOptions = ScannerOptions(), onResult: (ScanResult) -> Unit = {}) {
    RsScanner.init(androidx.compose.ui.platform.LocalContext.current)
    val vm: ScannerViewModel = viewModel()
    vm.configure(options)
    // Opens straight into the camera.
    var screen by rememberSaveable { mutableStateOf(Screen.CAMERA) }
    var cropIndex by rememberSaveable { mutableIntStateOf(0) }
    var reviewIndex by rememberSaveable { mutableIntStateOf(0) }

    // Process death loses the in-memory session; never land on an empty editor.
    if ((screen == Screen.REVIEW || screen == Screen.CROP || screen == Screen.ERASE) && vm.pages.isEmpty() && !vm.isRendering) {
        screen = Screen.CAMERA
    }

    // Embedded use: hand the finished scan back to the caller.
    LaunchedEffect(vm) {
        vm.results.collect { onResult(it) }
    }

    BackHandler(enabled = screen != Screen.CAMERA || vm.pages.isNotEmpty()) {
        screen = when (screen) {
            Screen.CROP, Screen.ERASE -> Screen.REVIEW
            Screen.REVIEW -> Screen.CAMERA
            Screen.HOME -> Screen.CAMERA
            Screen.CAMERA -> Screen.REVIEW
        }
    }

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
                onSaved = { screen = if (options.standalone) Screen.HOME else Screen.CAMERA },
                onDiscard = { vm.discardSession(); screen = Screen.CAMERA },
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
}
