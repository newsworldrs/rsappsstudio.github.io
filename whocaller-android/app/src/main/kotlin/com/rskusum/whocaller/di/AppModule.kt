package com.rskusum.whocaller.di

import com.rskusum.whocaller.BuildConfig
import com.rskusum.whocaller.core.common.analytics.AnalyticsTracker
import com.rskusum.whocaller.core.common.analytics.CrashReporter
import com.rskusum.whocaller.core.data.di.BackendEnvironment
import com.rskusum.whocaller.core.domain.repository.AuthRepository
import com.rskusum.whocaller.core.network.AppCheckTokenProvider
import com.rskusum.whocaller.core.network.AuthTokenProvider
import com.rskusum.whocaller.core.network.NetworkConfig
import com.rskusum.whocaller.firebase.FirebaseAnalyticsTracker
import com.rskusum.whocaller.firebase.FirebaseAppCheckTokenProvider
import com.rskusum.whocaller.firebase.FirebaseAuthRepository
import com.rskusum.whocaller.firebase.FirebaseCrashReporter
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AppBindingsModule {
    @Binds abstract fun analytics(impl: FirebaseAnalyticsTracker): AnalyticsTracker
    @Binds abstract fun crashReporter(impl: FirebaseCrashReporter): CrashReporter
    @Binds abstract fun auth(impl: FirebaseAuthRepository): AuthRepository
    @Binds abstract fun appCheck(impl: FirebaseAppCheckTokenProvider): AppCheckTokenProvider
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

    @Provides
    @Singleton
    fun authTokenProvider(auth: dagger.Lazy<AuthRepository>): AuthTokenProvider =
        AuthTokenProvider { auth.get().idToken(forceRefresh = false) }
}
