package com.rskusum.whocaller.core.ui.component

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rskusum.whocaller.core.model.CallerLabel
import com.rskusum.whocaller.core.model.LocalProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Look of one built-in avatar. All avatars are original illustrations drawn in code. */
data class AvatarStyle(
    val background: Color,
    val skin: Color,
    val hair: Color,
    val shirt: Color,
    /** 0 short, 1 long, 2 bun, 3 none, 4 curly */
    val hairStyle: Int,
    val glasses: Boolean,
)

object Avatars {
    private val skins = listOf(Color(0xFFF5D0B5), Color(0xFFE0AC85), Color(0xFFB57C55), Color(0xFF7D4E33))

    val ALL: List<AvatarStyle> = listOf(
        AvatarStyle(Color(0xFF5CDBCD), skins[0], Color(0xFF3B2A20), Color(0xFF006A62), 0, false),
        AvatarStyle(Color(0xFFFFB86B), skins[1], Color(0xFF1B1B1B), Color(0xFF8A5100), 1, false),
        AvatarStyle(Color(0xFF9CC9F0), skins[2], Color(0xFF2A1A12), Color(0xFF3A5F7D), 2, true),
        AvatarStyle(Color(0xFFC9B6F2), skins[3], Color(0xFF111111), Color(0xFF5B3FA0), 4, false),
        AvatarStyle(Color(0xFFA8E6A1), skins[0], Color(0xFFB5651D), Color(0xFF1B6D3B), 1, true),
        AvatarStyle(Color(0xFFFFD6E0), skins[1], Color(0xFF4A2C1D), Color(0xFFB3261E), 0, true),
        AvatarStyle(Color(0xFFFFE08A), skins[2], Color(0xFF1B1B1B), Color(0xFF6D5E0F), 3, false),
        AvatarStyle(Color(0xFFB4C8E9), skins[3], Color(0xFF2B2B2B), Color(0xFF1D314B), 2, false),
        AvatarStyle(Color(0xFFE6C3A5), skins[0], Color(0xFFD9A441), Color(0xFF8C5A2B), 4, true),
        AvatarStyle(Color(0xFFBEE3DB), skins[1], Color(0xFF3B2A20), Color(0xFF00504A), 3, true),
        AvatarStyle(Color(0xFFF2B8B5), skins[2], Color(0xFF5A3825), Color(0xFF8C1D18), 1, false),
        AvatarStyle(Color(0xFFD6E3F9), skins[3], Color(0xFF151515), Color(0xFF3A4759), 0, false),
    )

    fun get(id: Int?): AvatarStyle? = id?.let { ALL.getOrNull(it) }
}

