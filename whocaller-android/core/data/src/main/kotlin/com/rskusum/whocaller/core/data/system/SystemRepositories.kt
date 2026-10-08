package com.rskusum.whocaller.core.data.system

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.telephony.TelephonyManager
import com.rskusum.whocaller.core.common.phone.PhoneNumberNormalizer
import com.rskusum.whocaller.core.domain.repository.CountryRepository
import com.rskusum.whocaller.core.domain.repository.NetworkMonitor
import com.rskusum.whocaller.core.domain.repository.SettingsRepository
import com.rskusum.whocaller.core.model.Country
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
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
