@file:OptIn(ExperimentalFoundationApi::class)

package com.rskusum.whocaller.feature.dialer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Voicemail
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rskusum.whocaller.core.model.CallerLabel
import com.rskusum.whocaller.core.model.IdentityType
import com.rskusum.whocaller.core.ui.util.ActionIntents
import com.rskusum.whocaller.core.ui.util.TelecomActions
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private val KEYS = listOf(
    '1' to "", '2' to "ABC", '3' to "DEF",
    '4' to "GHI", '5' to "JKL", '6' to "MNO",
    '7' to "PQRS", '8' to "TUV", '9' to "WXYZ",
    '*' to "", '0' to "+", '#' to "",
)

/** Everything the keypad knows about the typed number, derived once per recomposition. */
private class CallerView(
    val name: String?,
    val photoUri: String?,
    val warning: Boolean,
    val label: String?,
    val callerLabel: CallerLabel?,
    val business: Boolean,
    val company: String?,
    val place: String?,
    val international: String?,
    val contactId: Long?,
    val verified: Boolean = false,
)

@Composable
fun KeypadTab(
    viewModel: DialerViewModel,
    actions: CallActions,
    onShowDetails: (DetailsTarget) -> Unit,
    onShowRecents: () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    // Re-read the SIMs whenever the app comes back (SIM settings may have changed).
    var simTick by remember { mutableIntStateOf(0) }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { simTick++ }
    val dualSim: List<SimOption>? = remember(simTick) {
        Sims.list(context).takeIf { it.size >= 2 && Sims.default(context) == null }?.take(2)
    }
    val palette = LocalDialerPalette.current
    val input by viewModel.input.collectAsState()
    val lookup by viewModel.lookup.collectAsState()
    val suggestions by viewModel.suggestions.collectAsState()
    val blocked by viewModel.blocked.collectAsState()
    val number = input.text
    val tones = rememberDtmfTones(context)
    val whatsApp = remember { TelecomActions.whatsAppPackage(context) != null }
    val formatted = remember(number) { NumberTools.format(number, viewModel.countryIso) }
    val emergency = remember(number) { NumberTools.isEmergency(context, number) }
    val caller = callerView(context, number, lookup)
    var menu by remember { mutableStateOf(false) }
    var numberMenu by remember { mutableStateOf(false) }

    fun press(c: Char) {
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        tones.play(c)
        viewModel.insert(c)
    }

    fun details() {
        if (number.isNotBlank()) onShowDetails(DetailsTarget(number = number, contactId = caller?.contactId))
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Fit the keypad to the screen: keys shrink on short phones so nothing gets cut off.
        val reserved = 330.dp + if (number.isEmpty()) 0.dp else 110.dp
        val keySize = ((maxHeight - reserved - 120.dp) / 4 - 10.dp).coerceIn(54.dp, 76.dp)

        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            // Top actions (no title).
            Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                IconButton(onClick = {
                    val pasted = clipboardNumber(context)
                    if (pasted.isNullOrEmpty()) {
                        Toast.makeText(context, R.string.dialer_nothing_to_paste, Toast.LENGTH_SHORT).show()
                    } else {
                        viewModel.insertText(pasted)
                    }
                }) { Icon(Icons.Filled.ContentPaste, stringResource(R.string.dialer_paste), tint = palette.accent) }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.dialer_more_options), tint = palette.accent) }
                    KeypadMenu(
                        expanded = menu,
                        onDismiss = { menu = false },
                        hasNumber = number.isNotEmpty(),
                        isContact = caller?.contactId != null,
                        blocked = blocked,
                        onCopy = { copyNumber(context, number) },
                        onShare = { shareNumber(context, number) },
                        onAdd = { ActionIntents.saveContact(context, number) },
                        onDetails = ::details,
                        onBlock = { viewModel.toggleBlock(number, caller?.name) },
                        onReport = { openWhoCaller(context, "report/${numberPath(number)}") },
                        onSearch = { openWhoCaller(context, "number/${numberPath(number)}") },
                        onVoicemail = actions::voicemail,
                    )
                }
            }

            // Caller card. Swipe up: details · swipe down: recents · swipe the avatar sideways: WhoCaller profile.
            val density = LocalDensity.current
            val swipe = with(density) { 56.dp.toPx() }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .pointerInput(number, caller?.contactId) {
                        var total = 0f
                        detectVerticalDragGestures(
                            onDragStart = { total = 0f },
                            onDragEnd = {
                                when {
                                    total < -swipe -> details()
                                    total > swipe -> onShowRecents()
                                }
                            },
                        ) { change, amount ->
                            change.consume()
                            total += amount
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (number.isEmpty()) {
                    AnimatedLogo(maxSize = 150.dp)
                } else {
                    CallerCard(
                        caller = caller,
                        searching = lookup is DialLookup.Searching,
                        onSwipeAvatar = { openWhoCaller(context, "number/${numberPath(number)}") },
                        swipeThreshold = swipe,
                    )
                }
            }

            // The number: tap to move the cursor, long-press to copy/paste/share.
            Box {
                Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.width(44.dp))
                    NumberDisplay(
                        input = input,
                        formatted = formatted,
                        modifier = Modifier.weight(1f),
                        onCursor = viewModel::setCursor,
                        onLongPress = {
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            numberMenu = true
                        },
                    )
                    Box(Modifier.width(44.dp), contentAlignment = Alignment.Center) {
                        if (number.isNotEmpty()) {
                            IconButton(onClick = viewModel::clear) {
                                Box(Modifier.size(26.dp).clip(CircleShape).background(palette.subtle.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Filled.Close, stringResource(R.string.dialer_clear), tint = palette.subtle, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
                DropdownMenu(expanded = numberMenu, onDismissRequest = { numberMenu = false }) {
                    if (number.isNotEmpty()) {
                        MenuItem(Icons.Filled.ContentCopy, R.string.dialer_copy) { numberMenu = false; copyNumber(context, number) }
                    }
                    MenuItem(Icons.Filled.ContentPaste, R.string.dialer_paste) {
                        numberMenu = false
                        clipboardNumber(context)?.let(viewModel::insertText)
                            ?: Toast.makeText(context, R.string.dialer_nothing_to_paste, Toast.LENGTH_SHORT).show()
                    }
                    if (number.isNotEmpty()) {
                        MenuItem(Icons.Filled.Share, R.string.dialer_share) { numberMenu = false; shareNumber(context, number) }
                        MenuItem(Icons.Filled.Close, R.string.dialer_clear) { numberMenu = false; viewModel.clear() }
                    }
                }
            }

            // Status line under the number.
            AnimatedVisibility(number.isNotEmpty(), enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                Box(Modifier.fillMaxWidth().height(40.dp), contentAlignment = Alignment.Center) {
                    when {
                        emergency -> StatusChip(Icons.Filled.LocalHospital, stringResource(R.string.dialer_emergency), WarnRed, filled = true)
                        caller?.contactId != null || (caller?.name != null && !caller.warning) ->
                            StatusChip(Icons.Filled.CheckCircle, stringResource(R.string.dialer_details_found), OkGreen, filled = true)
                        suggestions.isNotEmpty() -> LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(suggestions, key = { it.contactId.toString() + it.number }) { s ->
                                SuggestionChip(s) { viewModel.setNumber(s.number) }
                            }
                        }
                        caller?.international != null && !number.startsWith("+") && caller.international.filter(Char::isDigit) != number.filter(Char::isDigit) ->
                            StatusChip(Icons.Filled.Public, caller.international, palette.accent)
                    }
                }
            }

            // Quick actions.
            AnimatedVisibility(number.isNotEmpty(), enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    QuickAction(stringResource(R.string.dialer_sms), Indigo, onClick = { ActionIntents.message(context, number) }) {
                        QuickActionIcon(Icons.AutoMirrored.Filled.Message, Indigo)
                    }
                    if (whatsApp) {
                        QuickAction(stringResource(R.string.dialer_whatsapp), WhatsAppGreen, onClick = { actions.openWhatsAppChat(caller?.international ?: number) }) {
                            WhatsAppLogo(26.dp)
                        }
                    }
                    // WHOCALLER VIDEO (experimental)
                    QuickAction(stringResource(com.rskusum.whocaller.core.ui.R.string.action_whocaller_video), Violet, onClick = { TelecomActions.whoCallerVideo(context, number) }) {
                        QuickActionIcon(Icons.Filled.Videocam, Violet)
                    }
                    if (caller?.warning == true) {
                        QuickAction(stringResource(if (blocked) R.string.dialer_unblock else R.string.dialer_block), WarnRed, onClick = { viewModel.toggleBlock(number, caller.name) }) {
                            QuickActionIcon(Icons.Filled.Block, WarnRed)
                        }
                        QuickAction(stringResource(R.string.dialer_report), Color(0xFFF59E0B), onClick = { openWhoCaller(context, "report/${numberPath(number)}") }) {
                            QuickActionIcon(Icons.Filled.Flag, Color(0xFFF59E0B))
                        }
                    } else {
                        QuickAction(stringResource(R.string.dialer_details), Sky, onClick = ::details) {
                            QuickActionIcon(Icons.Filled.Info, Sky)
                        }
                    }
                }
            }

            Spacer(Modifier.height(6.dp))

            // Keypad.
            KEYS.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    row.forEach { (digit, letters) ->
                        DialKey(
                            digit = digit,
                            letters = letters,
                            size = keySize,
                            onClick = { press(digit) },
                            onLongClick = when (digit) {
                                '0' -> ({ view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); viewModel.insert('+') })
                                '1' -> ({ view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); actions.voicemail() })
                                else -> null
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // Call (long-press: choose SIM) · Delete; the empty slot keeps the call button centred.
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.size(60.dp))
                // Two SIMs and no default calling SIM ("ask every time"): one call button per SIM,
                // with its network underneath, so the right number is used in one tap.
                if (dualSim != null && !emergency) {
                    dualSim.forEach { sim ->
                        SimCallButton(sim, enabled = number.isNotBlank()) {
                            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            actions.callOn(number, sim.handle)
                        }
                    }
                } else {
                    GradientCallButton(
                        icon = Icons.Filled.Call,
                        description = stringResource(if (emergency) R.string.dialer_emergency_call else R.string.dialer_call),
                        colors = if (emergency) listOf(Color(0xFFFF6B6B), WarnRed) else listOf(CallGreenLight, CallGreen),
                        size = 76.dp,
                        enabled = number.isNotBlank(),
                        onLongClickLabel = stringResource(R.string.dialer_choose_sim),
                        onLongClick = { actions.call(number, pickSim = true) },
                        onClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            actions.call(number)
                        },
                    )
                }
                Box(Modifier.size(60.dp), contentAlignment = Alignment.Center) {
                    if (number.isNotEmpty()) {
                        Box(
                            Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .combinedClickable(
                                    onClickLabel = stringResource(R.string.dialer_backspace),
                                    onLongClickLabel = stringResource(R.string.dialer_clear),
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

@Composable
private fun callerView(context: Context, number: String, lookup: DialLookup): CallerView? {
    val found = lookup as? DialLookup.Found ?: return null
    val result = found.result
    val display = CallerDisplayFormatter.from(context, number, result)
    val contact = found.contact
    val name = contact?.name ?: result.displayName
    val isBusiness = result.label == CallerLabel.VERIFIED_BUSINESS || result.label == CallerLabel.BUSINESS ||
        result.info?.identityType == IdentityType.BUSINESS || found.business != null
    val company = contact?.company?.let { c -> listOfNotNull(contact.jobTitle, c).joinToString(" · ") }
        ?: found.business?.category
    val place = listOfNotNull(
        result.info?.carrier?.takeIf { it.isNotBlank() }
            ?: found.facts?.carrier?.let { context.getString(com.rskusum.whocaller.core.ui.R.string.operator_original_short, it) },
        contact?.city ?: found.business?.address ?: found.facts?.location,
    ).distinct().joinToString(" · ").ifEmpty { null }
    return CallerView(
        name = name,
        photoUri = contact?.photoUri,
        warning = display.warning,
        label = display.label.takeIf { result.number != null || contact != null },
        callerLabel = result.label,
        business = isBusiness,
        company = company,
        place = place,
        international = found.facts?.international,
        contactId = contact?.contactId,
        verified = result.info?.whoCallerVerified == true,
    )
}

// ---------- Caller card ----------

@Composable
private fun CallerCard(caller: CallerView?, searching: Boolean, onSwipeAvatar: () -> Unit, swipeThreshold: Float) {
    val palette = LocalDialerPalette.current
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        // Avatar shrinks to leave room for the text lines on short screens.
        val avatar = (maxHeight - 96.dp).coerceIn(48.dp, 112.dp)
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Box(
                Modifier.pointerInput(Unit) {
                    var total = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { total = 0f },
                        onDragEnd = { if (kotlin.math.abs(total) > swipeThreshold) onSwipeAvatar() },
                    ) { change, amount ->
                        change.consume()
                        total += amount
                    }
                },
            ) {
                AvatarRing(size = avatar, warning = caller?.warning == true, spinning = searching) {
                    if (caller == null) {
                        Box(Modifier.fillMaxSize().background(Brush.linearGradient(avatarColors(null))), contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.Person, contentDescription = null, tint = Color.White, modifier = Modifier.size(avatar * 0.45f))
                        }
                    } else {
                        ContactAvatar(caller.name, caller.photoUri, avatar * 0.78f, warning = caller.warning)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            AnimatedContent(
                targetState = Triple(caller?.name?.let { it to caller.verified }, caller?.label, searching),
                transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(150)) },
                label = "caller",
            ) { (named, label, isSearching) ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    if (named != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                named.first,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = palette.text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            if (named.second) {
                                Spacer(Modifier.width(4.dp))
                                com.rskusum.whocaller.core.ui.component.VerifiedTick(20.dp)
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                    when {
                        isSearching -> SearchingChip()
                        caller?.warning == true && label != null -> StatusChip(Icons.Filled.Warning, label, WarnRed)
                        caller?.business == true -> StatusChip(Icons.Filled.Verified, stringResource(R.string.dialer_business_profile), Indigo)
                        caller?.contactId != null -> StatusChip(Icons.Filled.CheckCircle, stringResource(R.string.dialer_saved_contact), OkGreen)
                        label != null -> StatusChip(null, label, palette.subtle)
                    }
                }
            }
            caller?.company?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, color = palette.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            caller?.place?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = palette.subtle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun SearchingChip() {
    val palette = LocalDialerPalette.current
    val transition = rememberInfiniteTransition(label = "dots")
    val phase by transition.animateFloat(0f, 3f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "phase")
    val dots = ".".repeat(phase.toInt() + 1).padEnd(3, ' ')
    StatusChip(Icons.Filled.Search, stringResource(R.string.dialer_identifying_short) + dots, palette.accent)
}

/** Gradient ring that slowly turns (fast while searching), a soft glow and an orbiting sparkle. */
@Composable
private fun AvatarRing(size: Dp, warning: Boolean, spinning: Boolean, content: @Composable () -> Unit) {
    val transition = rememberInfiniteTransition(label = "ring")
    val angle by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(if (spinning) 1_200 else 6_000, easing = LinearEasing)), label = "angle")
    val glow by transition.animateFloat(0.85f, 1f, infiniteRepeatable(tween(1_400), RepeatMode.Reverse), label = "glow")
    val ringColors = if (warning) listOf(WarnRed, Color(0xFFFF9F43), WarnRed) else listOf(Indigo, Violet, Sky, Indigo)
    val radiusPx = with(LocalDensity.current) { (size / 2).toPx() }
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(size).scale(glow).alpha(0.28f)
                .background(Brush.radialGradient(listOf(ringColors.first(), Color.Transparent)), CircleShape),
        )
        Box(Modifier.size(size * 0.9f).rotate(angle).border(3.dp, Brush.sweepGradient(ringColors), CircleShape))
        Box(Modifier.size(size * 0.78f).clip(CircleShape), contentAlignment = Alignment.Center) { content() }
        if (!warning) {
            val rad = Math.toRadians((angle * 0.6 - 45).toDouble())
            Icon(
                Icons.Filled.AutoAwesome,
                contentDescription = null,
                tint = Violet,
                modifier = Modifier
                    .offset { IntOffset((cos(rad) * radiusPx * 0.86).roundToInt(), (sin(rad) * radiusPx * 0.86).roundToInt()) }
                    .size(size * 0.14f)
                    .alpha(glow),
            )
        }
    }
}

/** App icon breathing inside a turning ring — shown before anything is typed. */
@Composable
private fun AnimatedLogo(maxSize: Dp) {
    BoxWithConstraints(contentAlignment = Alignment.Center) {
        val size = minOf(maxSize, maxHeight - 8.dp).coerceAtLeast(56.dp)
        val transition = rememberInfiniteTransition(label = "logo")
        val breathe by transition.animateFloat(0.94f, 1.04f, infiniteRepeatable(tween(1_600), RepeatMode.Reverse), label = "breathe")
        AvatarRing(size = size, warning = false, spinning = false) {
            Image(
                painterResource(R.drawable.dialer_logo),
                contentDescription = stringResource(R.string.dialer_label),
                modifier = Modifier.fillMaxSize().scale(breathe),
            )
        }
    }
}

// ---------- Number field ----------

@Composable
private fun NumberDisplay(
    input: DialInput,
    formatted: String,
    modifier: Modifier,
    onCursor: (Int) -> Unit,
    onLongPress: () -> Unit,
) {
    val palette = LocalDialerPalette.current
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val blink = rememberInfiniteTransition(label = "cursor")
    val cursorAlpha by blink.animateFloat(1f, 0f, infiniteRepeatable(tween(530, easing = { if (it < 0.5f) 0f else 1f }), RepeatMode.Reverse), label = "blink")
    val empty = input.text.isEmpty()
    val fontSize = when {
        empty -> 20.sp
        formatted.length <= 13 -> 34.sp
        formatted.length <= 17 -> 28.sp
        formatted.length <= 22 -> 22.sp
        else -> 18.sp
    }
    val hint = stringResource(R.string.dialer_hint)
    val shown = if (empty) hint else formatted
    val cursorAt = Editing.toFormatted(formatted, input.cursor)
    Text(
        shown,
        fontSize = fontSize,
        fontWeight = if (empty) FontWeight.Normal else FontWeight.SemiBold,
        letterSpacing = if (empty) 0.sp else 1.sp,
        color = if (empty) palette.subtle else palette.text,
        textAlign = TextAlign.Center,
        maxLines = 1,
        softWrap = false,
        onTextLayout = { layout = it },
        modifier = modifier
            .semantics { liveRegion = LiveRegionMode.Polite }
            .pointerInput(formatted) {
                detectTapGestures(
                    onTap = { pos ->
                        val l = layout
                        if (!empty && l != null) onCursor(Editing.toRaw(formatted, l.getOffsetForPosition(pos)))
                    },
                    onLongPress = { onLongPress() },
                )
            }
            .drawWithContent {
                drawContent()
                val l = layout ?: return@drawWithContent
                if (!empty && cursorAt <= l.layoutInput.text.length) {
                    val r = l.getCursorRect(cursorAt)
                    drawLine(
                        palette.accent.copy(alpha = cursorAlpha),
                        Offset(r.left, r.top + 6f),
                        Offset(r.left, r.bottom - 6f),
                        strokeWidth = 2.dp.toPx(),
                    )
                }
            },
    )
}

// ---------- Keys and buttons ----------

@Composable
private fun DialKey(digit: Char, letters: String, size: Dp, onClick: () -> Unit, onLongClick: (() -> Unit)?) {
    val palette = LocalDialerPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, label = "key")
    val description = when (digit) {
        '1' -> stringResource(R.string.dialer_key_voicemail)
        '0' -> stringResource(R.string.dialer_key_zero)
        else -> if (letters.isEmpty()) digit.toString() else "$digit $letters"
    }
    Box(
        Modifier
            .size(size)
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
                fontSize = (size.value * if (digit == '*') 0.46f else 0.4f).sp,
                fontWeight = FontWeight.Medium,
                color = palette.digit,
                lineHeight = (size.value * 0.44f).sp,
            )
            when {
                digit == '1' -> Icon(Icons.Filled.Voicemail, contentDescription = null, tint = palette.letters, modifier = Modifier.size(14.dp))
                letters.isNotEmpty() -> Text(letters, fontSize = 10.sp, letterSpacing = 1.5.sp, color = palette.letters, lineHeight = 12.sp)
            }
        }
    }
}

