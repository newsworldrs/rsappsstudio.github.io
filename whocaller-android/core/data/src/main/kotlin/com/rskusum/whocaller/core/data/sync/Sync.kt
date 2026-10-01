package com.rskusum.whocaller.core.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.rskusum.whocaller.core.common.analytics.CrashReporter
import com.rskusum.whocaller.core.common.result.AppError
import com.rskusum.whocaller.core.common.result.AppResult
import com.rskusum.whocaller.core.domain.repository.CallerRepository
import com.rskusum.whocaller.core.domain.repository.CountryRepository
import com.rskusum.whocaller.core.domain.repository.SettingsRepository
import com.rskusum.whocaller.core.domain.repository.SpamRepository
import com.rskusum.whocaller.core.domain.repository.SyncController
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Uploads pending spam reports and refreshes the offline spam list for the user's region.
 * Runs only with network; retried with exponential backoff.
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val spamRepository: SpamRepository,
    private val callerRepository: CallerRepository,
    private val countryRepository: CountryRepository,
    private val settingsRepository: SettingsRepository,
    private val crashReporter: CrashReporter,
    private val smsModelStore: SmsModelStore,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val reports = spamRepository.syncPendingReports()
            runCatching { smsModelStore.refresh() }
            val list = if (settingsRepository.current().spamProtectionEnabled) {
                callerRepository.refreshSpamDatabase(countryRepository.defaultRegion())
            } else {
                AppResult.Success(0)
            }
            val retryable = listOf(reports, list).any { r ->
                val e = (r as? AppResult.Failure)?.error
                e == AppError.NETWORK_UNAVAILABLE || e == AppError.TIMEOUT || e == AppError.SERVER
            }
            if (retryable && runAttemptCount < MAX_RETRIES) Result.retry() else Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            crashReporter.recordNonFatal(e, "sync_worker")
            Result.failure()
        }
    }

    private companion object {
        const val MAX_RETRIES = 5
    }
}

@Singleton
class WorkManagerSyncController @Inject constructor(
    @ApplicationContext private val context: Context,
) : SyncController {

    private val workManager get() = WorkManager.getInstance(context)

    private val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    override val isSyncing: Flow<Boolean>
        get() = combine(
            workManager.getWorkInfosForUniqueWorkFlow(ONE_TIME_WORK),
            workManager.getWorkInfosForUniqueWorkFlow(PERIODIC_WORK),
        ) { a, b -> (a + b).any { it.state == WorkInfo.State.RUNNING } }
            .map { it }
            .distinctUntilChanged()

    override fun requestSync() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork(ONE_TIME_WORK, ExistingWorkPolicy.KEEP, request)
    }

    override fun schedulePeriodicSync() {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(12, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    companion object {
        const val ONE_TIME_WORK = "whocaller_sync_now"
        const val PERIODIC_WORK = "whocaller_sync_periodic"
    }
}
