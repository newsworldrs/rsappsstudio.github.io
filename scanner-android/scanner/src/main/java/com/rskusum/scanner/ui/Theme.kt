package com.rskusum.scanner.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** RS Apps Studio brand palette: blue-to-purple gradient accent, marigold highlight, slate surfaces. */
object ScanColors {
    val Blue = Color(0xFF1E90FF)          // gradient start (brand blue)
    val Purple = Color(0xFF8C6CFF)        // gradient end (brand purple)
    val Accent = Color(0xFF5B6CFF)        // blue-violet: selected mode, handles, sliders
    val AccentBright = Color(0xFF7FB4FF)  // progress rings, detected outline
    val AccentSoft = Color(0x555B6CFF)
    /** Brand gradient for primary buttons, badges and the page outline. */
    val Gradient: Brush get() = Brush.linearGradient(listOf(Blue, Purple))
    val Marigold = Color(0xFFFFB020)      // book spine guide, badges
    val Bar = Color(0xFF101418)
    val Surface = Color(0xFF14191E)
    val SurfaceHigh = Color(0xFF1F262D)
    val Pill = Color(0xFF1F262D)
    val TextDim = Color(0xFFAAB4BE)
}

private val scheme = darkColorScheme(
    primary = ScanColors.Accent,
    onPrimary = Color.White,
    secondary = ScanColors.Accent,
    background = ScanColors.Surface,
    surface = ScanColors.Surface,
    surfaceVariant = ScanColors.SurfaceHigh,
    surfaceContainer = ScanColors.SurfaceHigh,
    surfaceContainerHigh = ScanColors.SurfaceHigh,
    onSurface = Color.White,
    onBackground = Color.White,
)

@Composable
fun ScannerTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
