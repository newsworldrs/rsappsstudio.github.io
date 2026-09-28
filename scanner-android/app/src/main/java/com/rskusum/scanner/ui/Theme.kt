package com.rskusum.scanner.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object ScanColors {
    val Accent = Color(0xFF2D7FF9)        // Adobe-Scan style blue (pill, selected mode, handles)
    val AccentSoft = Color(0x552D7FF9)
    val Bar = Color(0xFF000000)
    val Surface = Color(0xFF121212)
    val SurfaceHigh = Color(0xFF1E1E1E)
    val Pill = Color(0xFF1B1B1F)
    val TextDim = Color(0xFFB8B8B8)
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
