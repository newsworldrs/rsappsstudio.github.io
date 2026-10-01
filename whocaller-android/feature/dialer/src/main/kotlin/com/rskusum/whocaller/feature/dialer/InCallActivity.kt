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
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rskusum.whocaller.core.model.CallerLabel
import com.rskusum.whocaller.core.ui.component.CallerAvatar
import com.rskusum.whocaller.core.ui.theme.WhoCallerTheme
import com.rskusum.whocaller.core.ui.util.formatDuration
import kotlinx.coroutines.delay

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
                val calls by CallManager.calls.collectAsStateWithLifecycle()
                val audio by CallManager.audio.collectAsStateWithLifecycle()
                val current = calls.firstOrNull { it.isRinging }
                    ?: calls.firstOrNull { it.state != Call.STATE_HOLDING && it.state != Call.STATE_DISCONNECTED }
                    ?: calls.firstOrNull()
                LaunchedEffect(calls.isEmpty()) {
                    if (calls.isEmpty()) {
                        delay(600)
                        finish()
                    }
                }
                Surface(color = Color(0xFF0B1F1D), contentColor = Color.White, modifier = Modifier.fillMaxSize()) {
                    if (current != null) InCallScreen(current, audio)
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

@Composable
private fun InCallScreen(call: CallUi, audio: CallAudioState?) {
    val context = LocalContext.current
    var showKeypad by rememberSaveable { mutableStateOf(false) }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && call.isRinging) CallManager.answer(call.call, video = true)
    }

    Box(Modifier.fillMaxSize()) {
        if (call.isVideo && call.isActive) VideoSurfaces(call.call)

        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(32.dp))
            if (!call.isVideo || !call.isActive) {
                CallerAvatar(call.display.title.takeIf { call.display.number != null }, call.display.callerLabel, size = 96.dp)
                Spacer(Modifier.height(16.dp))
            }
            Text(call.display.title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
            call.display.number?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.8f)) }
            call.display.label?.let { label ->
                Spacer(Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (call.display.warning) Color(0xFFB3261E) else Color.White.copy(alpha = 0.15f),
                    contentColor = Color.White,
                ) {
                    Text(label, Modifier.padding(horizontal = 14.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge)
                }
            }
            Spacer(Modifier.height(12.dp))
            CallStatus(call)
            Spacer(Modifier.weight(1f))

            if (call.isRinging) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    RoundAction(Icons.Filled.CallEnd, stringResource(R.string.call_decline), Color(0xFFB3261E)) { CallManager.decline(call.call) }
                    if (call.incomingVideo) {
                        RoundAction(Icons.Filled.Videocam, stringResource(R.string.call_answer_video), Color(0xFF1B6D3B)) {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                                CallManager.answer(call.call, video = true)
                            } else {
                                cameraLauncher.launch(Manifest.permission.CAMERA)
                            }
                        }
                    }
                    RoundAction(Icons.Filled.Call, stringResource(R.string.call_answer), Color(0xFF1B6D3B)) { CallManager.answer(call.call, video = false) }
                }
            } else {
                if (showKeypad) {
                    DtmfPad { CallManager.dtmf(call.call, it) }
                    TextButton(onClick = { showKeypad = false }) { Text(stringResource(R.string.call_hide_keypad), color = Color.White) }
                } else {
                    val muted = audio?.isMuted == true
                    val speaker = audio?.route == CallAudioState.ROUTE_SPEAKER
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        Toggle(Icons.Filled.MicOff, stringResource(R.string.call_mute), muted) { CallManager.setMuted(!muted) }
                        Toggle(Icons.AutoMirrored.Filled.VolumeUp, stringResource(R.string.call_speaker), speaker) { CallManager.setSpeaker(!speaker) }
                        if (call.canHold) {
                            Toggle(Icons.Filled.Pause, stringResource(R.string.call_hold), call.state == Call.STATE_HOLDING) { CallManager.toggleHold(call.call) }
                        }
                        Toggle(Icons.Filled.Dialpad, stringResource(R.string.call_keypad), false) { showKeypad = true }
                    }
                }
                Spacer(Modifier.height(24.dp))
                RoundAction(Icons.Filled.CallEnd, stringResource(R.string.call_end), Color(0xFFB3261E)) { CallManager.hangUp(call.call) }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
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
        Call.STATE_RINGING -> stringResource(R.string.call_state_incoming)
        Call.STATE_DIALING -> stringResource(R.string.call_state_dialing)
        Call.STATE_CONNECTING, Call.STATE_NEW -> stringResource(R.string.call_state_connecting)
        Call.STATE_HOLDING -> stringResource(R.string.call_state_on_hold)
        Call.STATE_DISCONNECTED, Call.STATE_DISCONNECTING -> stringResource(R.string.call_state_ended)
        Call.STATE_ACTIVE -> if (call.connectTimeMillis > 0) formatDuration((now - call.connectTimeMillis).coerceAtLeast(0) / 1000) else ""
        else -> ""
    }
    Text(text, style = MaterialTheme.typography.titleSmall, color = Color.White.copy(alpha = 0.85f))
}

@Composable
private fun RoundAction(icon: ImageVector, label: String, color: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledIconButton(
            onClick = onClick,
            modifier = Modifier.size(72.dp).semantics { contentDescription = label },
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = color, contentColor = Color.White),
        ) { Icon(icon, contentDescription = null, modifier = Modifier.size(32.dp)) }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun Toggle(icon: ImageVector, label: String, checked: Boolean, onToggle: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledTonalIconToggleButton(
            checked = checked,
            onCheckedChange = { onToggle() },
            modifier = Modifier.size(60.dp).semantics { contentDescription = label },
        ) { Icon(icon, contentDescription = null) }
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
internal fun DtmfPad(onDigit: (Char) -> Unit) {
    val rows = listOf("123", "456", "789", "*0#")
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                row.forEach { c ->
                    TextButton(
                        onClick = { onDigit(c) },
                        modifier = Modifier.size(72.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.1f)),
                    ) { Text(c.toString(), style = MaterialTheme.typography.headlineSmall, color = Color.White) }
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
