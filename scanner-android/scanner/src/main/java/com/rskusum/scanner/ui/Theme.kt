package com.rskusum.scanner.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** RS Kusum brand palette: deep teal accent, warm marigold highlight, slate surfaces. */
object ScanColors {
    val Accent = Color(0xFF0B8F86)        // teal: buttons, selected mode, handles
    val AccentBright = Color(0xFF2FD3C4)  // progress rings, detected outline
    val AccentSoft = Color(0x550B8F86)
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
