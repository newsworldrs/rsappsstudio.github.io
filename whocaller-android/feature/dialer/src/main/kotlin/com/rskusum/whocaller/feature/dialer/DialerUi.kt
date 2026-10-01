package com.rskusum.whocaller.feature.dialer

import android.content.Context
import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar
import kotlin.math.abs

// ---------- Palette ----------

@Immutable
data class DialerPalette(
    val dark: Boolean,
    val background: Brush,
    val surface: Color,
    val keyTop: Color,
    val keyBottom: Color,
    val keyBorder: Color,
    val keyShadow: Color,
    val digit: Color,
    val letters: Color,
    val text: Color,
    val subtle: Color,
    val accent: Color,
    val actionBg: Color,
    val divider: Color,
)

val Indigo = Color(0xFF4F46E5)
val Violet = Color(0xFF8B5CF6)
val Sky = Color(0xFF38BDF8)
val CallGreenLight = Color(0xFF3DDC84)
val CallGreen = Color(0xFF12A150)
val WarnRed = Color(0xFFE5484D)
val OkGreen = Color(0xFF1F9D55)
val WhatsAppGreen = Color(0xFF25D366)
val MissedRed = Color(0xFFE5484D)

fun dialerPalette(dark: Boolean): DialerPalette = if (dark) {
    DialerPalette(
        dark = true,
        background = Brush.verticalGradient(listOf(Color(0xFF0B1026), Color(0xFF151B3D))),
        surface = Color(0xFF1A2042),
        keyTop = Color(0xFF262E55),
        keyBottom = Color(0xFF1A2042),
        keyBorder = Color(0xFF333D6B),
        keyShadow = Color.Black,
        digit = Color(0xFFF1F3FF),
        letters = Color(0xFF9AA3C7),
        text = Color(0xFFF1F3FF),
        subtle = Color(0xFFA3ABCF),
        accent = Color(0xFF8B93FF),
        actionBg = Color(0xFF242B52),
        divider = Color(0xFF2A3157),
    )
} else {
    DialerPalette(
        dark = false,
        background = Brush.verticalGradient(listOf(Color(0xFFF7F8FF), Color(0xFFE8ECFF))),
        surface = Color.White,
        keyTop = Color.White,
        keyBottom = Color(0xFFEEF1FB),
        keyBorder = Color.White,
        keyShadow = Color(0xFF7F88C9),
        digit = Color(0xFF1B1F3B),
        letters = Color(0xFF6B7090),
        text = Color(0xFF1B1F3B),
        subtle = Color(0xFF5A6085),
        accent = Indigo,
        actionBg = Color(0xFFE3E7FF),
        divider = Color(0xFFE1E5F5),
    )
}

val LocalDialerPalette = staticCompositionLocalOf { dialerPalette(false) }

// ---------- Avatars ----------

private val AVATAR_GRADIENTS = listOf(
    listOf(Color(0xFF6D7BFF), Color(0xFF4F46E5)),
    listOf(Color(0xFFFF8A65), Color(0xFFE8505B)),
    listOf(Color(0xFF34D399), Color(0xFF059669)),
    listOf(Color(0xFFFBBF24), Color(0xFFF97316)),
    listOf(Color(0xFFF472B6), Color(0xFFDB2777)),
    listOf(Color(0xFF60A5FA), Color(0xFF2563EB)),
    listOf(Color(0xFFA78BFA), Color(0xFF7C3AED)),
    listOf(Color(0xFF2DD4BF), Color(0xFF0D9488)),
)

/** Every contact gets its own colour, picked from the name so it stays the same everywhere. */
fun avatarColors(seed: String?): List<Color> =
    if (seed.isNullOrBlank()) AVATAR_GRADIENTS.first() else AVATAR_GRADIENTS[abs(seed.lowercase().hashCode()) % AVATAR_GRADIENTS.size]

private fun initialOf(name: String?): String? = name?.firstOrNull { it.isLetter() }?.uppercaseChar()?.toString()

