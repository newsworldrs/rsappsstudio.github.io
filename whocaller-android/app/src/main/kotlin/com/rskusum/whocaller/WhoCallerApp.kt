package com.rskusum.whocaller

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.rskusum.whocaller.core.common.ApplicationScope
import com.rskusum.whocaller.core.common.analytics.AnalyticsTracker
import com.rskusum.whocaller.core.common.analytics.CrashReporter
import com.rskusum.whocaller.core.domain.repository.NetworkMonitor
import com.rskusum.whocaller.core.domain.repository.SettingsRepository
import com.rskusum.whocaller.core.domain.repository.SyncController
import com.rskusum.whocaller.core.ui.notification.NotificationChannels
import com.rskusum.whocaller.firebase.AppCheckInstaller
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class WhoCallerApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var analytics: AnalyticsTracker
    @Inject lateinit var crashReporter: CrashReporter
    @Inject lateinit var syncController: SyncController
    @Inject lateinit var networkMonitor: NetworkMonitor

    @Inject @ApplicationScope
    lateinit var appScope: CoroutineScope

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        NotificationChannels.createAll(this)
        AppCheckInstaller.install(this)

        appScope.launch {
            // Apply the user's consent choices; both default to off.
            settingsRepository.settings
                .map { it.analyticsEnabled to it.crashReportsEnabled }
                .distinctUntilChanged()
                .collect { (analyticsOn, crashOn) ->
                    analytics.setEnabled(analyticsOn)
                    crashReporter.setEnabled(crashOn)
                }
        }
        appScope.launch {
            // "Syncing protection data…" whenever connectivity comes back.
            networkMonitor.isOnline.drop(1).filter { it }.collect { syncController.requestSync() }
        }
        syncController.schedulePeriodicSync()
    }
}
