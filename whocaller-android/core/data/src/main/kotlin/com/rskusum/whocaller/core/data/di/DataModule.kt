package com.rskusum.whocaller.core.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.rskusum.whocaller.core.common.ApplicationScope
import com.rskusum.whocaller.core.common.Clock
import com.rskusum.whocaller.core.common.DefaultDispatcher
import com.rskusum.whocaller.core.common.IoDispatcher
import com.rskusum.whocaller.core.common.SystemClock
import com.rskusum.whocaller.core.common.spam.RuleBasedSpamScoreEngine
import com.rskusum.whocaller.core.common.spam.SpamScoreEngine
import com.rskusum.whocaller.core.data.repository.BlockRepositoryImpl
import com.rskusum.whocaller.core.data.repository.BusinessRepositoryImpl
import com.rskusum.whocaller.core.data.repository.CallerRepositoryImpl
import com.rskusum.whocaller.core.data.repository.IdentifiedCallRepositoryImpl
import com.rskusum.whocaller.core.data.repository.SearchHistoryRepositoryImpl
import com.rskusum.whocaller.core.data.repository.SettingsRepositoryImpl
import com.rskusum.whocaller.core.data.repository.SpamRepositoryImpl
import com.rskusum.whocaller.core.data.repository.StatsRepositoryImpl
import com.rskusum.whocaller.core.data.repository.UserDataRepositoryImpl
import com.rskusum.whocaller.core.data.sync.WorkManagerSyncController
import com.rskusum.whocaller.core.data.system.CallLogRepositoryImpl
import com.rskusum.whocaller.core.data.system.ContactsRepositoryImpl
import com.rskusum.whocaller.core.data.system.CountryRepositoryImpl
import com.rskusum.whocaller.core.data.system.NetworkMonitorImpl
import com.rskusum.whocaller.core.data.system.SmsRepositoryImpl
import com.rskusum.whocaller.core.data.repository.LocalProfileRepositoryImpl
import com.rskusum.whocaller.core.domain.repository.LocalProfileRepository
import com.rskusum.whocaller.core.domain.repository.BlockRepository
import com.rskusum.whocaller.core.domain.repository.BusinessRepository
import com.rskusum.whocaller.core.domain.repository.CallLogRepository
import com.rskusum.whocaller.core.domain.repository.CallerRepository
import com.rskusum.whocaller.core.domain.repository.ContactsRepository
import com.rskusum.whocaller.core.domain.repository.CountryRepository
import com.rskusum.whocaller.core.domain.repository.IdentifiedCallRepository
import com.rskusum.whocaller.core.domain.repository.NetworkMonitor
import com.rskusum.whocaller.core.domain.repository.SearchHistoryRepository
import com.rskusum.whocaller.core.domain.repository.SettingsRepository
import com.rskusum.whocaller.core.domain.repository.SmsRepository
import com.rskusum.whocaller.core.domain.repository.SpamRepository
import com.rskusum.whocaller.core.domain.repository.StatsRepository
import com.rskusum.whocaller.core.domain.repository.SyncController
import com.rskusum.whocaller.core.domain.repository.UserDataRepository
import com.rskusum.whocaller.core.network.AppCheckTokenProvider
import com.rskusum.whocaller.core.network.AuthTokenProvider
import com.rskusum.whocaller.core.network.HttpClientFactory
import com.rskusum.whocaller.core.network.NetworkConfig
import com.rskusum.whocaller.core.network.NetworkDataSource
import com.rskusum.whocaller.core.network.RetrofitNetworkDataSource
import com.rskusum.whocaller.core.network.UnconfiguredNetworkDataSource
import com.rskusum.whocaller.core.network.dev.DevNetworkDataSource
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

/**
 * Supplied by the app module from BuildConfig. [allowDevBackend] must be false for release builds so
 * the development fixtures can never reach users.
 */
data class BackendEnvironment(
    val config: NetworkConfig,
    val allowDevBackend: Boolean,
)

@Module
@InstallIn(SingletonComponent::class)
abstract class DataBindingsModule {
    @Binds abstract fun settings(impl: SettingsRepositoryImpl): SettingsRepository
    @Binds abstract fun caller(impl: CallerRepositoryImpl): CallerRepository
    @Binds abstract fun spam(impl: SpamRepositoryImpl): SpamRepository
    @Binds abstract fun block(impl: BlockRepositoryImpl): BlockRepository
    @Binds abstract fun identified(impl: IdentifiedCallRepositoryImpl): IdentifiedCallRepository
    @Binds abstract fun searchHistory(impl: SearchHistoryRepositoryImpl): SearchHistoryRepository
    @Binds abstract fun stats(impl: StatsRepositoryImpl): StatsRepository
    @Binds abstract fun business(impl: BusinessRepositoryImpl): BusinessRepository
    @Binds abstract fun contacts(impl: ContactsRepositoryImpl): ContactsRepository
    @Binds abstract fun callLog(impl: CallLogRepositoryImpl): CallLogRepository
    @Binds abstract fun country(impl: CountryRepositoryImpl): CountryRepository
    @Binds abstract fun network(impl: NetworkMonitorImpl): NetworkMonitor
    @Binds abstract fun sms(impl: SmsRepositoryImpl): SmsRepository
    @Binds abstract fun userData(impl: UserDataRepositoryImpl): UserDataRepository
    @Binds abstract fun localProfile(impl: LocalProfileRepositoryImpl): LocalProfileRepository
    @Binds abstract fun sync(impl: WorkManagerSyncController): SyncController
    @Binds abstract fun spamEngine(impl: RuleBasedSpamScoreEngine): SpamScoreEngine
    @Binds abstract fun clock(impl: SystemClock): Clock
}

@Module
@InstallIn(SingletonComponent::class)
object DataProvidersModule {

    @Provides
    @IoDispatcher
    fun ioDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @DefaultDispatcher
    fun defaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    @Provides
    @Singleton
    @ApplicationScope
    fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun preferencesDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            produceFile = { context.preferencesDataStoreFile("whocaller_settings") },
        )

    @Provides
    @Singleton
    fun networkDataSource(
        environment: BackendEnvironment,
        authTokenProvider: AuthTokenProvider,
        appCheckTokenProvider: AppCheckTokenProvider,
        clock: Clock,
    ): NetworkDataSource = when {
        environment.config.isConfigured -> RetrofitNetworkDataSource(
            RetrofitNetworkDataSource.createApi(
                environment.config.baseUrl,
                HttpClientFactory.create(environment.config, authTokenProvider, appCheckTokenProvider),
            ),
        )
        environment.allowDevBackend -> DevNetworkDataSource(clock)
        else -> UnconfiguredNetworkDataSource()
    }
}