@Composable
fun GradientCallButton(
    icon: ImageVector,
    description: String,
    colors: List<Color>,
    size: Dp,
    enabled: Boolean,
    onLongClickLabel: String? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
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
                onLongClickLabel = onLongClickLabel,
                onLongClick = onLongClick,
                onClick = onClick,
            )
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.42f))
    }
}

@Composable
private fun SuggestionChip(s: DialSuggestion, onClick: () -> Unit) {
    val palette = LocalDialerPalette.current
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(palette.actionBg)
            .clickable(onClick = onClick)
            .padding(start = 4.dp, end = 12.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ContactAvatar(s.name, s.photoUri, 28.dp)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(s.name, color = palette.text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(s.number, color = palette.subtle, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

@Composable
private fun KeypadMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    hasNumber: Boolean,
    isContact: Boolean,
    blocked: Boolean,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onAdd: () -> Unit,
    onDetails: () -> Unit,
    onBlock: () -> Unit,
    onReport: () -> Unit,
    onSearch: () -> Unit,
    onVoicemail: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        fun run(action: () -> Unit): () -> Unit = { onDismiss(); action() }
        if (hasNumber) {
            MenuItem(Icons.Filled.ContentCopy, R.string.dialer_copy, run(onCopy))
            MenuItem(Icons.Filled.Share, R.string.dialer_share, run(onShare))
            MenuItem(Icons.Filled.Info, R.string.dialer_details, run(onDetails))
            if (!isContact) MenuItem(Icons.Filled.PersonAdd, R.string.dialer_add_contact, run(onAdd))
            MenuItem(Icons.Filled.Search, R.string.dialer_search_whocaller, run(onSearch))
            MenuItem(Icons.Filled.Block, if (blocked) R.string.dialer_unblock else R.string.dialer_block, run(onBlock))
            MenuItem(Icons.Filled.Flag, R.string.dialer_report, run(onReport))
        }
        MenuItem(Icons.Filled.Voicemail, R.string.dialer_call_voicemail, run(onVoicemail))
    }
}

@Composable
internal fun MenuItem(icon: ImageVector, text: Int, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(text)) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        onClick = onClick,
    )
}

