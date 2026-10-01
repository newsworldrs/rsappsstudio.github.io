package com.rskusum.whocaller.feature.dialer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.telecom.PhoneAccountHandle
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SimCard
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rskusum.whocaller.core.ui.util.TelecomActions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Places calls the way a phone app should: asks for the call permission once, lets the user pick a
 * SIM when there is more than one (or when they long-press Call), routes emergency numbers safely,
 * and finds a working video option (SIM video calling, else WhatsApp video).
 */
@Stable
class CallActions internal constructor(private val context: Context, private val scope: CoroutineScope) {

    internal var simChoice by mutableStateOf<SimRequest?>(null)
    internal var videoChoice by mutableStateOf<VideoRequest?>(null)
    internal var pending: (() -> Unit)? = null
    internal var requestPermission: (Array<String>) -> Unit = {}

    internal data class SimRequest(val number: String, val video: Boolean, val sims: List<SimOption>)
    internal data class VideoRequest(val number: String, val whatsAppEntry: Long)

    /** Calls [number]. [pickSim] forces the SIM chooser (long-press on the Call button). */
    fun call(number: String, video: Boolean = false, pickSim: Boolean = false, cameraAsked: Boolean = false) {
        if (number.isBlank()) return
        if (NumberTools.isEmergency(context, number) && !isDefaultDialer(context)) {
            // Only the default phone app may place emergency calls directly.
            NumberTools.dialEmergencyWithSystem(context, number)
            return
        }
        // Video needs the camera for the self-view; without it the network can't start video.
        val needCamera = video && !cameraAsked &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
        if (!TelecomActions.canPlaceCalls(context) || needCamera) {
            pending = { call(number, video, pickSim, cameraAsked = true) }
            requestPermission(
                listOfNotNull(
                    Manifest.permission.CALL_PHONE,
                    Manifest.permission.READ_PHONE_STATE,
                    Manifest.permission.CAMERA.takeIf { video },
                ).toTypedArray(),
            )
            return
        }
        val withVideo = video && ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (video && !withVideo) Toast.makeText(context, R.string.call_camera_needed, Toast.LENGTH_LONG).show()
        val sims = Sims.list(context)
        if (sims.size > 1 && (pickSim || Sims.default(context) == null)) {
            simChoice = SimRequest(number, withVideo, sims)
            return
        }
        TelecomActions.placeCall(context, number, withVideo)
    }

    internal fun callWith(request: SimRequest, handle: PhoneAccountHandle) {
        simChoice = null
        TelecomActions.placeCall(context, request.number, request.video, handle)
    }

    /** Video call: SIM video calling when the carrier supports it, else WhatsApp video. */
    fun video(number: String) {
        if (number.isBlank()) return
        scope.launch {
            val entry = withContext(Dispatchers.IO) { ContactLookup.whatsAppVideoEntry(context, number) }
            // Many phones don't report video support until the call starts, so always offer the SIM
            // video call (4G/VoLTE): the network connects it as video, or as voice if it can't.
            if (entry != null) videoChoice = VideoRequest(number, entry) else call(number, video = true)
        }
    }

    internal fun whatsAppVideo(request: VideoRequest) {
        if (!ContactLookup.startWhatsAppVideo(context, request.whatsAppEntry)) openWhatsAppChat(request.number, hint = true)
    }

    fun openWhatsAppChat(number: String, hint: Boolean = false) {
        val e164 = NumberTools.international(number, NumberTools.countryIso(context)) ?: number
        TelecomActions.openWhatsApp(context, e164)
        if (hint) Toast.makeText(context, R.string.dialer_video_in_whatsapp, Toast.LENGTH_LONG).show()
    }

    fun voicemail() {
        if (!TelecomActions.canPlaceCalls(context)) {
            pending = { voicemail() }
            requestPermission(arrayOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE))
            return
        }
        if (!TelecomActions.callVoicemail(context)) Toast.makeText(context, R.string.dialer_voicemail_unavailable, Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun rememberCallActions(): CallActions {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val actions = remember { CallActions(context, scope) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val next = actions.pending
        actions.pending = null
        if (result[Manifest.permission.CALL_PHONE] == true) {
            next?.invoke()
        } else {
            Toast.makeText(context, R.string.dialer_permission_needed, Toast.LENGTH_SHORT).show()
        }
    }
    actions.requestPermission = { launcher.launch(it) }
    return actions
}

/** Shows the SIM chooser and the video-call choice when [actions] needs them. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallActionsHost(actions: CallActions) {
    val palette = LocalDialerPalette.current
    actions.simChoice?.let { request ->
        ModalBottomSheet(onDismissRequest = { actions.simChoice = null }, containerColor = palette.surface) {
            SimPicker(request.sims, palette) { actions.callWith(request, it.handle) }
        }
    }
    actions.videoChoice?.let { request ->
        AlertDialog(
            onDismissRequest = { actions.videoChoice = null },
            title = { Text(stringResource(R.string.dialer_video_how)) },
            text = { Text(stringResource(R.string.dialer_video_sim_hint)) },
            confirmButton = {
                TextButton(onClick = {
                    actions.videoChoice = null
                    actions.call(request.number, video = true)
                }) { Text(stringResource(R.string.dialer_video_sim)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    actions.videoChoice = null
                    actions.whatsAppVideo(request)
                }) { Text(stringResource(R.string.dialer_video_whatsapp)) }
            },
            icon = { Icon(Icons.Filled.Videocam, contentDescription = null) },
        )
    }
}

/** List of SIMs with their carrier colour. Also used by the in-call screen. */
@Composable
fun SimPicker(sims: List<SimOption>, palette: DialerPalette, onPick: (SimOption) -> Unit) {
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text(stringResource(R.string.dialer_choose_sim), style = MaterialTheme.typography.titleMedium, color = palette.text, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp))
        sims.forEachIndexed { i, sim ->
            val color = sim.color?.let { Color(it) } ?: if (i == 0) Indigo else Violet
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { onPick(sim) }
                    .padding(vertical = 12.dp, horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(color.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.SimCard, contentDescription = null, tint = color)
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(sim.label, color = palette.text, style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.dialer_sim_n, i + 1), color = palette.subtle, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}
