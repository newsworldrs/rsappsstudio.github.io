package com.rskusum.whocaller.feature.dialer

import android.content.Context
import android.os.Build
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telecom.VideoProfile
import com.rskusum.whocaller.core.model.CallerLabel
import com.rskusum.whocaller.core.model.CallerResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** What the call screen and notification say about the other party. */
data class CallerDisplay(
    /** Contact name, else WhoCaller name, else the number. */
    val title: String,
    /** Number (when the title is a name) — null otherwise. */
    val number: String?,
    /** Short label: "Contact", "⚠ Suspected spam", "Identified by WhoCaller"… */
    val label: String?,
    val warning: Boolean,
    val callerLabel: CallerLabel,
)

/** Snapshot of one call for the UI. */
data class CallUi(
    val call: Call,
    val state: Int,
    val display: CallerDisplay,
    val isVideo: Boolean,
    val incomingVideo: Boolean,
    val connectTimeMillis: Long,
    val canHold: Boolean,
) {
    val isRinging: Boolean get() = state == Call.STATE_RINGING
    val isActive: Boolean get() = state == Call.STATE_ACTIVE
}

/**
 * Process-wide state of the calls Telecom gave to [WhoCallerInCallService]. The in-call screen
 * and notifications observe [calls]; actions go through here so they work from anywhere.
 */
object CallManager {

    private val list = CopyOnWriteArrayList<Call>()
    private val displays = ConcurrentHashMap<Call, CallerDisplay>()
    private var service: WeakReference<InCallService>? = null

    private val _calls = MutableStateFlow<List<CallUi>>(emptyList())
    val calls: StateFlow<List<CallUi>> = _calls.asStateFlow()

    private val _audio = MutableStateFlow<CallAudioState?>(null)
    val audio: StateFlow<CallAudioState?> = _audio.asStateFlow()

    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) = refresh()
        override fun onDetailsChanged(call: Call, details: Call.Details) = refresh()
        override fun onVideoCallChanged(call: Call, videoCall: InCallService.VideoCall?) = refresh()
    }

    internal fun attach(s: InCallService) {
        service = WeakReference(s)
    }

    internal fun detach() {
        service = null
    }

    internal fun add(call: Call, display: CallerDisplay) {
        if (!list.contains(call)) {
            list += call
            call.registerCallback(callback)
        }
        displays[call] = display
        refresh()
    }

    internal fun setDisplay(call: Call, display: CallerDisplay) {
        if (!list.contains(call)) return
        displays[call] = display
        refresh()
    }

    internal fun remove(call: Call) {
        call.unregisterCallback(callback)
        list.remove(call)
        displays.remove(call)
        refresh()
    }

    internal fun onAudioState(state: CallAudioState?) {
        _audio.value = state
    }

    /** The call the screen should focus on: ringing first, then active/dialing, then held. */
    fun primary(): CallUi? = _calls.value.let { all ->
        all.firstOrNull { it.isRinging }
            ?: all.firstOrNull { it.state == Call.STATE_ACTIVE || it.state == Call.STATE_DIALING || it.state == Call.STATE_CONNECTING }
            ?: all.firstOrNull()
    }

    fun answer(call: Call, video: Boolean) =
        call.answer(if (video) VideoProfile.STATE_BIDIRECTIONAL else VideoProfile.STATE_AUDIO_ONLY)

    fun decline(call: Call) = call.reject(false, null)

    fun hangUp(call: Call) = call.disconnect()

    fun toggleHold(call: Call) {
        if (stateOf(call) == Call.STATE_HOLDING) call.unhold() else call.hold()
    }

    fun setMuted(muted: Boolean) {
        service?.get()?.setMuted(muted)
    }

    fun setSpeaker(on: Boolean) {
        val route = if (on) CallAudioState.ROUTE_SPEAKER else CallAudioState.ROUTE_WIRED_OR_EARPIECE
        @Suppress("DEPRECATION")
        service?.get()?.setAudioRoute(route)
    }

    fun dtmf(call: Call, digit: Char) {
        call.playDtmfTone(digit)
        call.stopDtmfTone()
    }

    fun stateOf(call: Call): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        call.details.state
    } else {
        @Suppress("DEPRECATION")
        call.state
    }

    private fun refresh() {
        _calls.value = list.map { call ->
            val details = call.details
            val state = stateOf(call)
            CallUi(
                call = call,
                state = state,
                display = displays[call] ?: CallerDisplay(details.handle?.schemeSpecificPart.orEmpty(), null, null, false, CallerLabel.UNKNOWN),
                isVideo = VideoProfile.isVideo(details.videoState) && state != Call.STATE_RINGING,
                incomingVideo = state == Call.STATE_RINGING && VideoProfile.isVideo(details.videoState),
                connectTimeMillis = details.connectTimeMillis,
                canHold = details.can(Call.Details.CAPABILITY_HOLD) || details.can(Call.Details.CAPABILITY_SUPPORT_HOLD),
            )
        }
    }
}

/** Turns an identification result into the text shown on the call screen and notification. */
object CallerDisplayFormatter {

    fun initial(context: Context, number: String?): CallerDisplay =
        if (number.isNullOrBlank()) {
            CallerDisplay(context.getString(R.string.call_private), null, null, false, CallerLabel.HIDDEN)
        } else {
            CallerDisplay(number, null, null, false, CallerLabel.UNKNOWN)
        }

    fun from(context: Context, raw: String?, result: CallerResult): CallerDisplay {
        if (result.isHidden) return initial(context, null)
        val number = result.number?.display ?: raw.orEmpty()
        // Contact name first; otherwise any name in WhoCaller's database; otherwise the number.
        val name = result.contactName ?: result.info?.displayName?.takeIf { it.isNotBlank() }
        val reports = result.info?.reportCount ?: 0
        val reportText = if (reports > 0) context.resources.getQuantityString(R.plurals.call_reported_by, reports, reports) else null
        val (label, warning) = when (result.label) {
            CallerLabel.CONTACT -> context.getString(R.string.call_label_contact) to false
            CallerLabel.POSSIBLE_SCAM -> listOfNotNull(context.getString(R.string.call_label_scam), reportText).joinToString(" · ") to true
            CallerLabel.SUSPECTED_SPAM -> listOfNotNull(context.getString(R.string.call_label_spam), reportText).joinToString(" · ") to true
            CallerLabel.TELEMARKETING -> listOfNotNull(context.getString(R.string.call_label_telemarketing), reportText).joinToString(" · ") to true
            CallerLabel.VERIFIED_BUSINESS -> context.getString(R.string.call_label_verified) to false
            CallerLabel.BUSINESS, CallerLabel.PERSON -> context.getString(R.string.call_label_whocaller) to false
            CallerLabel.HIDDEN -> context.getString(R.string.call_private) to false
            CallerLabel.UNKNOWN -> context.getString(R.string.call_label_unknown) to false
        }
        return CallerDisplay(
            title = name ?: number,
            number = if (name != null) number else null,
            label = label,
            warning = warning,
            callerLabel = result.label,
        )
    }
}
