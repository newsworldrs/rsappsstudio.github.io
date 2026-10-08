package com.rskusum.whocaller.feature.callerid

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telecom.Call
import android.telecom.Connection
import android.telecom.CallScreeningService
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import com.rskusum.whocaller.core.common.analytics.CrashReporter
import com.rskusum.whocaller.core.domain.caller.CallerIdentificationManager
import com.rskusum.whocaller.core.domain.repository.SettingsRepository
import com.rskusum.whocaller.core.model.CallDecision
import com.rskusum.whocaller.core.model.CallerLabel
import com.rskusum.whocaller.feature.postcall.PostCallWorker
import com.rskusum.whocaller.core.model.CallerResult
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates screening of one call: identification (time-boxed), response, and alert.
 * Shared by the Android 10+ CallScreeningService and the Android 8–9 broadcast fallback.
 */
@Singleton
class CallScreeningManager @Inject constructor(
    private val identificationManager: CallerIdentificationManager,
    private val settingsRepository: SettingsRepository,
    private val notifier: CallerAlertNotifier,
    private val crashReporter: CrashReporter,
) {
    /**
     * @param canBlock false on Android 8–9, where apps can't reject calls without being the dialer.
     * @return the result, or null if identification failed or ran out of time (the call is then allowed).
     */
    suspend fun screen(
        rawNumber: String?,
        canBlock: Boolean,
        showAlert: Boolean = true,
        /** The network's caller-ID check (STIR/SHAKEN, mainly US carriers) failed: the number may be spoofed. */
        callerIdFailed: Boolean = false,
    ): CallerResult? {
        val result = try {
            withTimeoutOrNull(TOTAL_BUDGET_MS) { identificationManager.identify(rawNumber) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            crashReporter.recordNonFatal(e, "call_screening")
            null
        } ?: return null

        val effective = when {
            // Can't reject on this OS version: warn instead.
            !canBlock && result.decision == CallDecision.BLOCK -> result.copy(decision = CallDecision.WARN)
            // Spoofed caller ID: never trust the number's good name, at least warn.
            callerIdFailed && result.label != CallerLabel.CONTACT && result.decision == CallDecision.ALLOW -> result.copy(decision = CallDecision.WARN)
            else -> result
        }
        val settings = runCatching { settingsRepository.current() }.getOrNull()
        if (showAlert && settings != null && (settings.callerIdEnabled || effective.decision != CallDecision.ALLOW)) {
            val alert = if (callerIdFailed && result.label != CallerLabel.CONTACT) notifier.spoofAlert(effective) else notifier.buildAlert(effective)
            alert?.let { notifier.post(it, settings.disabledNotificationCategories) }
        }
        return effective
    }

    companion object {
        /** Telecom allows ~5 s; answer well before that. */
        const val TOTAL_BUDGET_MS = 4_000L
    }
}

/**
 * Android 10+ call screening. Telecom binds this service only after the user grants the
 * call-screening role and only for callers who aren't in the user's contacts.
 */
@AndroidEntryPoint
class WhoCallerScreeningService : CallScreeningService() {

    @Inject lateinit var screeningManager: CallScreeningManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onScreenCall(callDetails: Call.Details) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            callDetails.callDirection != Call.Details.DIRECTION_INCOMING
        ) {
            respondToCall(callDetails, CallScreeningService.CallResponse.Builder().build())
            return
        }
        val number = callDetails.handle?.schemeSpecificPart
        val hidden = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            callDetails.handlePresentation != TelecomManager.PRESENTATION_ALLOWED

        scope.launch {
            // As the default phone app, WhoCaller's call screen already shows who is calling.
            val isDefaultDialer = getSystemService(TelecomManager::class.java)?.defaultDialerPackage == packageName
            val callerIdFailed = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                callDetails.callerNumberVerificationStatus == Connection.VERIFICATION_STATUS_FAILED
            val result = screeningManager.screen(if (hidden) null else number, canBlock = true, showAlert = !isDefaultDialer, callerIdFailed = callerIdFailed)
            // Not the phone app: we can't see the call end, so check the call log afterwards
            // and offer "Know this caller?" if the user answered or declined.
            if (!isDefaultDialer && !hidden && number != null && result != null &&
                result.decision != CallDecision.BLOCK && result.label != CallerLabel.CONTACT
            ) {
                PostCallWorker.schedule(this@WhoCallerScreeningService, number)
            }
            val response = if (result?.decision == CallDecision.BLOCK) {
                CallScreeningService.CallResponse.Builder()
                    .setDisallowCall(true)
                    .setRejectCall(true)
                    .setSkipCallLog(false)
                    .setSkipNotification(true)
                    .build()
            } else {
                CallScreeningService.CallResponse.Builder().build()
            }
            try {
                respondToCall(callDetails, response)
            } catch (_: Exception) {
                // The call may already have ended.
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

/**
 * Android 8–9 fallback. Enabled only on API < 29 (see values-v29/bools.xml). Identifies the caller
 * and shows a notification; it cannot block (that needs the default-dialer role on these versions).
 */
@AndroidEntryPoint
class LegacyPhoneStateReceiver : BroadcastReceiver() {

    @Inject lateinit var screeningManager: CallScreeningManager

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        if (intent.getStringExtra(TelephonyManager.EXTRA_STATE) != TelephonyManager.EXTRA_STATE_RINGING) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return
        @Suppress("DEPRECATION")
        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
        // Android 9 sends the broadcast twice; only the one carrying the number (needs READ_CALL_LOG) is useful.
        if (number.isNullOrBlank() && Build.VERSION.SDK_INT == Build.VERSION_CODES.P) return

        val pending = goAsync()
        receiverScope.launch {
            try {
                screeningManager.screen(number, canBlock = false)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