// ---------- Helpers ----------

/** Number as a safe whocaller:// path segment. */
internal fun numberPath(number: String): String = android.net.Uri.encode(number.filter { it.isDigit() || it == '+' })

internal fun copyNumber(context: Context, number: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.dialer_phone_number), number))
    // Android 13+ shows its own confirmation.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(context, R.string.dialer_copied, Toast.LENGTH_SHORT).show()
}

internal fun shareNumber(context: Context, number: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, number)
    runCatching { context.startActivity(Intent.createChooser(send, context.getString(R.string.dialer_share))) }
}

private fun clipboardNumber(context: Context): String? {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return null
    val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString() ?: return null
    val cleaned = text.filter { it.isDigit() || it == '+' }
    return cleaned.takeIf { it.count(Char::isDigit) >= 3 }
}

/** Plays the keypad tone for a digit when the system "Dial pad tones" setting is on. */
private class DtmfTones(private val generator: ToneGenerator?) {
    fun play(c: Char) {
        val g = generator ?: return
        val tone = when (c) {
            in '0'..'9' -> ToneGenerator.TONE_DTMF_0 + (c - '0')
            '*' -> ToneGenerator.TONE_DTMF_S
            '#' -> ToneGenerator.TONE_DTMF_P
            else -> return
        }
        g.startTone(tone, 120)
    }

