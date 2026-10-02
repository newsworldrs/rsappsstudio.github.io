package com.rskusum.whocaller.feature.dialer

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telecom.Connection
import android.telecom.TelecomManager
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telecom.PhoneAccountHandle
import android.telecom.VideoProfile
import com.rskusum.whocaller.core.model.CallerLabel
import com.rskusum.whocaller.core.model.CallerResult
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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
    /** Current network confirmed by the number's owner (null: only the original network is known). */
    val carrier: String? = null,
    /** The number's owner verified it by SMS code: show the blue tick. */
    val verified: Boolean = false,
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
    /** Raw number of the other party (null when withheld). */
    val number: String? = null,
    val isConference: Boolean = false,
    val childCount: Int = 0,
    /** Part of a conference: shown through its parent, not on its own. */
    val isChild: Boolean = false,
    val canMerge: Boolean = false,
    val canSwapConference: Boolean = false,
    /** The network and both phones allow switching this call to video. */
    val canVideo: Boolean = false,
    /** This phone can do video but the other side (phone or network) says it can't. */
    val remoteCantVideo: Boolean = false,
    /** We asked to switch to video and are waiting for the other person. */
    val videoUpgradePending: Boolean = false,
    /** One-way video: we see them / they see us. */
    val receivingVideo: Boolean = false,
    val sendingVideo: Boolean = false,
    /** The other person asked to switch this call to video. */
    val videoRequested: Boolean = false,
) {
    val isRinging: Boolean get() = state == Call.STATE_RINGING
    val isActive: Boolean get() = state == Call.STATE_ACTIVE
    val isHeld: Boolean get() = state == Call.STATE_HOLDING
    /** Telecom is waiting for the user to choose a SIM ("ask every time"). */
    val needsAccount: Boolean get() = state == Call.STATE_SELECT_PHONE_ACCOUNT
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

    /** One-off messages for the call screen (string resource ids). */
    private val _messages = MutableSharedFlow<Int>(extraBufferCapacity = 4)
    val messages: SharedFlow<Int> = _messages.asSharedFlow()

    private val videoCallbacks = ConcurrentHashMap<Call, Pair<InCallService.VideoCall, InCallService.VideoCall.Callback>>()
    private val videoRequests: MutableSet<Call> = ConcurrentHashMap.newKeySet()
    private val pendingUpgrades: MutableSet<Call> = ConcurrentHashMap.newKeySet()
    /** Video state the other person asked for (two-way, or one-way). */
    private val requestedStates = ConcurrentHashMap<Call, Int>()

    /** Camera size reported by the IMS video provider, and the other person's video size. */
    data class VideoSizes(val cameraWidth: Int = 0, val cameraHeight: Int = 0, val peerWidth: Int = 0, val peerHeight: Int = 0)
    private val _videoSizes = MutableStateFlow<Map<Call, VideoSizes>>(emptyMap())
    val videoSizes: StateFlow<Map<Call, VideoSizes>> = _videoSizes.asStateFlow()

    private fun updateSizes(call: Call, change: (VideoSizes) -> VideoSizes) {
        _videoSizes.value = _videoSizes.value + (call to change(_videoSizes.value[call] ?: VideoSizes()))
    }

    /** Network's answer to the last failed video request, in words (shown on the call screen). */
    @Volatile var lastVideoFailure: String? = null
        private set

    /** Called when the other person asks to switch to video (the call screen must come to the front). */
    @Volatile internal var onVideoRequest: ((Call) -> Unit)? = null
    /** Video calls that the network connected as voice (told once). */
    private val downgradeNoticed: MutableSet<Call> = ConcurrentHashMap.newKeySet()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _audio = MutableStateFlow<CallAudioState?>(null)
    val audio: StateFlow<CallAudioState?> = _audio.asStateFlow()

    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) = refresh()
        override fun onDetailsChanged(call: Call, details: Call.Details) = refresh()
        override fun onVideoCallChanged(call: Call, videoCall: InCallService.VideoCall?) = refresh()
        override fun onConferenceableCallsChanged(call: Call, conferenceableCalls: MutableList<Call>) = refresh()
        override fun onChildrenChanged(call: Call, children: MutableList<Call>) = refresh()
        override fun onParentChanged(call: Call, parent: Call?) = refresh()
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
        videoCallbacks.remove(call)?.let { (vc, cb) -> runCatching { vc.unregisterCallback(cb) } }
        videoRequests.remove(call)
        pendingUpgrades.remove(call)
        requestedStates.remove(call)
        _videoSizes.value = _videoSizes.value - call
        downgradeNoticed.remove(call)
        call.unregisterCallback(callback)
        list.remove(call)
        displays.remove(call)
        refresh()
    }

    internal fun onAudioState(state: CallAudioState?) {
        _audio.value = state
    }

    /** The call the screen should focus on: ringing first, then active/dialing, then held. */
    fun primary(): CallUi? = _calls.value.filterNot { it.isChild }.let { all ->
        all.firstOrNull { it.isRinging }
            ?: all.firstOrNull { it.state == Call.STATE_ACTIVE || it.state == Call.STATE_DIALING || it.state == Call.STATE_CONNECTING }
            ?: all.firstOrNull()
    }

    fun answer(call: Call, video: Boolean) {
        if (video) VideoDiagnostics.record("answered as video", call)
        call.answer(if (video) VideoProfile.STATE_BIDIRECTIONAL else VideoProfile.STATE_AUDIO_ONLY)
    }

    fun decline(call: Call) = call.reject(false, null)

    fun hangUp(call: Call) = call.disconnect()

    fun toggleHold(call: Call) {
        if (stateOf(call) == Call.STATE_HOLDING) call.unhold() else call.hold()
    }

    /** Joins the active and held calls into a conference, if the network allows it. */
    fun merge(call: Call) {
        val other = call.conferenceableCalls.firstOrNull()
        when {
            other != null -> call.conference(other)
            call.details.can(Call.Details.CAPABILITY_MERGE_CONFERENCE) -> call.mergeConference()
        }
    }

    /** Switches between the active call and the held one. */
    fun swap() {
        val all = _calls.value.filterNot { it.isChild }
        val active = all.firstOrNull { it.isActive }
        val held = all.firstOrNull { it.isHeld }
        when {
            active != null && active.canSwapConference -> active.call.swapConference()
            held != null -> held.call.unhold() // Telecom puts the active call on hold.
            active != null -> active.call.hold()
        }
    }

    /** Ends the active call and answers the ringing one. */
    fun endAndAnswer(ringing: Call) {
        _calls.value.filter { it.isActive && it.call != ringing }.forEach { it.call.disconnect() }
        answer(ringing, video = false)
    }

    /**
     * Asks the network to turn video on (or off) for an ongoing call. Returns false if it can't.
     * For "on", the call screen has already started the camera preview (some networks need it).
     */
    fun requestVideo(call: Call, on: Boolean): Boolean {
        val vc = call.videoCall ?: return false
        watchVideo(call)
        val state = if (on) VideoProfile.STATE_BIDIRECTIONAL else VideoProfile.STATE_AUDIO_ONLY
        val sent = runCatching { vc.sendSessionModifyRequest(VideoProfile(state)) }
            .onFailure { VideoDiagnostics.record("request ${if (on) "video" else "voice"}: could not send (${it.javaClass.simpleName}: ${it.message})", call) }
            .isSuccess
        if (sent) VideoDiagnostics.record("request ${if (on) "video" else "voice"} sent", call)
        if (sent && on) {
            pendingUpgrades += call
            // No answer at all: stop waiting (the network normally times out first).
            mainHandler.postDelayed({
                if (pendingUpgrades.remove(call)) {
                    _messages.tryEmit(R.string.call_video_no_answer)
                    refresh()
                }
            }, UPGRADE_WAIT_MS)
        }
        refresh()
        return sent
    }

    /** Stops waiting for the other person (the request itself can't be withdrawn). */
    fun cancelVideoRequest(call: Call) {
        pendingUpgrades.remove(call)
        refresh()
    }

    /** Answers the other person's request to switch to video. */
    fun respondToVideoRequest(call: Call, accept: Boolean) {
        videoRequests.remove(call)
        // Accept exactly what was asked (two-way, or one-way video), like the system phone app.
        val asked = requestedStates.remove(call) ?: VideoProfile.STATE_BIDIRECTIONAL
        runCatching {
            call.videoCall?.sendSessionModifyResponse(VideoProfile(if (accept) asked else VideoProfile.STATE_AUDIO_ONLY))
        }
        refresh()
    }

    /** Listens to the video session (upgrade requests, answers) for each call that has one. */
    private fun watchVideo(call: Call) {
        val vc = call.videoCall
        val known = videoCallbacks[call]
        if (vc == null || known?.first === vc) return
        known?.let { (old, oldCb) -> runCatching { old.unregisterCallback(oldCb) } }
        val cb = object : InCallService.VideoCall.Callback() {
            override fun onSessionModifyRequestReceived(videoProfile: VideoProfile) {
                VideoDiagnostics.record("other side asks for " + VideoDiagnostics.videoState(videoProfile.videoState), call)
                if (VideoProfile.isVideo(videoProfile.videoState) && !VideoProfile.isVideo(call.details.videoState)) {
                    requestedStates[call] = videoProfile.videoState
                    videoRequests += call
                    refresh()
                    // Show the request even if the call screen is in the background.
                    onVideoRequest?.invoke(call)
                }
            }

            override fun onSessionModifyResponseReceived(status: Int, requestedProfile: VideoProfile?, responseProfile: VideoProfile?) {
                val wasWaiting = pendingUpgrades.remove(call)
                val nowVideo = responseProfile?.let { VideoProfile.isVideo(it.videoState) } == true
                VideoDiagnostics.record(
                    "answer: " + VideoDiagnostics.statusText(status) +
                        ", asked " + (requestedProfile?.let { VideoDiagnostics.videoState(it.videoState) } ?: "?") +
                        ", got " + (responseProfile?.let { VideoDiagnostics.videoState(it.videoState) } ?: "?"),
                    call,
                )
                lastVideoFailure = if (status != Connection.VideoProvider.SESSION_MODIFY_REQUEST_SUCCESS || (wasWaiting && !nowVideo)) {
                    VideoDiagnostics.statusText(status)
                } else {
                    null
                }
                if (status != Connection.VideoProvider.SESSION_MODIFY_REQUEST_SUCCESS || (wasWaiting && !nowVideo)) {
                    _messages.tryEmit(
                        when (status) {
                            Connection.VideoProvider.SESSION_MODIFY_REQUEST_SUCCESS,
                            Connection.VideoProvider.SESSION_MODIFY_REQUEST_REJECTED_BY_REMOTE,
                            -> R.string.call_video_declined
                            Connection.VideoProvider.SESSION_MODIFY_REQUEST_TIMED_OUT -> R.string.call_video_no_answer
                            else -> R.string.call_video_failed
                        },
                    )
                }
                refresh()
            }

            override fun onCallSessionEvent(event: Int) = VideoDiagnostics.record("session event $event", call)
            override fun onPeerDimensionsChanged(width: Int, height: Int) =
                updateSizes(call) { it.copy(peerWidth = width, peerHeight = height) }
            override fun onVideoQualityChanged(videoQuality: Int) = Unit
            override fun onCallDataUsageChanged(dataUsage: Long) = Unit
            override fun onCameraCapabilitiesChanged(cameraCapabilities: VideoProfile.CameraCapabilities?) {
                cameraCapabilities ?: return
                updateSizes(call) { it.copy(cameraWidth = cameraCapabilities.width, cameraHeight = cameraCapabilities.height) }
            }
        }
        runCatching { vc.registerCallback(cb, mainHandler) }
        videoCallbacks[call] = vc to cb
    }

    fun selectAccount(call: Call, handle: PhoneAccountHandle) = call.phoneAccountSelected(handle, false)

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

    private fun localVideo(d: Call.Details) =
        d.can(Call.Details.CAPABILITY_SUPPORTS_VT_LOCAL_BIDIRECTIONAL) || d.can(Call.Details.CAPABILITY_SUPPORTS_VT_LOCAL_TX)

    private fun remoteVideo(d: Call.Details) =
        d.can(Call.Details.CAPABILITY_SUPPORTS_VT_REMOTE_BIDIRECTIONAL) || d.can(Call.Details.CAPABILITY_SUPPORTS_VT_REMOTE_RX)

    private const val UPGRADE_WAIT_MS = 30_000L

    private fun refresh() {
        _calls.value = list.map { call ->
            val details = call.details
            val state = stateOf(call)
            watchVideo(call)
            val video = VideoProfile.isVideo(details.videoState)
            // Asked for a video call, but the network connected it as voice: say so once.
            if (state == Call.STATE_ACTIVE && !video && call !in downgradeNoticed &&
                details.intentExtras?.getInt(TelecomManager.EXTRA_START_CALL_WITH_VIDEO_STATE, VideoProfile.STATE_AUDIO_ONLY)
                    ?.let(VideoProfile::isVideo) == true
            ) {
                downgradeNoticed += call
                VideoDiagnostics.record("video call connected as voice by the network", call)
                _messages.tryEmit(R.string.call_video_became_voice)
            }
            CallUi(
                call = call,
                state = state,
                display = displays[call] ?: CallerDisplay(details.handle?.schemeSpecificPart.orEmpty(), null, null, false, CallerLabel.UNKNOWN),
                isVideo = VideoProfile.isVideo(details.videoState) && state != Call.STATE_RINGING,
                incomingVideo = state == Call.STATE_RINGING && VideoProfile.isVideo(details.videoState),
                connectTimeMillis = details.connectTimeMillis,
                canHold = details.can(Call.Details.CAPABILITY_HOLD) || details.can(Call.Details.CAPABILITY_SUPPORT_HOLD),
                number = details.handle?.schemeSpecificPart?.takeIf { it.isNotBlank() },
                isConference = details.hasProperty(Call.Details.PROPERTY_CONFERENCE),
                childCount = call.children.size,
                isChild = call.parent != null,
                canMerge = call.conferenceableCalls.isNotEmpty() || details.can(Call.Details.CAPABILITY_MERGE_CONFERENCE),
                canSwapConference = details.can(Call.Details.CAPABILITY_SWAP_CONFERENCE),
                // Like the system phone app: both this phone and the other side must support video.
                canVideo = call.videoCall != null && localVideo(details) && remoteVideo(details),
                remoteCantVideo = call.videoCall != null && localVideo(details) && !remoteVideo(details),
                videoUpgradePending = call in pendingUpgrades && !video,
                receivingVideo = VideoProfile.isReceptionEnabled(details.videoState),
                sendingVideo = VideoProfile.isTransmissionEnabled(details.videoState),
                videoRequested = call in videoRequests && !video,
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
            CallerLabel.SUSPECTED_SPAM -> if (result.info?.flaggedOnlyByList == true) {
                context.getString(com.rskusum.whocaller.core.ui.R.string.label_possible_spam_list) + " · " +
                    context.getString(com.rskusum.whocaller.core.ui.R.string.label_flagged_by_list, result.info?.listedBy.orEmpty()) to true
            } else {
                listOfNotNull(context.getString(R.string.call_label_spam), reportText).joinToString(" · ") to true
            }
            CallerLabel.TELEMARKETING -> listOfNotNull(context.getString(R.string.call_label_telemarketing), reportText).joinToString(" · ") to true
            CallerLabel.VERIFIED_BUSINESS -> context.getString(R.string.call_label_verified) to false
            CallerLabel.BUSINESS, CallerLabel.PERSON ->
                context.getString(if (result.info?.whoCallerVerified == true) com.rskusum.whocaller.core.ui.R.string.label_verified_id else R.string.call_label_whocaller) to false
            CallerLabel.HIDDEN -> context.getString(R.string.call_private) to false
            CallerLabel.UNKNOWN -> context.getString(R.string.call_label_unknown) to false
        }
        return CallerDisplay(
            title = name ?: number,
            number = if (name != null) number else null,
            label = label,
            warning = warning,
            callerLabel = result.label,
            carrier = result.info?.carrier?.takeIf { it.isNotBlank() },
            verified = result.info?.whoCallerVerified == true,
        )
    }
}
