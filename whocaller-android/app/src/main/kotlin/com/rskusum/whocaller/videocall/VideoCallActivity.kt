package com.rskusum.whocaller.videocall

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.ListenerRegistration
import com.rskusum.whocaller.R
import com.rskusum.whocaller.core.ui.component.GradientAvatar
import com.rskusum.whocaller.core.ui.theme.WhoCallerTheme
import com.rskusum.whocaller.core.ui.util.TelecomActions
import com.rskusum.whocaller.core.ui.util.formatDuration
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

/**
 * WHOCALLER VIDEO (experimental): app-to-app video call between two WhoCaller users, over the
 * internet (WebRTC), set up through Firestore and rung with a push notification.
 *
 * To remove the feature: delete this package (videocall/), its <activity>/<service> in
 * AndroidManifest.xml, the firebase-messaging / stream-webrtc dependencies, the "WhoCaller video"
 * buttons (search the code for "WHOCALLER VIDEO") and functions/src/videoCalls.ts.
 */
class VideoCallActivity : ComponentActivity() {

    private enum class Phase { Starting, NotOnWhoCaller, Calling, Incoming, Connecting, Connected, Ended }

    private var phase by mutableStateOf(Phase.Starting)
    private var peerName by mutableStateOf("")
    private var peerNumber by mutableStateOf("")
    private var endMessage by mutableStateOf<Int?>(null)
    private var micOn by mutableStateOf(true)
    private var cameraOn by mutableStateOf(true)
    private var speakerOn by mutableStateOf(true)
    private var connectedAt by mutableLongStateOf(0L)
    private var localTrack by mutableStateOf<VideoTrack?>(null)
    private var remoteTrack by mutableStateOf<VideoTrack?>(null)

