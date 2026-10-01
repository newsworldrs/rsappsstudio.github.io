package com.rskusum.whocaller.feature.dialer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Bundle
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.PhoneAccountHandle
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMerge
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AddIcCall
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.SwapCalls
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rskusum.whocaller.core.ui.theme.WhoCallerTheme
import com.rskusum.whocaller.core.ui.util.formatDuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Full-screen call UI used while WhoCaller is the default phone app. Shows over the lock screen. */
class InCallActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        handleAction(intent)

        setContent {
            WhoCallerTheme(dynamicColor = false) {
                CompositionLocalProvider(LocalDialerPalette provides remember { dialerPalette(true) }) {
                    val calls by CallManager.calls.collectAsStateWithLifecycle()
                    val audio by CallManager.audio.collectAsStateWithLifecycle()
                    val visible = calls.filterNot { it.isChild }
                    val current = visible.firstOrNull { it.isRinging }
                        ?: visible.firstOrNull { it.state != Call.STATE_HOLDING && it.state != Call.STATE_DISCONNECTED }
                        ?: visible.firstOrNull()
                    LaunchedEffect(calls.isEmpty()) {
                        if (calls.isEmpty()) {
                            delay(600)
                            finish()
                        }
                    }
                    Box(Modifier.fillMaxSize().background(CALL_BACKGROUND)) {
                        if (current != null) InCallScreen(current, visible.filter { it.call != current.call }, audio)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAction(intent)
    }

    private fun handleAction(intent: Intent?) {
        if (intent?.action == ACTION_ANSWER) {
            CallManager.calls.value.firstOrNull { it.isRinging }?.let { CallManager.answer(it.call, video = false) }
        }
    }

    companion object {
        const val ACTION_ANSWER = "com.rskusum.whocaller.action.ANSWER"

        fun intent(context: Context): Intent =
            Intent(context, InCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
    }
}

private val CALL_BACKGROUND = Brush.verticalGradient(listOf(Color(0xFF0B1026), Color(0xFF1B1F4A), Color(0xFF0B1026)))
private val GLASS = Color.White.copy(alpha = 0.12f)
private val END_RED = listOf(Color(0xFFFF6B6B), Color(0xFFD92D20))

@Composable
private fun InCallScreen(call: CallUi, others: List<CallUi>, audio: CallAudioState?) {
    val context = LocalContext.current
    var showKeypad by rememberSaveable { mutableStateOf(false) }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && call.isRinging) CallManager.answer(call.call, video = true)
    }
    // Contact photo and offline location/operator for the other party.
    var photoUri by remember(call.number) { mutableStateOf<String?>(null) }
    var facts by remember(call.number) { mutableStateOf<NumberFacts?>(null) }
    LaunchedEffect(call.number) {
        val n = call.number ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            photoUri = ContactLookup.byNumber(context, n)?.photoUri
            facts = NumberTools.facts(n, NumberTools.countryIso(context))
        }
    }
    val activeOther = others.firstOrNull { it.isActive }
    val heldOther = others.firstOrNull { it.isHeld }

    Box(Modifier.fillMaxSize()) {
        if (call.isVideo && call.isActive) VideoSurfaces(call.call)

        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Another call waiting on hold.
            (heldOther ?: activeOther)?.takeIf { !call.isRinging }?.let { other ->
                OtherCallBanner(other, canSwap = true)
                Spacer(Modifier.height(12.dp))
            }
            Spacer(Modifier.height(24.dp))
            if (!call.isVideo || !call.isActive) {
                Box(
                    Modifier.size(132.dp).border(3.dp, Brush.sweepGradient(if (call.display.warning) listOf(WarnRed, Color(0xFFFF9F43), WarnRed) else listOf(Indigo, Violet, Sky, Indigo)), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (call.isConference) {
                        Box(Modifier.size(112.dp).clip(CircleShape).background(Brush.linearGradient(avatarColors("conference"))), contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.Groups, contentDescription = null, tint = Color.White, modifier = Modifier.size(56.dp))
                        }
                    } else {
                        ContactAvatar(call.display.title.takeIf { call.display.number != null }, photoUri, 112.dp, warning = call.display.warning)
                    }
                }
                Spacer(Modifier.height(18.dp))
            }
            Text(
                if (call.isConference) stringResource(R.string.call_conference, call.childCount) else call.display.title,
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            call.display.number?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.8f)) }
            listOfNotNull(facts?.location, facts?.carrier).joinToString(" · ").takeIf { it.isNotEmpty() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.65f))
            }
            call.display.label?.let { label ->
                Spacer(Modifier.height(10.dp))
                StatusChip(if (call.display.warning) Icons.Filled.Warning else null, label, if (call.display.warning) Color(0xFFFF8A80) else Color.White)
            }
            Spacer(Modifier.height(12.dp))
            CallStatus(call)
            Spacer(Modifier.weight(1f))

            when {
                call.needsAccount -> AccountChooser(call)
                call.isRinging -> RingingActions(call, activeOther != null) {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        CallManager.answer(call.call, video = true)
                    } else {
                        cameraLauncher.launch(Manifest.permission.CAMERA)
                    }
                }
                else -> {
                    if (showKeypad) {
                        DtmfPad { CallManager.dtmf(call.call, it) }
                        TextButton(onClick = { showKeypad = false }) { Text(stringResource(R.string.call_hide_keypad), color = Color.White) }
                    } else {
                        val muted = audio?.isMuted == true
                        val speaker = audio?.route == CallAudioState.ROUTE_SPEAKER
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            Toggle(Icons.Filled.MicOff, stringResource(R.string.call_mute), muted) { CallManager.setMuted(!muted) }
                            Toggle(Icons.AutoMirrored.Filled.VolumeUp, stringResource(R.string.call_speaker), speaker) { CallManager.setSpeaker(!speaker) }
                            Toggle(Icons.Filled.Dialpad, stringResource(R.string.call_keypad), false) { showKeypad = true }
                        }
                        Spacer(Modifier.height(18.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            Toggle(Icons.Filled.Pause, stringResource(R.string.call_hold), call.isHeld, enabled = call.canHold) { CallManager.toggleHold(call.call) }
                            Toggle(Icons.Filled.AddIcCall, stringResource(R.string.call_add), false) { addCall(context) }
                            when {
                                call.canMerge && others.isNotEmpty() ->
                                    Toggle(Icons.AutoMirrored.Filled.CallMerge, stringResource(R.string.call_merge), false) { CallManager.merge(call.call) }
                                others.isNotEmpty() ->
                                    Toggle(Icons.Filled.SwapCalls, stringResource(R.string.call_swap), false) { CallManager.swap() }
                                else ->
                                    Toggle(Icons.AutoMirrored.Filled.CallMerge, stringResource(R.string.call_merge), false, enabled = false) {}
                            }
                        }
                    }
                    Spacer(Modifier.height(28.dp))
                    RoundAction(Icons.Filled.CallEnd, stringResource(R.string.call_end), END_RED) { CallManager.hangUp(call.call) }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun RingingActions(call: CallUi, hasActiveCall: Boolean, onAnswerVideo: () -> Unit) {
    val green = listOf(CallGreenLight, CallGreen)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        RoundAction(Icons.Filled.CallEnd, stringResource(R.string.call_decline), END_RED) { CallManager.decline(call.call) }
        if (call.incomingVideo) RoundAction(Icons.Filled.Videocam, stringResource(R.string.call_answer_video), listOf(Color(0xFF6366F1), Color(0xFF4338CA)), onAnswerVideo)
        if (hasActiveCall) {
            RoundAction(Icons.Filled.CallEnd, stringResource(R.string.call_end_and_answer), listOf(Color(0xFFFBBF24), Color(0xFFF97316))) { CallManager.endAndAnswer(call.call) }
            RoundAction(Icons.Filled.Pause, stringResource(R.string.call_hold_and_answer), green) { CallManager.answer(call.call, video = false) }
        } else {
            RoundAction(Icons.Filled.Call, stringResource(R.string.call_answer), green) { CallManager.answer(call.call, video = false) }
        }
    }
}