/** Draws a built-in avatar: background, shoulders, face and hair. */
@Composable
fun AvatarImage(style: AvatarStyle, modifier: Modifier = Modifier, size: Dp = 48.dp) {
    Canvas(modifier.size(size).clip(CircleShape)) {
        val w = this.size.width
        val h = this.size.height
        drawRect(style.background)

        // Shoulders
        drawOval(style.shirt, topLeft = Offset(w * 0.12f, h * 0.70f), size = Size(w * 0.76f, h * 0.6f))
        // Neck
        drawRect(style.skin, topLeft = Offset(w * 0.43f, h * 0.55f), size = Size(w * 0.14f, h * 0.17f))

        val headCenter = Offset(w * 0.5f, h * 0.42f)
        val r = w * 0.2f
        // Long hair behind the head
        if (style.hairStyle == 1) {
            drawRoundRect(
                style.hair,
                topLeft = Offset(headCenter.x - r * 1.15f, headCenter.y - r * 0.9f),
                size = Size(r * 2.3f, r * 2.4f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r),
            )
        }
        // Face
        drawCircle(style.skin, radius = r, center = headCenter)

        // Hair on top
        when (style.hairStyle) {
            0, 1 -> {
                val path = Path().apply {
                    moveTo(headCenter.x - r * 1.02f, headCenter.y - r * 0.05f)
                    cubicTo(headCenter.x - r, headCenter.y - r * 1.45f, headCenter.x + r, headCenter.y - r * 1.45f, headCenter.x + r * 1.02f, headCenter.y - r * 0.05f)
                    cubicTo(headCenter.x + r * 0.5f, headCenter.y - r * 0.6f, headCenter.x - r * 0.5f, headCenter.y - r * 0.6f, headCenter.x - r * 1.02f, headCenter.y - r * 0.05f)
                    close()
                }
                drawPath(path, style.hair)
            }
            2 -> {
                drawCircle(style.hair, radius = r * 0.45f, center = Offset(headCenter.x, headCenter.y - r * 1.2f))
                drawArc(style.hair, 180f, 180f, useCenter = true, topLeft = Offset(headCenter.x - r, headCenter.y - r), size = Size(r * 2, r * 1.3f))
            }
            4 -> {
                for (i in -2..2) {
                    drawCircle(style.hair, radius = r * 0.38f, center = Offset(headCenter.x + i * r * 0.45f, headCenter.y - r * 0.85f + (i * i) * r * 0.08f))
                }
            }
            else -> Unit
        }

        // Eyes and smile
        val eyeY = headCenter.y + r * 0.05f
        drawCircle(Color(0xFF1B1B1B), radius = r * 0.09f, center = Offset(headCenter.x - r * 0.38f, eyeY))
        drawCircle(Color(0xFF1B1B1B), radius = r * 0.09f, center = Offset(headCenter.x + r * 0.38f, eyeY))
        drawArc(
            Color(0xFF1B1B1B),
            startAngle = 20f,
            sweepAngle = 140f,
            useCenter = false,
            topLeft = Offset(headCenter.x - r * 0.4f, headCenter.y + r * 0.05f),
            size = Size(r * 0.8f, r * 0.6f),
            style = Stroke(width = r * 0.09f),
        )
        if (style.glasses) {
            val stroke = Stroke(width = r * 0.08f)
            drawCircle(Color(0xFF1B1B1B), radius = r * 0.26f, center = Offset(headCenter.x - r * 0.38f, eyeY), style = stroke)
            drawCircle(Color(0xFF1B1B1B), radius = r * 0.26f, center = Offset(headCenter.x + r * 0.38f, eyeY), style = stroke)
            drawLine(Color(0xFF1B1B1B), Offset(headCenter.x - r * 0.12f, eyeY), Offset(headCenter.x + r * 0.12f, eyeY), strokeWidth = r * 0.08f)
        }
    }
}

/** Loads a downscaled photo from app storage off the main thread. */
@Composable
fun rememberPhoto(path: String?, maxPx: Int = 512): ImageBitmap? {
    var bitmap by remember(path) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(path) {
        bitmap = if (path == null) null else withContext(Dispatchers.IO) { decodeScaled(path, maxPx) }
    }
    return bitmap
}

private fun decodeScaled(path: String, maxPx: Int): ImageBitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= maxPx) sample *= 2
    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}.getOrNull()

/** The user's own picture: photo, else chosen avatar, else initial. */
@Composable
fun ProfileAvatar(profile: LocalProfile, modifier: Modifier = Modifier, size: Dp = 48.dp, description: String? = null) {
    val photo = rememberPhoto(profile.photoPath)
    val style = Avatars.get(profile.avatarId)
    val semanticsModifier = if (description != null) modifier.semantics { contentDescription = description } else modifier
    Box(semanticsModifier.size(size).clip(CircleShape).background(Color.Transparent), contentAlignment = Alignment.Center) {
        when {
            photo != null -> Image(photo, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size))
            style != null -> AvatarImage(style, size = size)
            else -> CallerAvatar(profile.name.ifBlank { null }, CallerLabel.PERSON, size = size)
        }
    }
}
