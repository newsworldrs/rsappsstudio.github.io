package com.rskusum.whocaller.core.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.rskusum.whocaller.core.model.RiskLevel
import com.rskusum.whocaller.core.model.ThemeMode

// Original WhoCaller palette: deep teal (trust) with warm amber/red for risk. Contrast ≥ 4.5:1 for text.
private val Teal10 = Color(0xFF00201D)
private val Teal20 = Color(0xFF003733)
private val Teal30 = Color(0xFF00504A)
private val Teal40 = Color(0xFF006A62)
private val Teal80 = Color(0xFF5CDBCD)
private val Teal90 = Color(0xFF7BF8EA)
private val Slate10 = Color(0xFF111C2B)
private val Slate30 = Color(0xFF3A4759)
private val Slate80 = Color(0xFFBAC7DC)
private val Slate90 = Color(0xFFD6E3F9)
private val Neutral10 = Color(0xFF191C1C)
private val Neutral90 = Color(0xFFE0E3E2)
private val Neutral95 = Color(0xFFEFF1F0)
private val Neutral99 = Color(0xFFFAFDFB)
private val NeutralVariant30 = Color(0xFF3F4947)
private val NeutralVariant50 = Color(0xFF6F7977)
private val NeutralVariant80 = Color(0xFFBEC9C6)
private val NeutralVariant90 = Color(0xFFDAE5E2)
private val Red40 = Color(0xFFBA1A1A)
private val Red80 = Color(0xFFFFB4AB)
private val Red90 = Color(0xFFFFDAD6)
private val Red10 = Color(0xFF410002)

private val LightColors = lightColorScheme(
    primary = Teal40, onPrimary = Color.White, primaryContainer = Teal90, onPrimaryContainer = Teal10,
    secondary = Slate30, onSecondary = Color.White, secondaryContainer = Slate90, onSecondaryContainer = Slate10,
    tertiary = Color(0xFF4C5F7C), onTertiary = Color.White,
    error = Red40, onError = Color.White, errorContainer = Red90, onErrorContainer = Red10,
    background = Neutral99, onBackground = Neutral10, surface = Neutral99, onSurface = Neutral10,
    surfaceVariant = NeutralVariant90, onSurfaceVariant = NeutralVariant30, outline = NeutralVariant50,
    surfaceContainer = Neutral95, surfaceContainerHigh = Neutral90,
)

private val DarkColors = darkColorScheme(
    primary = Teal80, onPrimary = Teal20, primaryContainer = Teal30, onPrimaryContainer = Teal90,
    secondary = Slate80, onSecondary = Slate10, secondaryContainer = Slate30, onSecondaryContainer = Slate90,
    tertiary = Color(0xFFB4C8E9), onTertiary = Color(0xFF1D314B),
    error = Red80, onError = Color(0xFF690005), errorContainer = Color(0xFF93000A), onErrorContainer = Red90,
    background = Color(0xFF0F1413), onBackground = Neutral90, surface = Color(0xFF0F1413), onSurface = Neutral90,
    surfaceVariant = NeutralVariant30, onSurfaceVariant = NeutralVariant80, outline = Color(0xFF899390),
    surfaceContainer = Color(0xFF1B2120), surfaceContainerHigh = Color(0xFF252B2A),
)

/** Colours with fixed meaning that must not change with dynamic colour. */
@Immutable
data class RiskColors(
    val safe: Color,
    val low: Color,
    val moderate: Color,
    val high: Color,
    val veryHigh: Color,
    val onRisk: Color,
) {
    fun forLevel(level: RiskLevel): Color = when (level) {
        RiskLevel.LOW -> low
        RiskLevel.MODERATE -> moderate
        RiskLevel.HIGH -> high
        RiskLevel.VERY_HIGH -> veryHigh
    }
}

private val LightRisk = RiskColors(
    safe = Color(0xFF1B6D3B), low = Color(0xFF3A5F7D), moderate = Color(0xFF8A5100),
    high = Color(0xFFB3261E), veryHigh = Color(0xFF8C0009), onRisk = Color.White,
)
private val DarkRisk = RiskColors(
    safe = Color(0xFF7DDC98), low = Color(0xFF9CC9F0), moderate = Color(0xFFFFB86B),
    high = Color(0xFFFF8A80), veryHigh = Color(0xFFFFB4AB), onRisk = Color(0xFF1B1B1B),
)

val LocalRiskColors = staticCompositionLocalOf { LightRisk }

private val WhoCallerTypography = Typography().let { base ->
    base.copy(
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    )
}

@Composable
fun WhoCallerTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colors: ColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        dark -> DarkColors
        else -> LightColors
    }
    CompositionLocalProvider(LocalRiskColors provides if (dark) DarkRisk else LightRisk) {
        MaterialTheme(colorScheme = colors, typography = WhoCallerTypography, content = content)
    }
}

object WhoCallerTheme {
    val riskColors: RiskColors
        @Composable get() = LocalRiskColors.current
}