/** Telecom asks which SIM to use ("ask every time"). */
@Composable
private fun AccountChooser(call: CallUi) {
    val context = LocalContext.current
    val sims = remember(call.call) {
        val offered: List<PhoneAccountHandle> = call.call.details.intentExtras?.let { extras ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                extras.getParcelableArrayList(Call.AVAILABLE_PHONE_ACCOUNTS, PhoneAccountHandle::class.java)
            } else {
                @Suppress("DEPRECATION")
                extras.getParcelableArrayList(Call.AVAILABLE_PHONE_ACCOUNTS)
            }
        }.orEmpty()
        val all = Sims.list(context)
        if (offered.isEmpty()) all else all.filter { it.handle in offered }.ifEmpty { all }
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(GLASS)) {
        SimPicker(sims, LocalDialerPalette.current) { CallManager.selectAccount(call.call, it.handle) }
        TextButton(onClick = { CallManager.hangUp(call.call) }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text(stringResource(android.R.string.cancel), color = Color.White)
        }
    }
}

@Composable
private fun OtherCallBanner(other: CallUi, canSwap: Boolean) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(GLASS).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (other.isHeld) Icons.Filled.Pause else Icons.Filled.Call, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(other.display.title, color = Color.White, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(if (other.isHeld) R.string.call_state_on_hold else R.string.call_state_active),
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (canSwap) {
            TextButton(onClick = { CallManager.swap() }) {
                Icon(Icons.Filled.SwapCalls, contentDescription = null, tint = Color.White)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.call_swap), color = Color.White)
            }
        }
    }
}

