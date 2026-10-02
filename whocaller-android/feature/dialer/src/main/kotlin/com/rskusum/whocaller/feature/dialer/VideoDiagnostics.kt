package com.rskusum.whocaller.feature.dialer

import android.content.Context
import android.os.Build
import android.telecom.Call
import android.telecom.Connection
import android.telecom.DisconnectCause
import android.telecom.VideoProfile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What the network answered to each video attempt, kept on the phone (last [MAX] entries) and shown
 * in More → Video call diagnostics. Carrier video (ViLTE) failures are decided by the network, so the
 * exact codes are what tells us why a video call didn't start.
 */
object VideoDiagnostics {
    private const val PREFS = "video_diagnostics"
    private const val KEY = "events"
    private const val MAX = 20
    @Volatile private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun list(context: Context): List<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").orEmpty().split('\n').filter { it.isNotBlank() }

    fun clear(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()

    fun record(event: String, call: Call? = null) {
        val ctx = appContext ?: return
        val time = SimpleDateFormat("dd MMM HH:mm:ss", Locale.US).format(Date())
        val caps = call?.details?.let { capabilities(it) }.orEmpty()
        val line = listOf(time, event, caps).filter { it.isNotBlank() }.joinToString(" | ").replace('\n', ' ')
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val all = (listOf(line) + list(ctx)).take(MAX)
        prefs.edit().putString(KEY, all.joinToString("\n")).apply()
    }

    fun capabilities(d: Call.Details): String {
        fun yn(b: Boolean) = if (b) "yes" else "no"
        return "state=" + videoState(d.videoState) +
            " local=" + yn(d.can(Call.Details.CAPABILITY_SUPPORTS_VT_LOCAL_BIDIRECTIONAL)) +
            " remote=" + yn(d.can(Call.Details.CAPABILITY_SUPPORTS_VT_REMOTE_BIDIRECTIONAL)) +
            " wifi=" + yn(d.hasProperty(Call.Details.PROPERTY_WIFI)) +
            " hd=" + yn(d.hasProperty(Call.Details.PROPERTY_HIGH_DEF_AUDIO)) +
            " android=" + Build.VERSION.SDK_INT
    }

    fun videoState(state: Int): String = when {
        state == VideoProfile.STATE_AUDIO_ONLY -> "voice"
        VideoProfile.isBidirectional(state) -> "video (2-way)"
        VideoProfile.isTransmissionEnabled(state) -> "video (sending)"
        VideoProfile.isReceptionEnabled(state) -> "video (receiving)"
        else -> state.toString()
    }

    /** Session-modify status in words, with the raw code. */
    fun statusText(status: Int): String = when (status) {
        Connection.VideoProvider.SESSION_MODIFY_REQUEST_SUCCESS -> "success"
        Connection.VideoProvider.SESSION_MODIFY_REQUEST_FAIL -> "failed by the network"
        Connection.VideoProvider.SESSION_MODIFY_REQUEST_INVALID -> "invalid request"
        Connection.VideoProvider.SESSION_MODIFY_REQUEST_TIMED_OUT -> "no answer (timed out)"
        Connection.VideoProvider.SESSION_MODIFY_REQUEST_REJECTED_BY_REMOTE -> "rejected by the other phone/network"
        else -> "unknown"
    } + " (code $status)"

    fun disconnectText(cause: DisconnectCause?): String {
        cause ?: return "unknown"
        val code = when (cause.code) {
            DisconnectCause.LOCAL -> "ended here"
            DisconnectCause.REMOTE -> "ended by the other side"
            DisconnectCause.REJECTED -> "rejected"
            DisconnectCause.BUSY -> "busy"
            DisconnectCause.ERROR -> "network error"
            DisconnectCause.RESTRICTED -> "restricted"
            DisconnectCause.MISSED -> "missed"
            DisconnectCause.CANCELED -> "cancelled"
            else -> "other"
        }
        return listOfNotNull(code, cause.reason?.takeIf { it.isNotBlank() }, cause.description?.toString()?.takeIf { it.isNotBlank() })
            .joinToString(" · ") + " (code ${cause.code})"
    }
}
