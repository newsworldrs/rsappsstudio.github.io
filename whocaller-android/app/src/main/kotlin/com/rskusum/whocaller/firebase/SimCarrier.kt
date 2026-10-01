package com.rskusum.whocaller.firebase

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat

/**
 * The mobile network the user's own SIM is on right now. After number portability this can differ
 * from the network that originally issued the number (which is all a number's prefix can tell).
 */
object SimCarrier {

    /**
     * Operator of the SIM that holds [e164]. With one SIM that's the SIM; with several, the one
     * whose number matches (when Android knows it). Null rather than a guess.
     */
    fun forNumber(context: Context, e164: String): String? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return null
        val sm = context.getSystemService(SubscriptionManager::class.java) ?: return null
        val tm = context.getSystemService(TelephonyManager::class.java) ?: return null
        val subs = try {
            sm.activeSubscriptionInfoList.orEmpty()
        } catch (_: SecurityException) {
            return null
        }
        val wanted = e164.filter(Char::isDigit).takeLast(10)
        val sub = when {
            subs.size == 1 -> subs.first()
            else -> subs.firstOrNull { info -> numberOf(context, sm, info).filter(Char::isDigit).takeLast(10) == wanted && wanted.length >= 8 }
        } ?: return null
        val name = runCatching { tm.createForSubscriptionId(sub.subscriptionId).simOperatorName }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: sub.carrierName?.toString()?.takeIf { it.isNotBlank() }
        return name?.let(::clean)
    }

    private fun numberOf(context: Context, sm: SubscriptionManager, info: android.telephony.SubscriptionInfo): String = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_NUMBERS) == PackageManager.PERMISSION_GRANTED
        ) {
            sm.getPhoneNumber(info.subscriptionId)
        } else {
            @Suppress("DEPRECATION")
            info.number.orEmpty()
        }
    } catch (_: SecurityException) {
        ""
    }

    /** "Jio 4G" → "Jio", "!dea"/"Vodafone IN" → "Vi", "T-Mobile" stays… Unknown names are kept as they are. */
    fun clean(raw: String): String {
        val n = raw.trim().lowercase()
        return when {
            "jio" in n -> "Jio"
            "airtel" in n -> "Airtel"
            "vodafone" in n || "idea" in n || n == "vi" || n.startsWith("vi ") || "!dea" in n -> "Vi"
            "bsnl" in n -> "BSNL"
            "mtnl" in n -> "MTNL"
            "verizon" in n -> "Verizon"
            "t-mobile" in n || "tmobile" in n -> "T-Mobile"
            "at&t" in n || n == "att" -> "AT&T"
            "us cellular" in n || "uscellular" in n -> "US Cellular"
            "mint" in n -> "Mint Mobile"
            "visible" in n -> "Visible"
            "cricket" in n -> "Cricket"
            "boost" in n -> "Boost Mobile"
            "metro" in n -> "Metro by T-Mobile"
            "google fi" in n || n == "fi" -> "Google Fi"
            else -> raw.trim().take(MAX)
        }
    }

    private const val MAX = 40
}
