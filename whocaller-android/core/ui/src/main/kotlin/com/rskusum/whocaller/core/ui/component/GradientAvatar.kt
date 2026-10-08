package com.rskusum.whocaller.core.ui.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonOff
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

/** Base colour pairs; each name always gets the same one. */
private val BASES = listOf(
    Color(0xFF6D7BFF) to Color(0xFF4F46E5),
    Color(0xFFFF8A65) to Color(0xFFE8505B),
    Color(0xFF34D399) to Color(0xFF059669),
    Color(0xFFFBBF24) to Color(0xFFF97316),
    Color(0xFFF472B6) to Color(0xFFDB2777),
    Color(0xFF60A5FA) to Color(0xFF2563EB),
    Color(0xFFA78BFA) to Color(0xFF7C3AED),
    Color(0xFF2DD4BF) to Color(0xFF0D9488),
)
private val WARN = Color(0xFFFF6B6B) to Color(0xFFE5484D)

/** Light gradient + readable letter colour for [seed], following the current light/dark theme. */
data class AvatarLook(val gradient: List<Color>, val content: Color)

@Composable
fun avatarLook(seed: String?, warning: Boolean = false): AvatarLook {
    val (a, b) = if (warning) WARN else if (seed.isNullOrBlank()) BASES.first() else BASES[abs(seed.lowercase().hashCode()) % BASES.size]
    val surface = MaterialTheme.colorScheme.surface
    return if (surface.luminance() < 0.5f) {
        // Dark theme: soft, deeper tones with a light letter.
        AvatarLook(listOf(lerp(a, surface, 0.45f), lerp(b, surface, 0.6f)), lerp(a, Color.White, 0.65f))
    } else {
        // Light theme: pastel gradient with a deep letter of the same colour.
        AvatarLook(listOf(lerp(a, Color.White, 0.70f), lerp(b, Color.White, 0.80f)), lerp(b, Color.Black, 0.15f))
    }
}

/**
 * Round avatar on a light gradient: the photo if there is one, else the name's first letter
 * (or [icon]). Red tones for suspected spam.
 */
@Composable
fun GradientAvatar(
    name: String?,
    size: Dp,
    modifier: Modifier = Modifier,
    photo: ImageBitmap? = null,
    warning: Boolean = false,
    icon: ImageVector? = null,
) {
    val look = avatarLook(name, warning)
    Box(
        modifier.size(size).clip(CircleShape).background(Brush.linearGradient(look.gradient)),
        contentAlignment = Alignment.Center,
    ) {
        val initial = name?.firstOrNull { it.isLetter() }?.uppercaseChar()
        when {
            photo != null && !warning -> Image(photo, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size))
            warning -> Icon(Icons.Filled.Warning, contentDescription = null, tint = look.content, modifier = Modifier.size(size * 0.5f))
            icon != null -> Icon(icon, contentDescription = null, tint = look.content, modifier = Modifier.size(size * 0.5f))
            initial != null -> Text(initial.toString(), color = look.content, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.42f).sp)
            else -> Icon(Icons.Filled.Person, contentDescription = null, tint = look.content, modifier = Modifier.size(size * 0.55f))
        }
    }
}

/** Icon for labels that have one (business, hidden); null means "show the letter". */
fun iconFor(label: com.rskusum.whocaller.core.model.CallerLabel): ImageVector? = when (label) {
    com.rskusum.whocaller.core.model.CallerLabel.VERIFIED_BUSINESS, com.rskusum.whocaller.core.model.CallerLabel.BUSINESS -> Icons.Filled.Business
    com.rskusum.whocaller.core.model.CallerLabel.HIDDEN -> Icons.Filled.PersonOff
    else -> null
}

/** Blue tick shown after a name that is a verified WhoCaller ID (number confirmed by SMS code). */
@Composable
fun VerifiedTick(size: Dp, modifier: Modifier = Modifier, description: String? = null) {
    Icon(
        Icons.Filled.Verified,
        contentDescription = description,
        tint = VerifiedBlue,
        modifier = modifier.size(size),
    )
}

/** The blue used for verified WhoCaller IDs, the same in light and dark themes. */
val VerifiedBlue = Color(0xFF1D9BF0)
