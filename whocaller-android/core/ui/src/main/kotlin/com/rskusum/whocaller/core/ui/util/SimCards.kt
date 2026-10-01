package com.rskusum.whocaller.core.ui.util

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat

/** One SIM in the phone, as Settings → SIM shows it. */
data class SimCard(
    val subscriptionId: Int,
    /** 1-based slot ("SIM 1"). */
    val slot: Int,
    /** Network provider of this SIM ("Jio", "Airtel"…). After porting this is the new network. */
    val network: String?,
    /** The SIM's own number, when the operator stored it on the SIM (many don't). */
    val number: String?,
    /** ISO country of the SIM, e.g. "in", "us". */
    val countryIso: String?,
)

/**
 * The phone's own SIMs: number and network provider read from the device, the same values the
 * SIM settings screen shows. Needs READ_PHONE_STATE (+ READ_PHONE_NUMBERS for the numbers).
 */
object SimCards {

    val PERMISSIONS = arrayOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_PHONE_NUMBERS)

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

    private fun canReadNumbers(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_NUMBERS) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission") // checked in hasPermission() / canReadNumbers()
    fun list(context: Context): List<SimCard> {
        if (!hasPermission(context)) return emptyList()
        val sm = context.getSystemService(SubscriptionManager::class.java) ?: return emptyList()
        val tm = context.getSystemService(TelephonyManager::class.java)
        val subs = try {
            sm.activeSubscriptionInfoList.orEmpty()
        } catch (_: SecurityException) {
            return emptyList()
        }
        return subs.sortedBy { it.simSlotIndex }.map { info ->
            val perSim = runCatching { tm?.createForSubscriptionId(info.subscriptionId) }.getOrNull()
            // carrierName is what Settings → SIM shows; the SIM's operator name is the fallback.
            val network = info.carrierName?.toString()?.takeIf { it.isNotBlank() }
                ?: perSim?.simOperatorName?.takeIf { it.isNotBlank() }
            SimCard(
                subscriptionId = info.subscriptionId,
                slot = info.simSlotIndex + 1,
                network = network?.let(::cleanNetworkName),
                number = numberOf(context, sm, info)?.takeIf { it.count(Char::isDigit) >= 6 },
                countryIso = info.countryIso?.takeIf { it.isNotBlank() },
            )
        }
    }

    /** The SIM holding [e164] (matched on the last 10 digits); with one SIM, that SIM. */
    fun forNumber(context: Context, e164: String): SimCard? {
        val sims = list(context)
        if (sims.size == 1) return sims.first()
        val wanted = e164.filter(Char::isDigit).takeLast(10)
        if (wanted.length < 8) return null
        return sims.firstOrNull { it.number?.filter(Char::isDigit)?.takeLast(10) == wanted }
    }

    @SuppressLint("MissingPermission", "HardwareIds") // checked in canReadNumbers(); the user's own number
    private fun numberOf(context: Context, sm: SubscriptionManager, info: SubscriptionInfo): String? = try {
        when {
            !canReadNumbers(context) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> null
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> sm.getPhoneNumber(info.subscriptionId)
            else -> {
                @Suppress("DEPRECATION")
                info.number
            }
        }?.takeIf { it.isNotBlank() }
    } catch (_: SecurityException) {
        null
    }

    /** "Jio 4G" → "Jio", "Vodafone IN"/"!dea" → "Vi", … Unknown names are kept as they are. */
    fun cleanNetworkName(raw: String): String {
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
            "cricket" in n -> "Cricket"
            "boost" in n -> "Boost Mobile"
            "google fi" in n || n == "fi" -> "Google Fi"
            else -> raw.trim().take(40)
        }
    }
}
