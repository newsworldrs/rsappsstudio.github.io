package com.rskusum.scanner.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** RS Kusum palette: teal accent, marigold highlight, slate surfaces; buttons use the RS Apps Studio blue-purple gradient. */
object ScanColors {
    val Blue = Color(0xFF1E90FF)          // gradient start (brand blue)
    val Purple = Color(0xFF8C6CFF)        // gradient end (brand purple)
    val Accent = Color(0xFF0B8F86)        // teal: selected states, handles, outlines
    val AccentBright = Color(0xFF2FD3C4)  // progress rings, detected outline
    val AccentSoft = Color(0x550B8F86)
    /** Brand gradient: backgrounds of buttons and icon buttons only. */
    val Gradient: Brush get() = Brush.linearGradient(listOf(Blue, Purple))

    /** Button / icon background: the gradient when on, [off] otherwise. */
    fun buttonBackground(on: Boolean, off: Color = Color.Transparent): Brush =
        if (on) Gradient else androidx.compose.ui.graphics.SolidColor(off)
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
