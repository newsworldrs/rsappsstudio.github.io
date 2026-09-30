package com.rskusum.whocaller.core.data.system

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Telephony
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.rskusum.whocaller.core.common.IoDispatcher
import com.rskusum.whocaller.core.common.phone.PhoneNumberNormalizer
import com.rskusum.whocaller.core.domain.repository.BlockRepository
import com.rskusum.whocaller.core.domain.repository.CountryRepository
import com.rskusum.whocaller.core.domain.repository.NetworkMonitor
import com.rskusum.whocaller.core.domain.repository.SettingsRepository
import com.rskusum.whocaller.core.domain.repository.SmsRepository
import com.rskusum.whocaller.core.domain.repository.SpamRepository
import com.rskusum.whocaller.core.domain.sms.SmsClassifier
import com.rskusum.whocaller.core.model.Country
import com.rskusum.whocaller.core.model.SmsMessage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Countries WhoCaller is configured for. Adding a country is one line here (and optionally backend
 * support); parsing rules for every region already come from libphonenumber.
 */
object CountryConfig {
    val FEATURED: List<String> = listOf("IN", "US", "CA", "GB", "AU", "DE", "FR", "AE", "SG")
}

@Singleton
class CountryRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val normalizer: PhoneNumberNormalizer,
) : CountryRepository {

    private val all: List<Country> by lazy {
        normalizer.supportedRegions()
            .map { Country(it, normalizer.countryCodeFor(it), Locale("", it).getDisplayCountry(Locale.ENGLISH)) }
            .sortedWith(compareBy<Country> { it.regionCode !in CountryConfig.FEATURED }.thenBy { it.name })
    }

    override fun countries(): List<Country> = all

    override suspend fun defaultRegion(): String {
        settingsRepository.current().defaultRegion?.let { return it }
        return detectedRegion
    }

    private val detectedRegion: String by lazy {
        val tm = context.getSystemService(TelephonyManager::class.java)
        val candidates = listOfNotNull(
            runCatching { tm?.simCountryIso }.getOrNull(),
            runCatching { tm?.networkCountryIso }.getOrNull(),
            Locale.getDefault().country,
        )
        candidates.map { it.uppercase(Locale.ROOT) }
            .firstOrNull { it.length == 2 && it in normalizer.supportedRegions() }
            ?: "US"
    }
}

@Singleton
class NetworkMonitorImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : NetworkMonitor {
    override val isOnline: Flow<Boolean> = callbackFlow {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        if (cm == null) {
            trySend(false)
            close()
            return@callbackFlow
        }
        val networks = mutableSetOf<Network>()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                networks += network
                trySend(true)
            }
            override fun onLost(network: Network) {
                networks -= network
                trySend(networks.isNotEmpty())
            }
        }
        val initial = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        trySend(initial)
        try {
            cm.registerDefaultNetworkCallback(callback)
        } catch (_: SecurityException) {
            // ACCESS_NETWORK_STATE is a normal permission; this only happens on unusual ROMs.
        }
        awaitClose { runCatching { cm.unregisterNetworkCallback(callback) } }
    }.conflate().distinctUntilChanged()
}

/**
 * Reads the SMS inbox *only* if this build declares READ_SMS and the user granted it. The default
 * build does not declare it (Google Play restricts SMS permissions to default SMS apps and approved
 * exceptions); users can still check any message by sharing it to WhoCaller. See docs/PRIVACY.md.
 */
@Singleton
class SmsRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val classifier: SmsClassifier,
    private val blockRepository: BlockRepository,
    private val spamRepository: SpamRepository,
    private val normalizer: PhoneNumberNormalizer,
    private val countryRepository: CountryRepository,
    @IoDispatcher private val io: CoroutineDispatcher,
) : SmsRepository {

    override fun isInboxAvailable(): Boolean =
        isDeclared(Manifest.permission.READ_SMS) &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    override suspend fun loadInbox(limit: Int): List<SmsMessage> = withContext(io) {
        if (!isInboxAvailable()) return@withContext emptyList()
        val region = countryRepository.defaultRegion()
        val uri = Telephony.Sms.Inbox.CONTENT_URI
        val projection = arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE)
        val out = ArrayList<SmsMessage>()
        try {
            context.contentResolver.query(uri, projection, null, null, "${Telephony.Sms.DATE} DESC")?.use { c ->
                while (c.moveToNext() && out.size < limit) {
                    val address = c.getString(1).orEmpty()
                    val body = c.getString(2).orEmpty()
                    val key = normalizer.keyOf(address, region)
                    val blocked = key != null && blockRepository.isBlocked(key)
                    val reported = key != null && spamRepository.latestReportFor(key) != null
                    out += SmsMessage(
                        id = c.getLong(0),
                        address = address,
                        body = body,
                        timestamp = c.getLong(3),
                        classification = classifier.classify(address, body, blocked, reported),
                    )
                }
            }
        } catch (_: SecurityException) {
            return@withContext emptyList()
        }
        out
    }

    private fun isDeclared(permission: String): Boolean = try {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        }
        info.requestedPermissions?.contains(permission) == true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}
