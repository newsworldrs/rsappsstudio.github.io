package com.rskusum.whocaller.di

import android.content.Context
import com.rskusum.whocaller.BuildConfig
import com.rskusum.whocaller.core.common.analytics.AnalyticsTracker
import com.rskusum.whocaller.core.common.analytics.CrashReporter
import com.rskusum.whocaller.core.data.di.BackendEnvironment
import com.rskusum.whocaller.core.domain.repository.AuthRepository
import com.rskusum.whocaller.core.network.AppCheckTokenProvider
import com.rskusum.whocaller.core.network.AuthTokenProvider
import com.rskusum.whocaller.core.network.BaseNetwork
import com.rskusum.whocaller.core.network.NetworkConfig
import com.rskusum.whocaller.core.network.NetworkDataSource
import com.rskusum.whocaller.firebase.FirestoreNetworkDataSource
import com.rskusum.whocaller.firebase.isFirebaseAvailable
import com.rskusum.whocaller.firebase.FirebaseAnalyticsTracker
import com.rskusum.whocaller.firebase.FirebaseAppCheckTokenProvider
import com.rskusum.whocaller.firebase.FirebaseAuthRepository
import com.rskusum.whocaller.firebase.FirebaseCrashReporter
import com.rskusum.whocaller.firebase.FirebasePhoneAuthGateway
import com.rskusum.whocaller.firebase.FirebaseRemoteFlags
import com.rskusum.whocaller.feature.premium.AdsPolicy
import com.rskusum.whocaller.feature.premium.AdMobAdsManager
import com.rskusum.whocaller.feature.profile.PhoneAuthGateway
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AppBindingsModule {
    @Binds abstract fun analytics(impl: FirebaseAnalyticsTracker): AnalyticsTracker
    @Binds abstract fun crashReporter(impl: FirebaseCrashReporter): CrashReporter
    @Binds abstract fun auth(impl: FirebaseAuthRepository): AuthRepository
    @Binds abstract fun appCheck(impl: FirebaseAppCheckTokenProvider): AppCheckTokenProvider
    @Binds abstract fun phoneAuth(impl: FirebasePhoneAuthGateway): PhoneAuthGateway
    @Binds abstract fun adsPolicy(impl: FirebaseRemoteFlags): AdsPolicy
}

@Module
@InstallIn(SingletonComponent::class)
object AppProvidersModule {

    @Provides
    @Singleton
    fun backendEnvironment(): BackendEnvironment = BackendEnvironment(
        config = NetworkConfig(
            baseUrl = BuildConfig.API_BASE_URL,
            userAgent = "WhoCaller/${BuildConfig.VERSION_NAME} (Android)",
            debugLogging = BuildConfig.DEBUG,
        ),
        allowDevBackend = BuildConfig.ALLOW_DEV_BACKEND,
    )

    /** Firestore for caller data when google-services.json is present; otherwise the REST/dev backend. */
    @Provides
    @Singleton
    fun networkDataSource(
        @ApplicationContext context: Context,
        @BaseNetwork base: NetworkDataSource,
    ): NetworkDataSource = if (context.isFirebaseAvailable()) FirestoreNetworkDataSource(base) else base

    @Provides
    @Named(AdMobAdsManager.BANNER_UNIT_ID)
    fun bannerUnitId(): String = BuildConfig.ADMOB_BANNER_ID

    @Provides
    @Singleton
    fun authTokenProvider(auth: dagger.Lazy<AuthRepository>): AuthTokenProvider =
        AuthTokenProvider { auth.get().idToken(forceRefresh = false) }
}
