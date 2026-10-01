@file:OptIn(ExperimentalFoundationApi::class)

package com.rskusum.whocaller.feature.dialer

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.provider.Settings
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rskusum.whocaller.core.model.CallerLabel
import com.rskusum.whocaller.core.ui.theme.WhoCallerTheme
import com.rskusum.whocaller.core.ui.util.ActionIntents
import com.rskusum.whocaller.core.ui.util.TelecomActions
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale

/** Keypad for placing calls. Handles ACTION_DIAL / tel: links (required for the default phone app role). */
@AndroidEntryPoint
class DialerActivity : ComponentActivity() {

    private val viewModel: DialerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) viewModel.setNumber(numberFrom(intent))
        setContent {
            WhoCallerTheme { DialerScreen(viewModel) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        numberFrom(intent).takeIf { it.isNotEmpty() }?.let(viewModel::setNumber)
    }

    private fun numberFrom(intent: Intent?): String =
        intent?.data?.schemeSpecificPart?.filter { it.isDigit() || it in "+*#," }?.take(DialerViewModel.MAX_LENGTH).orEmpty()
}

// ---------- Look ----------

@Immutable
private data class DialerPalette(
    val background: Brush,
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
)

private val Indigo = Color(0xFF4F46E5)
private val Violet = Color(0xFF8B5CF6)
private val Sky = Color(0xFF38BDF8)
private val CallGreenLight = Color(0xFF3DDC84)
private val CallGreen = Color(0xFF12A150)
private val WarnRed = Color(0xFFE5484D)
private val OkGreen = Color(0xFF1F9D55)

@Composable
private fun dialerPalette(): DialerPalette = if (isSystemInDarkTheme()) {
    DialerPalette(
        background = Brush.verticalGradient(listOf(Color(0xFF0B1026), Color(0xFF151B3D))),
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
    )
} else {
    DialerPalette(
        background = Brush.verticalGradient(listOf(Color(0xFFF7F8FF), Color(0xFFE8ECFF))),
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
    )
}

private val KEYS = listOf(
    '1' to "", '2' to "ABC", '3' to "DEF",
    '4' to "GHI", '5' to "JKL", '6' to "MNO",
    '7' to "PQRS", '8' to "TUV", '9' to "WXYZ",
    '*' to "", '0' to "+", '#' to "",
)

// ---------- Screen ----------