    private var signaling: VideoSignaling? = null
    private var session: RtcSession? = null
    private var callId: String? = null
    private var isCaller = false
    private var remoteSet = false
    private val listeners = mutableListOf<ListenerRegistration>()
    private var ringTimeout: Job? = null
    private var ringtone: Ringtone? = null
    private var finished = false
    private var pendingAction: (() -> Unit)? = null

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val next = pendingAction
        pendingAction = null
        if (result.values.all { it }) next?.invoke() else {
            Toast.makeText(this, R.string.vc_permissions_needed, Toast.LENGTH_LONG).show()
            hangUp(VideoSignaling.STATUS_ENDED)
        }
    }

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
        NotificationManagerCompat.from(this).cancel(VideoCallMessagingService.NOTIFICATION_ID)

        val sig = VideoSignaling.create(this)
        if (sig == null) {
            Toast.makeText(this, R.string.vc_sign_in_needed, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        signaling = sig
        val incomingId = intent.getStringExtra(EXTRA_CALL_ID)
        when {
            incomingId != null && intent.getBooleanExtra(EXTRA_DECLINE, false) -> {
                lifecycleScope.launch { sig.setStatus(incomingId, VideoSignaling.STATUS_DECLINED) }
                finish()
                return
            }
            incomingId != null -> startIncoming(incomingId, autoAccept = intent.getBooleanExtra(EXTRA_ACCEPT, false))
            else -> startOutgoing(intent.getStringExtra(EXTRA_NUMBER).orEmpty())
        }

        setContent {
            WhoCallerTheme(dynamicColor = false) {
                Screen()
            }
        }
    }

    // ---------- Outgoing ----------

    private fun startOutgoing(rawNumber: String) {
        isCaller = true
        val sig = signaling ?: return
        lifecycleScope.launch {
            val e164 = toE164(this@VideoCallActivity, rawNumber)
            peerNumber = e164 ?: rawNumber
            val user = e164?.let { runCatching { sig.findUser(it) }.getOrNull() }
            if (user == null || user.first == sig.myUid) {
                phase = Phase.NotOnWhoCaller
                return@launch
            }
            peerName = user.second ?: peerNumber
            withPermissions {
                lifecycleScope.launch { placeCall(user.first, e164) }
            }
        }
    }

    private suspend fun placeCall(calleeUid: String, e164: String) {
        val sig = signaling ?: return
        phase = Phase.Calling
        val me = FirebaseAuth.getInstance().currentUser
        val myName = me?.displayName?.takeIf { it.isNotBlank() } ?: me?.phoneNumber ?: getString(R.string.vc_someone)
        val id = runCatching { sig.create(calleeUid, e164, peerName, myName, me?.phoneNumber.orEmpty()) }.getOrElse {
            end(R.string.vc_failed)
            return
        }
        callId = id
        val rtc = openSession(id) ?: return
        listeners += sig.listen(id) { doc -> onCallChanged(doc) }
        listeners += sig.listenCandidates(id) { rtc.addRemoteCandidate(it) }
        runCatching { sig.setOffer(id, rtc.createOffer()) }.onFailure { end(R.string.vc_failed); return }
        ringTimeout = lifecycleScope.launch {
            delay(RING_TIMEOUT_MS)
            if (phase == Phase.Calling) hangUp(VideoSignaling.STATUS_MISSED, R.string.vc_no_answer)
        }
    }

    // ---------- Incoming ----------

    private fun startIncoming(id: String, autoAccept: Boolean) {
        callId = id
        isCaller = false
        val sig = signaling ?: return
        lifecycleScope.launch {
            val doc = runCatching { sig.get(id) }.getOrNull()
            if (doc == null || doc.getString("status") != VideoSignaling.STATUS_RINGING) {
                end(R.string.vc_missed)
                return@launch
            }
            peerName = doc.getString("callerName").orEmpty()
            peerNumber = doc.getString("callerNumber").orEmpty()
            phase = Phase.Incoming
            listeners += sig.listen(id) { d -> onCallChanged(d) }
            if (autoAccept) accept() else startRinging()
        }
    }

    private fun accept() {
        stopRinging()
        withPermissions {
            lifecycleScope.launch {
                val id = callId ?: return@launch
                val sig = signaling ?: return@launch
                phase = Phase.Connecting
                val rtc = openSession(id) ?: return@launch
                listeners += sig.listenCandidates(id) { rtc.addRemoteCandidate(it) }
                // The caller writes the offer right after creating the call; wait for it briefly.
                var offer = VideoSignaling.descriptionOf(runCatching { sig.get(id) }.getOrNull()?.get("offer"))
                var waited = 0L
                while (offer == null && waited < OFFER_WAIT_MS) {
                    delay(300)
                    waited += 300
                    offer = VideoSignaling.descriptionOf(runCatching { sig.get(id) }.getOrNull()?.get("offer"))
                }
                if (offer == null) {
                    end(R.string.vc_failed)
                    return@launch
                }
                runCatching {
                    rtc.setRemote(offer)
                    remoteSet = true
                    sig.accept(id, rtc.createAnswer())
                }.onFailure { hangUp(VideoSignaling.STATUS_ENDED, R.string.vc_failed) }
            }
        }
    }

    private fun decline() {
        stopRinging()
        hangUp(VideoSignaling.STATUS_DECLINED)
    }

    // ---------- Shared ----------

    private suspend fun openSession(id: String): RtcSession? {
        val sig = signaling ?: return null
        val servers = sig.iceServers()
        return try {
            RtcSession(this, servers, object : RtcSession.Listener {
                override fun onIceCandidate(candidate: IceCandidate) = sig.addCandidate(id, candidate)
                override fun onRemoteVideo(track: VideoTrack) {
                    runOnUiThread { remoteTrack = track }
                }
                override fun onState(state: PeerConnection.PeerConnectionState) = runOnUiThread {
                    when (state) {
                        PeerConnection.PeerConnectionState.CONNECTED -> {
                            if (connectedAt == 0L) connectedAt = System.currentTimeMillis()
                            phase = Phase.Connected
                        }
                        PeerConnection.PeerConnectionState.FAILED -> hangUp(VideoSignaling.STATUS_ENDED, R.string.vc_connection_lost)
                        else -> Unit
                    }
                }
            }).also {
                it.startLocalMedia()
                localTrack = it.localVideo
                session = it
            }
        } catch (e: Exception) {
            end(R.string.vc_failed)
            null
        }
    }

    private fun onCallChanged(doc: com.google.firebase.firestore.DocumentSnapshot) {
        when (doc.getString("status")) {
            VideoSignaling.STATUS_ACCEPTED -> if (isCaller && !remoteSet) {
                val answer = VideoSignaling.descriptionOf(doc.get("answer")) ?: return
                remoteSet = true
                ringTimeout?.cancel()
                phase = Phase.Connecting
                lifecycleScope.launch { runCatching { session?.setRemote(answer) }.onFailure { hangUp(VideoSignaling.STATUS_ENDED, R.string.vc_failed) } }
            }
            VideoSignaling.STATUS_DECLINED -> end(if (isCaller) R.string.vc_declined else null)
            VideoSignaling.STATUS_MISSED -> end(R.string.vc_missed)
            VideoSignaling.STATUS_ENDED -> end(R.string.vc_ended)
        }
    }

    private fun withPermissions(action: () -> Unit) {
        val needed = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (needed.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
            action()
        } else {
            pendingAction = action
            permissions.launch(needed)
        }
    }

    /** Tells the other side, then closes. */
    private fun hangUp(status: String, message: Int? = R.string.vc_ended) {
        val id = callId
        val sig = signaling
        if (id != null && sig != null) lifecycleScope.launch { sig.setStatus(id, status) }
        end(message)
    }

    private fun end(message: Int?) {
        if (finished) return
        finished = true
        stopRinging()
        ringTimeout?.cancel()
        listeners.forEach { it.remove() }
        listeners.clear()
        localTrack = null
        remoteTrack = null
        session?.close()
        session = null
        endMessage = message
        phase = Phase.Ended
        lifecycleScope.launch {
            delay(1_200)
            finish()
        }
    }

    private fun startRinging() {
        ringtone = runCatching {
            RingtoneManager.getRingtone(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))?.apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isLooping = true
                play()
            }
        }.getOrNull()
    }

    private fun stopRinging() {
        runCatching { ringtone?.stop() }
        ringtone = null
    }

    override fun onDestroy() {
        if (!finished) {
            val id = callId
            val sig = signaling
            if (id != null && sig != null && phase != Phase.Ended) {
                // Closing the screen ends the call for both sides.
                val status = if (phase == Phase.Incoming) VideoSignaling.STATUS_DECLINED else VideoSignaling.STATUS_ENDED
                sig.setStatusAsync(id, status)
            }
            stopRinging()
            listeners.forEach { it.remove() }
            session?.close()
        }
        super.onDestroy()
    }

    // ---------- UI ----------

    @Composable
    private fun Screen() {
        val background = Brush.verticalGradient(listOf(Color(0xFF0B1026), Color(0xFF1B1F4A), Color(0xFF0B1026)))
        Box(Modifier.fillMaxSize().background(background)) {
            val rtc = session
            val remote = remoteTrack
            if (rtc != null && remote != null && phase == Phase.Connected) {
                VideoView(remote, rtc, mirror = false, Modifier.fillMaxSize())
            }
            val local = localTrack
            if (rtc != null && local != null && cameraOn && phase != Phase.Ended) {
                val small = phase == Phase.Connected && remote != null
                VideoView(
                    local,
                    rtc,
                    mirror = rtc.frontCamera,
                    modifier = if (small) {
                        Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp)
                            .width(110.dp).height(160.dp).clip(RoundedCornerShape(14.dp))
                    } else {
                        Modifier.fillMaxSize()
                    },
                    onTop = small,
                )
            }
            Column(
                Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (phase != Phase.Connected) {
                    Spacer(Modifier.height(48.dp))
                    GradientAvatar(peerName.ifBlank { peerNumber }, 112.dp)
                    Spacer(Modifier.height(16.dp))
                }
                Text(peerName.ifBlank { peerNumber }, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                if (peerName.isNotBlank() && peerNumber.isNotBlank() && peerName != peerNumber) {
                    Text(peerNumber, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.titleSmall)
                }
                Spacer(Modifier.height(8.dp))
                Text(statusText(), color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                when (phase) {
                    Phase.NotOnWhoCaller -> NotOnWhoCaller()
                    Phase.Incoming -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        RoundButton(Icons.Filled.CallEnd, stringResource(R.string.vc_decline), Color(0xFFE5484D)) { decline() }
                        RoundButton(Icons.Filled.Videocam, stringResource(R.string.vc_accept), Color(0xFF22C55E)) { accept() }
                    }
                    Phase.Ended, Phase.Starting -> Unit
                    else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            SmallButton(if (micOn) Icons.Filled.Mic else Icons.Filled.MicOff, stringResource(R.string.vc_mic), !micOn) {
                                micOn = !micOn
                                session?.setMicOn(micOn)
                            }
                            SmallButton(if (cameraOn) Icons.Filled.Videocam else Icons.Filled.VideocamOff, stringResource(R.string.vc_camera), !cameraOn) {
                                cameraOn = !cameraOn
                                session?.setCameraOn(cameraOn)
                            }
                            SmallButton(Icons.Filled.Cameraswitch, stringResource(R.string.vc_switch), false) { session?.switchCamera() }
                            SmallButton(if (speakerOn) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff, stringResource(R.string.vc_speaker), !speakerOn) {
                                speakerOn = !speakerOn
                                session?.setSpeaker(speakerOn)
                            }
                        }
                        Spacer(Modifier.height(24.dp))
                        RoundButton(Icons.Filled.CallEnd, stringResource(R.string.vc_end), Color(0xFFE5484D)) { hangUp(VideoSignaling.STATUS_ENDED) }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    @Composable
    private fun statusText(): String {
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(phase) {
            while (phase == Phase.Connected) {
                now = System.currentTimeMillis()
                delay(1_000)
            }
        }
        return when (phase) {
            Phase.Starting -> stringResource(R.string.vc_starting)
            Phase.NotOnWhoCaller -> stringResource(R.string.vc_not_on_whocaller_short)
            Phase.Calling -> stringResource(R.string.vc_calling)
            Phase.Incoming -> stringResource(R.string.vc_incoming)
            Phase.Connecting -> stringResource(R.string.vc_connecting)
            Phase.Connected -> formatDuration((now - connectedAt).coerceAtLeast(0) / 1000)
            Phase.Ended -> endMessage?.let { stringResource(it) } ?: stringResource(R.string.vc_ended)
        }
    }

    @Composable
    private fun NotOnWhoCaller() {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                stringResource(R.string.vc_not_on_whocaller),
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                if (TelecomActions.whatsAppPackage(this@VideoCallActivity) != null) {
                    RoundButton(Icons.Filled.Videocam, stringResource(R.string.vc_whatsapp), Color(0xFF25D366)) {
                        TelecomActions.whatsAppCall(this@VideoCallActivity, peerNumber, video = true)
                        finish()
                    }
                }
                RoundButton(Icons.Filled.Call, stringResource(R.string.vc_invite), Color(0xFF6366F1)) {
                    val text = getString(R.string.vc_invite_text, "https://play.google.com/store/apps/details?id=$packageName")
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                    runCatching { startActivity(Intent.createChooser(send, null)) }
                }
            }
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = { finish() }) { Text(stringResource(android.R.string.cancel), color = Color.White) }
        }
    }

    @Composable
    private fun VideoView(track: VideoTrack, rtc: RtcSession, mirror: Boolean, modifier: Modifier, onTop: Boolean = false) {
        val renderer = remember(track) {
            SurfaceViewRenderer(this).apply {
                init(rtc.egl.eglBaseContext, null)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                setEnableHardwareScaler(true)
                setZOrderMediaOverlay(onTop)
            }
        }
        renderer.setMirror(mirror)
        DisposableEffect(track, renderer) {
            runCatching { track.addSink(renderer) }
            onDispose {
                runCatching { track.removeSink(renderer) }
                runCatching { renderer.release() }
            }
        }
        AndroidView(factory = { renderer }, modifier = modifier)
    }

    @Composable
    private fun RoundButton(icon: ImageVector, label: String, color: Color, onClick: () -> Unit) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(72.dp).clip(CircleShape).background(color).clickable(onClick = onClick),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(32.dp)) }
            Spacer(Modifier.height(6.dp))
            Text(label, color = Color.White, style = MaterialTheme.typography.labelMedium)
        }
    }

    @Composable
    private fun SmallButton(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(56.dp).clip(CircleShape).background(if (active) Color.White else Color.White.copy(alpha = 0.16f)).clickable(onClick = onClick),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, contentDescription = label, tint = if (active) Color(0xFF1B1F4A) else Color.White) }
            Spacer(Modifier.height(4.dp))
            Text(label, color = Color.White, style = MaterialTheme.typography.labelSmall)
        }
    }

    companion object {
        const val ACTION_VIDEO = "com.rskusum.whocaller.action.WHOCALLER_VIDEO"
        const val EXTRA_NUMBER = "number"
        const val EXTRA_CALL_ID = "call_id"
        const val EXTRA_ACCEPT = "accept"
        const val EXTRA_DECLINE = "decline"
        private const val RING_TIMEOUT_MS = 45_000L
        private const val OFFER_WAIT_MS = 10_000L

        fun incoming(context: Context, callId: String, accept: Boolean = false, decline: Boolean = false): Intent =
            Intent(context, VideoCallActivity::class.java)
                .putExtra(EXTRA_CALL_ID, callId)
                .putExtra(EXTRA_ACCEPT, accept)
                .putExtra(EXTRA_DECLINE, decline)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        /** "+919876543210" from any local or international form (the SIM's country for local numbers). */
        fun toE164(context: Context, raw: String): String? {
            val iso = runCatching { context.getSystemService(android.telephony.TelephonyManager::class.java)?.simCountryIso?.uppercase() }
                .getOrNull()?.takeIf { it.length == 2 } ?: java.util.Locale.getDefault().country.ifBlank { "IN" }
            return runCatching {
                val util = com.google.i18n.phonenumbers.PhoneNumberUtil.getInstance()
                val parsed = util.parse(raw, iso)
                if (util.isValidNumber(parsed)) util.format(parsed, com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat.E164) else null
            }.getOrNull()
        }
    }
}