/** Photo if there is one, else the initial on the contact's own gradient. Red "!" for spam. */
@Composable
fun ContactAvatar(name: String?, photoUri: String?, size: Dp, warning: Boolean = false, modifier: Modifier = Modifier) {
    val photo = rememberContactPhoto(photoUri, size)
    val colors = if (warning) listOf(Color(0xFFFF6B6B), WarnRed) else avatarColors(name)
    Box(modifier.size(size).clip(CircleShape).background(Brush.linearGradient(colors)), contentAlignment = Alignment.Center) {
        if (photo != null && !warning) {
            Image(photo, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size))
        } else {
            Text(
                if (warning) "!" else initialOf(name) ?: "#",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.42f).sp,
            )
        }
    }
}

@Composable
fun rememberContactPhoto(uri: String?, size: Dp): ImageBitmap? {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { size.roundToPx() }.coerceAtLeast(48)
    var photo by remember(uri) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(uri, px) {
        photo = if (uri == null) null else withContext(Dispatchers.IO) { ContactLookup.photo(context, uri, px) }
    }
    return photo
}

// ---------- WhatsApp ----------

/** WhatsApp's own app icon (read from the installed app), or a green chat icon if it can't be read. */
@Composable
fun WhatsAppLogo(size: Dp) {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { size.roundToPx() }
    var icon by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(Unit) { icon = withContext(Dispatchers.Default) { WhatsAppBrand.icon(context, px * 2) } }
    val current = icon
    if (current != null) {
        Image(current, contentDescription = null, modifier = Modifier.size(size))
    } else {
        Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null, tint = WhatsAppGreen, modifier = Modifier.size(size))
    }
}

// ---------- Small building blocks ----------

@Composable
fun StatusChip(icon: ImageVector?, text: String, color: Color, filled: Boolean = false) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = if (filled) 0.18f else 0.12f))
            .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, color = color, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Round tinted button with a label underneath (SMS, WhatsApp, Video…). */
@Composable
fun QuickAction(label: String, tint: Color, enabled: Boolean = true, size: Dp = 46.dp, onClick: () -> Unit, icon: @Composable () -> Unit) {
    val palette = LocalDialerPalette.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        Box(
            Modifier.size(size).clip(CircleShape).background(tint.copy(alpha = if (palette.dark) 0.22f else 0.14f)),
            contentAlignment = Alignment.Center,
        ) { icon() }
        Spacer(Modifier.height(4.dp))
        Text(label, color = palette.text, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

@Composable
fun QuickActionIcon(icon: ImageVector, tint: Color) {
    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
}

/** "10:42 AM", "Yesterday", "Monday" or "28 Aug", like phone apps show it. */
fun relativeTime(context: Context, timestamp: Long, now: Long = System.currentTimeMillis()): String {
    if (DateUtils.isToday(timestamp)) return DateFormat.getTimeFormat(context).format(timestamp)
    val cal = Calendar.getInstance().apply { timeInMillis = now }
    cal.add(Calendar.DAY_OF_YEAR, -1)
    val then = Calendar.getInstance().apply { timeInMillis = timestamp }
    if (cal.get(Calendar.YEAR) == then.get(Calendar.YEAR) && cal.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)) {
        return context.getString(R.string.dialer_yesterday)
    }
    if (now - timestamp < 6 * DateUtils.DAY_IN_MILLIS) {
        return DateUtils.formatDateTime(context, timestamp, DateUtils.FORMAT_SHOW_WEEKDAY)
    }
    return DateUtils.formatDateTime(context, timestamp, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_NO_YEAR)
}

/** Talk time like "45s", "2m 15s" or "1h 5m". */
fun talkTime(context: Context, seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return when {
        h > 0 -> context.getString(R.string.dialer_duration_hm, h, m)
        m > 0 -> context.getString(R.string.dialer_duration_ms, m, s)
        else -> context.getString(R.string.dialer_duration_s, s)
    }
}
