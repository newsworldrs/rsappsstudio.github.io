package com.rskusum.whocaller.feature.premium

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/*
 * ADS OFF for the first two years (until about October 2028): WhoCaller shows no ads at all and the
 * AdMob SDK isn't in the app. Play Console → App content → Ads: "No, my app does not contain ads".
 *
 * To switch ads back on later:
 *  1. feature/premium/build.gradle.kts: restore play-services-ads and ump.
 *  2. Uncomment AdMobAdsManager below (and its imports, kept in the comment) and bind it in PremiumModule.
 *  3. app: restore the AdMob <meta-data>/<property> in AndroidManifest.xml, the banner unit id
 *     provider in di/AppModule.kt, adsManager.initialize in MainActivity and the banner calls in
 *     WhoCallerNavHost (search the code for "ADS OFF").
 *  4. Update Play Console (Ads: yes) and the Data safety form.
 */
import com.rskusum.whocaller.core.domain.repository.PremiumRepository

/**
 * Advertising abstraction. Ads are never shown to premium users, during calls, over caller ID,
 * in permission dialogs or in misleading places: only the app places a [Banner] explicitly
 * (home and search screens).
 */
interface AdsManager {
    val adsEnabled: StateFlow<Boolean>

    /** Collects GDPR/consent where required, then initialises the ads SDK. Call from an Activity. */
    fun initialize(activity: Activity)

    @Composable
    fun Banner(modifier: Modifier)
}

/** Remote switch for ads (e.g. Firebase Remote Config), so ads can be turned off without a release. */
interface AdsPolicy {
    val remoteAdsEnabled: StateFlow<Boolean>
}

/** Used while ads are off: never shows anything and never loads an ads SDK. */
@Singleton
class NoAdsManager @Inject constructor() : AdsManager {
    private val disabled = MutableStateFlow(false)
    override val adsEnabled: StateFlow<Boolean> = disabled.asStateFlow()
    override fun initialize(activity: Activity) = Unit

    @Composable
    override fun Banner(modifier: Modifier) = Unit
}

/* ADS OFF (first two years). Previous AdMob implementation, kept for later:


import android.app.Activity
import android.content.Context
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.MobileAds
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import com.rskusum.whocaller.core.domain.repository.PremiumRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton


@Singleton
class AdMobAdsManager @Inject constructor(
    @ApplicationContext private val context: Context,
    premiumRepository: PremiumRepository,
    adsPolicy: AdsPolicy,
    @Named(BANNER_UNIT_ID) private val bannerUnitId: String,
) : AdsManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sdkReady = MutableStateFlow(false)
    private val initStarted = AtomicBoolean(false)
    private val consentInformation: ConsentInformation = UserMessagingPlatform.getConsentInformation(context)

    override val adsEnabled: StateFlow<Boolean> = combine(
        premiumRepository.isPremium,
        sdkReady,
        adsPolicy.remoteAdsEnabled,
    ) { premium, ready, remote ->
        !premium && ready && remote && bannerUnitId.isNotBlank()
    }.stateIn(scope, SharingStarted.Eagerly, false)

    override fun initialize(activity: Activity) {
        if (!initStarted.compareAndSet(false, true)) return
        val params = ConsentRequestParameters.Builder().build()
        consentInformation.requestConsentInfoUpdate(
            activity,
            params,
            {
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) {
                    if (consentInformation.canRequestAds()) startSdk()
                }
            },
            { _ -> if (consentInformation.canRequestAds()) startSdk() },
        )
        // Consent obtained in a previous session: start immediately.
        if (consentInformation.canRequestAds()) startSdk()
    }

    private fun startSdk() {
        if (sdkReady.value) return
        MobileAds.initialize(context) { sdkReady.value = true }
    }

    @Composable
    override fun Banner(modifier: Modifier) {
        val enabled by adsEnabled.collectAsStateWithLifecycle()
        if (!enabled) return
        val ctx = LocalContext.current
        val widthDp = LocalConfiguration.current.screenWidthDp
        val adView = remember(widthDp) {
            AdView(ctx).apply {
                adUnitId = bannerUnitId
                setAdSize(AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(ctx, widthDp))
                loadAd(AdRequest.Builder().build())
            }
        }
        DisposableEffect(adView) { onDispose { adView.destroy() } }
        AndroidView(factory = { adView }, modifier = modifier.fillMaxWidth())
    }

    companion object {
        const val BANNER_UNIT_ID = "admob_banner_unit_id"
    }
}

*/

@Module
@InstallIn(SingletonComponent::class)
abstract class PremiumModule {
    @Binds abstract fun premiumRepository(impl: BillingManager): PremiumRepository
    // ADS OFF: @Binds abstract fun adsManager(impl: AdMobAdsManager): AdsManager
    @Binds abstract fun adsManager(impl: NoAdsManager): AdsManager
}