@Composable
private fun DialerScreen(viewModel: DialerViewModel) {
    val context = LocalContext.current
    val view = LocalView.current
    val palette = dialerPalette()
    val number by viewModel.number.collectAsState()
    val lookup by viewModel.lookup.collectAsState()
    val suggestions by viewModel.suggestions.collectAsState()
    val countryIso = remember { countryIso(context) }
    val tones = rememberDtmfTones(context)

    var pendingVideo by remember { mutableStateOf<Boolean?>(null) }
    // Video calling is offered only when a phone account reports CAPABILITY_VIDEO_CALLING.
    var videoSupported by remember { mutableStateOf(TelecomActions.supportsVideoCalling(context)) }
    val whatsApp = remember { TelecomActions.whatsAppPackage(context) != null }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        videoSupported = TelecomActions.supportsVideoCalling(context)
        val video = pendingVideo
        pendingVideo = null
        if (result[Manifest.permission.CALL_PHONE] == true && video != null) {
            TelecomActions.placeCall(context, number, video && videoSupported)
        } else if (result[Manifest.permission.CALL_PHONE] != true) {
            Toast.makeText(context, R.string.dialer_permission_needed, Toast.LENGTH_SHORT).show()
        }
    }

    fun call(video: Boolean) {
        if (number.isBlank()) return
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        if (TelecomActions.canPlaceCalls(context)) {
            TelecomActions.placeCall(context, number, video)
        } else {
            pendingVideo = video
            permissions.launch(arrayOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE))
        }
    }

    fun press(c: Char) {
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        tones.play(c)
        viewModel.append(c)
    }

    val e164 = (lookup as? DialLookup.Found)?.result?.number?.e164
    val isContact = (lookup as? DialLookup.Found)?.result?.label == CallerLabel.CONTACT

    Box(Modifier.fillMaxSize().background(palette.background)) {
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Top bar: title, paste, add contact.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.dialer_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.text,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = {
                    val pasted = clipboardNumber(context)
                    if (pasted.isNullOrEmpty()) {
                        Toast.makeText(context, R.string.dialer_nothing_to_paste, Toast.LENGTH_SHORT).show()
                    } else {
                        viewModel.setNumber(pasted)
                    }
                }) { Icon(Icons.Filled.ContentPaste, stringResource(R.string.dialer_paste), tint = palette.accent) }
                if (number.isNotEmpty() && !isContact) {
                    IconButton(onClick = { ActionIntents.saveContact(context, number) }) {
                        Icon(Icons.Filled.PersonAdd, stringResource(R.string.dialer_add_contact), tint = palette.accent)
                    }
                }
            }

            // Caller card.
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CallerCard(number, formatNumber(number, countryIso), lookup, palette)
            }

            // Matching contacts.
            Box(Modifier.height(44.dp).fillMaxWidth()) {
                if (suggestions.isNotEmpty() && (lookup !is DialLookup.Found || !isContact)) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(suggestions, key = { it.name + it.number }) { s ->
                            SuggestionChip(s, palette) { viewModel.setNumber(s.number) }
                        }
                    }
                }
            }

            // Quick actions.
            Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp).alpha(if (number.isEmpty()) 0f else 1f),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                QuickAction(Icons.AutoMirrored.Filled.Message, stringResource(R.string.dialer_sms), Indigo, palette, number.isNotEmpty()) {
                    ActionIntents.message(context, number)
                }
                if (whatsApp) {
                    QuickAction(Icons.AutoMirrored.Filled.Chat, stringResource(R.string.dialer_whatsapp), Color(0xFF25D366), palette, number.isNotEmpty()) {
                        TelecomActions.openWhatsApp(context, e164 ?: number)
                    }
                }
                if (videoSupported) {
                    QuickAction(Icons.Filled.Videocam, stringResource(R.string.dialer_video_call), Violet, palette, number.isNotEmpty()) { call(video = true) }
                }
            }

            Spacer(Modifier.height(8.dp))

            // Keypad.
            KEYS.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    row.forEach { (digit, letters) ->
                        DialKey(
                            digit = digit,
                            letters = letters,
                            palette = palette,
                            onClick = { press(digit) },
                            onLongClick = when (digit) {
                                '0' -> ({ view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); viewModel.append('+') })
                                else -> null
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // Call row: video · call · delete.
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                    if (videoSupported) {
                        GradientCallButton(
                            icon = Icons.Filled.Videocam,
                            description = stringResource(R.string.dialer_video_call),
                            colors = listOf(Color(0xFF6366F1), Color(0xFF4338CA)),
                            size = 60.dp,
                            enabled = number.isNotBlank(),
                        ) { call(video = true) }
                    }
                }
                GradientCallButton(
                    icon = Icons.Filled.Call,
                    description = stringResource(R.string.dialer_call),
                    colors = listOf(CallGreenLight, CallGreen),
                    size = 76.dp,
                    enabled = number.isNotBlank(),
                ) { call(video = false) }
                Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                    if (number.isNotEmpty()) {
                        val clearLabel = stringResource(R.string.dialer_clear)
                        Box(
                            Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .combinedClickable(
                                    onClickLabel = stringResource(R.string.dialer_backspace),
                                    onLongClickLabel = clearLabel,
                                    onLongClick = { view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); viewModel.clear() },
                                    role = Role.Button,
                                ) { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); viewModel.backspace() },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Backspace, stringResource(R.string.dialer_backspace), tint = palette.subtle, modifier = Modifier.size(28.dp))
                        }
                    }
                }
            }
        }
    }
}

// ---------- Caller card ----------

