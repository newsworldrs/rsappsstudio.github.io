package com.rskusum.whocaller.firebase

import android.content.Context
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings
import com.rskusum.whocaller.feature.premium.AdsPolicy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Firebase Remote Config flags. Defaults apply when Firebase isn't configured or offline.
 * Only feature switches live here — never secrets.
 */
@Singleton
class FirebaseRemoteFlags @Inject constructor(
    @ApplicationContext context: Context,
) : AdsPolicy {

    private val _adsEnabled = MutableStateFlow(true)
    override val remoteAdsEnabled: StateFlow<Boolean> = _adsEnabled.asStateFlow()

    init {
        if (context.isFirebaseAvailable()) {
            val config = FirebaseRemoteConfig.getInstance()
            config.setConfigSettingsAsync(
                FirebaseRemoteConfigSettings.Builder().setMinimumFetchIntervalInSeconds(12 * 60 * 60).build(),
            )
            config.setDefaultsAsync(mapOf(KEY_ADS_ENABLED to true, KEY_MIN_VERSION to 1L))
            config.fetchAndActivate().addOnCompleteListener {
                _adsEnabled.value = config.getBoolean(KEY_ADS_ENABLED)
            }
        }
    }

    private companion object {
        const val KEY_ADS_ENABLED = "ads_enabled"
        const val KEY_MIN_VERSION = "min_supported_version"
    }
}