    fun release() = generator?.release()
}

@Composable
private fun rememberDtmfTones(context: Context): DtmfTones {
    val tones = remember {
        val enabled = Settings.System.getInt(context.contentResolver, Settings.System.DTMF_TONE_WHEN_DIALING, 1) == 1
        DtmfTones(if (enabled) runCatching { ToneGenerator(AudioManager.STREAM_DTMF, 70) }.getOrNull() else null)
    }
    DisposableEffect(tones) { onDispose { tones.release() } }
    return tones
}

/** Call button for one SIM: green button with the slot number, "SIM 1" and the network below. */
@Composable
private fun SimCallButton(sim: SimOption, enabled: Boolean, onClick: () -> Unit) {
    val palette = LocalDialerPalette.current
    val slot = sim.slot ?: 1
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(76.dp)) {
        Box(contentAlignment = Alignment.BottomEnd) {
            GradientCallButton(
                icon = Icons.Filled.Call,
                description = stringResource(R.string.dialer_call_with_sim, slot, sim.network ?: sim.label),
                colors = listOf(CallGreenLight, CallGreen),
                size = 64.dp,
                enabled = enabled,
                onClick = onClick,
            )
            Box(
                Modifier.size(22.dp).clip(CircleShape).background(Color.White),
                contentAlignment = Alignment.Center,
            ) { Text(slot.toString(), color = CallGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
        }
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.dialer_sim_n, slot), color = palette.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Text(sim.network ?: sim.label, color = palette.subtle, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