@Composable
private fun CallerCard(number: String, formatted: String, lookup: DialLookup, palette: DialerPalette) {
    val context = LocalContext.current
    val found = (lookup as? DialLookup.Found)?.result
    val display = found?.let { CallerDisplayFormatter.from(context, number, it) }
    val name = found?.displayName
    val warning = display?.warning == true
    val searching = lookup is DialLookup.Searching

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        AvatarRing(
            initial = name?.firstOrNull { it.isLetter() }?.uppercaseChar(),
            warning = warning,
            active = number.isNotEmpty(),
            spinning = searching,
        )
        Spacer(Modifier.height(12.dp))

        AnimatedContent(
            targetState = Triple(name, display?.label, searching),
            transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(150)) },
            label = "caller",
        ) { (shownName, label, isSearching) ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                if (shownName != null) {
                    Text(
                        shownName,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = palette.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(6.dp))
                }
                when {
                    isSearching -> StatusChip(null, stringResource(R.string.dialer_identifying), palette.accent)
                    label != null && warning -> StatusChip(Icons.Filled.Warning, label, WarnRed)
                    found?.label == CallerLabel.VERIFIED_BUSINESS -> StatusChip(Icons.Filled.Verified, label.orEmpty(), Indigo)
                    shownName != null && label != null -> StatusChip(Icons.Filled.CheckCircle, label, OkGreen)
                    label != null -> StatusChip(null, label, palette.subtle)
                }
            }
        }

        details(found?.info?.carrier, found?.info?.regionCode)?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = palette.subtle)
        }
        Spacer(Modifier.height(10.dp))

        val fontSize = when {
            formatted.length <= 13 -> 34.sp
            formatted.length <= 17 -> 28.sp
            else -> 22.sp
        }
        Text(
            if (number.isEmpty()) stringResource(R.string.dialer_hint) else formatted,
            fontSize = if (number.isEmpty()) 20.sp else fontSize,
            fontWeight = if (number.isEmpty()) FontWeight.Normal else FontWeight.SemiBold,
            letterSpacing = if (number.isEmpty()) 0.sp else 1.sp,
            color = if (number.isEmpty()) palette.subtle else palette.text,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        )
        if (found != null && name != null && !warning) {
            Spacer(Modifier.height(8.dp))
            StatusChip(Icons.Filled.CheckCircle, stringResource(R.string.dialer_details_found), OkGreen, filled = true)
        }
    }
}

@Composable
private fun AvatarRing(initial: Char?, warning: Boolean, active: Boolean, spinning: Boolean) {
    val transition = rememberInfiniteTransition(label = "ring")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(if (spinning) 1_200 else 6_000, easing = LinearEasing)),
        label = "angle",
    )
    val glow by transition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_400), RepeatMode.Reverse),
        label = "glow",
    )
    val ringColors = if (warning) listOf(WarnRed, Color(0xFFFF9F43), WarnRed) else listOf(Indigo, Violet, Sky, Indigo)
    val fill = if (warning) listOf(Color(0xFFFF6B6B), WarnRed) else listOf(Color(0xFF6D7BFF), Indigo)

    Box(Modifier.size(112.dp), contentAlignment = Alignment.Center) {
        // Soft glow.
        Box(
            Modifier
                .size(112.dp)
                .scale(if (active) glow else 0.9f)
                .alpha(if (active) 0.25f else 0.12f)
                .background(Brush.radialGradient(listOf(ringColors.first(), Color.Transparent)), CircleShape),
        )
        // Rotating gradient ring.
        Box(
            Modifier
                .size(100.dp)
                .rotate(if (active) angle else 0f)
                .border(3.dp, Brush.sweepGradient(ringColors), CircleShape),
        )
        Box(
            Modifier.size(86.dp).clip(CircleShape).background(Brush.linearGradient(fill)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                when {
                    warning -> "!"
                    initial != null -> initial.toString()
                    else -> "?"
                },
                color = Color.White,
                fontSize = 38.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun StatusChip(icon: ImageVector?, text: String, color: Color, filled: Boolean = false) {
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

@Composable
private fun SuggestionChip(s: DialSuggestion, palette: DialerPalette, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(palette.actionBg)
            .clickable(onClick = onClick)
            .padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(28.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Color(0xFF6D7BFF), Indigo))),
            contentAlignment = Alignment.Center,
        ) {
            Text(s.name.firstOrNull { it.isLetter() }?.uppercase() ?: "#", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(8.dp))
        Column {
            Text(s.name, color = palette.text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(s.number, color = palette.subtle, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

@Composable
private fun QuickAction(icon: ImageVector, label: String, color: Color, palette: DialerPalette, enabled: Boolean, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(6.dp),
    ) {
        Box(
            Modifier.size(46.dp).clip(CircleShape).background(color.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp)) }
        Spacer(Modifier.height(4.dp))
        Text(label, color = palette.text, style = MaterialTheme.typography.labelMedium)
    }
}

// ---------- Keys and buttons ----------

@Composable
private fun DialKey(digit: Char, letters: String, palette: DialerPalette, onClick: () -> Unit, onLongClick: (() -> Unit)?) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, label = "key")
    val description = if (letters.isEmpty() || letters == "+") digit.toString() else "$digit $letters"
    Box(
        Modifier
            .size(72.dp)
            .scale(scale)
            .shadow(if (pressed) 1.dp else 6.dp, CircleShape, ambientColor = palette.keyShadow, spotColor = palette.keyShadow)
            .clip(CircleShape)
            .background(Brush.verticalGradient(listOf(palette.keyTop, palette.keyBottom)))
            .border(1.dp, palette.keyBorder, CircleShape)
            .combinedClickable(
                interactionSource = interaction,
                indication = ripple(color = palette.accent),
                role = Role.Button,
                onLongClick = onLongClick,
                onClick = onClick,
            )
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                digit.toString(),
                fontSize = if (digit == '*') 34.sp else 30.sp,
                fontWeight = FontWeight.Medium,
                color = palette.digit,
                lineHeight = 32.sp,
            )
            if (letters.isNotEmpty()) {
                Text(letters, fontSize = 10.sp, letterSpacing = 1.5.sp, color = palette.letters, lineHeight = 12.sp)
            }
        }
    }
}