/** Opens the keypad to dial a second call; Telecom holds the current one when it starts. */
private fun addCall(context: Context) {
    context.startActivity(Intent(context, DialerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

@Composable
private fun CallStatus(call: CallUi) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(call.state) {
        while (call.state == Call.STATE_ACTIVE) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val text = when (call.state) {
        Call.STATE_RINGING -> stringResource(if (call.incomingVideo) R.string.call_state_incoming_video else R.string.call_state_incoming)
        Call.STATE_DIALING -> stringResource(R.string.call_state_dialing)
        Call.STATE_CONNECTING, Call.STATE_NEW -> stringResource(R.string.call_state_connecting)
        Call.STATE_SELECT_PHONE_ACCOUNT -> stringResource(R.string.dialer_choose_sim)
        Call.STATE_HOLDING -> stringResource(R.string.call_state_on_hold)
        Call.STATE_DISCONNECTED, Call.STATE_DISCONNECTING -> stringResource(R.string.call_state_ended)
        Call.STATE_ACTIVE -> if (call.connectTimeMillis > 0) formatDuration((now - call.connectTimeMillis).coerceAtLeast(0) / 1000) else ""
        else -> ""
    }
    Text(text, style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.85f))
}

@Composable
private fun RoundAction(icon: ImageVector, label: String, colors: List<Color>, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        GradientCallButton(icon = icon, description = label, colors = colors, size = 72.dp, enabled = true, onClick = onClick)
        Spacer(Modifier.height(8.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = Color.White)
    }
}

@Composable
private fun Toggle(icon: ImageVector, label: String, checked: Boolean, enabled: Boolean = true, onToggle: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(84.dp)) {
        Box(
            Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(if (checked) Color.White else GLASS)
                .clickable(enabled = enabled, role = Role.Switch, onClick = onToggle)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = when {
                    !enabled -> Color.White.copy(alpha = 0.35f)
                    checked -> Color(0xFF1B1F4A)
                    else -> Color.White
                },
                modifier = Modifier.size(28.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = if (enabled) 1f else 0.5f), maxLines = 1)
    }
}

@Composable
internal fun DtmfPad(onDigit: (Char) -> Unit) {
    val rows = listOf("123", "456", "789", "*0#")
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                row.forEach { c ->
                    Box(
                        Modifier.size(70.dp).clip(CircleShape).background(GLASS).clickable(role = Role.Button) { onDigit(c) },
                        contentAlignment = Alignment.Center,
                    ) { Text(c.toString(), fontSize = 28.sp, color = Color.White) }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

/**
 * Remote video full-screen with a small self-view. Telecom/IMS handles the media; we only provide
 * surfaces and choose the camera.
 */
@Composable
private fun VideoSurfaces(call: Call) {
    val context = LocalContext.current
    val videoCall = call.videoCall ?: return
    var useFront by rememberSaveable { mutableStateOf(true) }
    val hasCamera = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    LaunchedEffect(useFront, hasCamera) {
        if (hasCamera) videoCall.setCamera(cameraId(context, useFront))
    }
    DisposableEffect(videoCall) {
        onDispose {
            runCatching {
                videoCall.setCamera(null)
                videoCall.setPreviewSurface(null)
                videoCall.setDisplaySurface(null)
            }
        }
    }
    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx -> surfaceView(ctx) { videoCall.setDisplaySurface(it) } },
            modifier = Modifier.fillMaxSize(),
        )
        if (hasCamera) {
            AndroidView(
                factory = { ctx -> surfaceView(ctx) { videoCall.setPreviewSurface(it) } },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(16.dp)
                    .width(110.dp)
                    .height(160.dp)
                    .clip(RoundedCornerShape(12.dp)),
            )
            FilledTonalIconToggleButton(
                checked = !useFront,
                onCheckedChange = { useFront = !useFront },
                modifier = Modifier.align(Alignment.TopStart).windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp),
            ) { Icon(Icons.Filled.Cameraswitch, contentDescription = stringResource(R.string.call_switch_camera)) }
        }
    }
}

private fun surfaceView(context: Context, onSurface: (Surface?) -> Unit): TextureView = TextureView(context).apply {
    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) = onSurface(Surface(texture))
        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit
        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
            onSurface(null)
            return true
        }
        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
    }
}

private fun cameraId(context: Context, front: Boolean): String? = try {
    val manager = context.getSystemService(CameraManager::class.java)
    val facing = if (front) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
    manager?.cameraIdList?.firstOrNull { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == facing }
} catch (_: Exception) {
    null
}
