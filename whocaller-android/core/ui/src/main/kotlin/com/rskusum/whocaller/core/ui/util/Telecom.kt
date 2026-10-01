package com.rskusum.whocaller.core.ui.util

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.rskusum.whocaller.core.ui.R

/**
 * Telephony helpers: video-call capability, placing calls through Telecom, and WhatsApp.
 */
object TelecomActions {

    private fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * True only if the SIM/carrier phone account says it can make video calls (e.g. ViLTE).
     * Needs READ_PHONE_STATE to read phone accounts; without it we can't know, so we say no.
     */
    fun supportsVideoCalling(context: Context): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return false
        val telecom = context.getSystemService(TelecomManager::class.java) ?: return false
        return try {
            val accounts = telecom.callCapablePhoneAccounts
            val preferred = telecom.getDefaultOutgoingPhoneAccount(PhoneAccount.SCHEME_TEL)
            val handles = listOfNotNull(preferred) + accounts
            handles.any { handle ->
                val account = telecom.getPhoneAccount(handle)
                // VIDEO_CALLING = ready now; SUPPORTS_VIDEO_CALLING = the SIM can do it (IMS may register later).
                account != null && (
                    account.hasCapabilities(PhoneAccount.CAPABILITY_VIDEO_CALLING) ||
                        account.hasCapabilities(PhoneAccount.CAPABILITY_SUPPORTS_VIDEO_CALLING)
                    )
            }
        } catch (_: SecurityException) {
            false
        }
    }

    /**
     * The phone's own "Video calling" switch (Settings → SIM / mobile network). Null when unknown
     * (no phone-state permission). Carrier video calls (ViLTE) need it on, on both phones.
     */
    fun isVideoCallingSwitchOn(context: Context): Boolean? {
        if (!granted(context, Manifest.permission.READ_PHONE_STATE)) return null
        val tm = context.getSystemService(android.telephony.TelephonyManager::class.java) ?: return null
        return try {
            @Suppress("DEPRECATION")
            tm.isVideoCallingEnabled
        } catch (_: SecurityException) {
            null
        }
    }

    /** Opens the mobile-network settings, where VoLTE and Video calling are switched on. */
    fun openMobileNetworkSettings(context: Context) {
        val intents = listOf(
            Intent(android.provider.Settings.ACTION_NETWORK_OPERATOR_SETTINGS),
            Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS),
        )
        for (intent in intents) {
            if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
                return
            } catch (_: ActivityNotFoundException) {
                // try the next one
            }
        }
    }

    fun canPlaceCalls(context: Context): Boolean = granted(context, Manifest.permission.CALL_PHONE)

    /**
     * Places a call via Telecom (audio or bidirectional video). Without CALL_PHONE it falls back to
     * the dialer with the number filled in, so the user still confirms the call.
     */
    fun placeCall(context: Context, number: String, video: Boolean = false, account: PhoneAccountHandle? = null) {
        val uri = Uri.fromParts(PhoneAccount.SCHEME_TEL, number, null)
        val telecom = context.getSystemService(TelecomManager::class.java)
        if (telecom != null && ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            try {
                val extras = Bundle()
                if (video) extras.putInt(TelecomManager.EXTRA_START_CALL_WITH_VIDEO_STATE, VideoProfile.STATE_BIDIRECTIONAL)
                if (account != null) extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, account)
                telecom.placeCall(uri, extras)
                return
            } catch (_: SecurityException) {
                // Fall through to the dialer.
            }
        }
        ActionIntents.dial(context, number)
    }

    /** Calls voicemail through Telecom (needs CALL_PHONE). */
    fun callVoicemail(context: Context): Boolean {
        val telecom = context.getSystemService(TelecomManager::class.java) ?: return false
        if (!canPlaceCalls(context)) return false
        return try {
            telecom.placeCall(Uri.fromParts(PhoneAccount.SCHEME_VOICEMAIL, "", null), Bundle())
            true
        } catch (_: SecurityException) {
            false
        }
    }

    // ---------- WhatsApp ----------

    private val WHATSAPP_PACKAGES = listOf("com.whatsapp", "com.whatsapp.w4b")

    /** Package of the installed WhatsApp (personal or Business), or null. */
    fun whatsAppPackage(context: Context): String? = WHATSAPP_PACKAGES.firstOrNull { pkg ->
        try {
            context.packageManager.getPackageInfo(pkg, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    /**
     * Opens a WhatsApp chat with [e164] (international format). Uses WhatsApp's public
     * click-to-chat link, restricted to the installed WhatsApp app.
     */
    fun openWhatsApp(context: Context, e164: String) {
        val digits = e164.filter { it.isDigit() }
        val pkg = whatsAppPackage(context)
        if (digits.length < 7 || pkg == null) {
            Toast.makeText(context, R.string.no_app_to_handle, Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$digits")).setPackage(pkg)
        if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, R.string.no_app_to_handle, Toast.LENGTH_SHORT).show()
        }
    }
}