@Composable
private fun GradientCallButton(icon: ImageVector, description: String, colors: List<Color>, size: Dp, enabled: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.92f else 1f, label = "call")
    Box(
        Modifier
            .size(size)
            .scale(scale)
            .alpha(if (enabled) 1f else 0.55f)
            .shadow(if (enabled) 14.dp else 2.dp, CircleShape, ambientColor = colors.last(), spotColor = colors.last())
            .clip(CircleShape)
            .background(Brush.linearGradient(colors))
            .combinedClickable(
                interactionSource = interaction,
                indication = ripple(color = Color.White),
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.42f))
    }
}

// ---------- Helpers ----------

/** Plays the keypad tone for a digit when the system "Dial pad tones" setting is on. */
private class DtmfTones(private val generator: ToneGenerator?, private val enabled: Boolean) {
    fun play(c: Char) {
        if (!enabled || generator == null) return
        val tone = when (c) {
            in '0'..'9' -> ToneGenerator.TONE_DTMF_0 + (c - '0')
            '*' -> ToneGenerator.TONE_DTMF_S
            '#' -> ToneGenerator.TONE_DTMF_P
            else -> return
        }
        generator.startTone(tone, TONE_MS)
    }

    fun release() = generator?.release()

    companion object {
        const val TONE_MS = 120
    }
}

@Composable
private fun rememberDtmfTones(context: Context): DtmfTones {
    val tones = remember {
        val enabled = Settings.System.getInt(context.contentResolver, Settings.System.DTMF_TONE_WHEN_DIALING, 1) == 1
        val generator = if (enabled) runCatching { ToneGenerator(AudioManager.STREAM_DTMF, 70) }.getOrNull() else null
        DtmfTones(generator, enabled)
    }
    DisposableEffect(tones) { onDispose { tones.release() } }
    return tones
}

private fun countryIso(context: Context): String {
    val tm = context.getSystemService(TelephonyManager::class.java)
    return listOfNotNull(tm?.networkCountryIso, tm?.simCountryIso, Locale.getDefault().country)
        .firstOrNull { it.isNotBlank() }
        ?.uppercase(Locale.ROOT)
        ?: "US"
}

private fun formatNumber(number: String, countryIso: String): String =
    if (number.length < 4 || number.any { it in "*#," }) number else PhoneNumberUtils.formatNumber(number, countryIso) ?: number

private fun details(carrier: String?, regionCode: String?): String? {
    val country = regionCode?.takeIf { it.length == 2 }?.let { Locale("", it).displayCountry }
    return listOfNotNull(carrier?.takeIf { it.isNotBlank() }, country?.takeIf { it.isNotBlank() })
        .joinToString(" · ")
        .ifEmpty { null }
}

private fun clipboardNumber(context: Context): String? {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return null
    val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString() ?: return null
    val cleaned = text.filter { it.isDigit() || it == '+' }
    return cleaned.takeIf { it.count(Char::isDigit) >= 3 }
}
