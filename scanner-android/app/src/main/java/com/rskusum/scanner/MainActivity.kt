package com.rskusum.scanner

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rskusum.scanner.ui.ScannerTheme
import com.rskusum.scanner.ui.camera.CameraScreen
import com.rskusum.scanner.ui.crop.CropScreen
import com.rskusum.scanner.ui.home.HomeScreen
import com.rskusum.scanner.ui.review.ReviewScreen

enum class Screen { CAMERA, REVIEW, CROP, ERASE, HOME }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.BLACK),
            navigationBarStyle = SystemBarStyle.dark(Color.BLACK),
        )
        super.onCreate(savedInstanceState)
        setContent {
            ScannerTheme {
                val vm: ScannerViewModel = viewModel()
                // Like Adobe Scan, the app opens straight into the camera.
                var screen by rememberSaveable { mutableStateOf(Screen.CAMERA) }
                var cropIndex by rememberSaveable { mutableStateOf(0) }
                var reviewIndex by rememberSaveable { mutableStateOf(0) }

                // Process death loses the in-memory session; never land on an empty editor.
                if ((screen == Screen.REVIEW || screen == Screen.CROP || screen == Screen.ERASE) && vm.pages.isEmpty() && !vm.isRendering) {
                    screen = Screen.CAMERA
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
                            onHome = { vm.refreshDocuments(); screen = Screen.HOME },
                        )
                        Screen.REVIEW -> ReviewScreen(
                            vm = vm,
                            initialPage = reviewIndex,
                            onPageChanged = { reviewIndex = it },
                            onAddPage = { screen = Screen.CAMERA },
                            onCrop = { cropIndex = it; screen = Screen.CROP },
                            onErase = { cropIndex = it; screen = Screen.ERASE },
                            onSaved = { screen = Screen.HOME },
                            onDiscard = { vm.discardSession(); screen = Screen.CAMERA },
                        )
                        Screen.CROP -> CropScreen(
                            vm = vm,
                            page = vm.pages.getOrNull(cropIndex),
                            onDone = { screen = Screen.REVIEW },
                        )
                        Screen.ERASE -> com.rskusum.scanner.ui.erase.EraseScreen(
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
        }
    }
}
