package com.rskusum.whocaller.core.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rskusum.whocaller.core.common.result.AppError
import com.rskusum.whocaller.core.common.result.AppResult
import com.rskusum.whocaller.core.data.repository.CallerRepositoryImpl
import com.rskusum.whocaller.core.data.repository.SpamRepositoryImpl
import com.rskusum.whocaller.core.database.WhoCallerDatabase
import com.rskusum.whocaller.core.domain.repository.LookupPolicy
import com.rskusum.whocaller.core.domain.repository.SyncController
import com.rskusum.whocaller.core.model.ReportReason
import com.rskusum.whocaller.core.model.SyncState
import com.rskusum.whocaller.core.network.NetworkDataSource
import com.rskusum.whocaller.core.network.UnconfiguredNetworkDataSource
import com.rskusum.whocaller.core.network.model.BusinessDto
import com.rskusum.whocaller.core.network.model.BusinessVerificationRequestDto
import com.rskusum.whocaller.core.network.model.BusinessVerificationResponseDto
import com.rskusum.whocaller.core.network.model.CountryDto
import com.rskusum.whocaller.core.network.model.NumberInfoDto
import com.rskusum.whocaller.core.network.model.PurchaseVerificationResponseDto
import com.rskusum.whocaller.core.network.model.RemoteConfigDto
import com.rskusum.whocaller.core.network.model.ReportRequestDto
import com.rskusum.whocaller.core.network.model.ReportResponseDto
import com.rskusum.whocaller.core.network.model.SpamListResponseDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Scriptable backend for repository tests. */
private class FakeNetwork : NetworkDataSource {
    override val isConfigured = true
    var numberResult: AppResult<NumberInfoDto> = AppResult.Failure(AppError.NOT_FOUND)
    var reportResult: AppResult<ReportResponseDto> = AppResult.Success(ReportResponseDto("r", true))
    var numberCalls = 0
    val reports = mutableListOf<ReportRequestDto>()

    override suspend fun getNumber(e164: String): AppResult<NumberInfoDto> {
        numberCalls++
        return numberResult
    }
    override suspend fun reportNumber(e164: String, request: ReportRequestDto): AppResult<ReportResponseDto> {
        reports += request
        return reportResult
    }
    override suspend fun signalBlock(e164: String) = AppResult.Success(Unit)
    override suspend fun searchBusinesses(query: String, region: String?) = AppResult.Success(emptyList<BusinessDto>())
    override suspend fun getBusiness(businessId: String) = AppResult.Failure(AppError.NOT_FOUND)
    override suspend fun requestBusinessVerification(request: BusinessVerificationRequestDto) =
        AppResult.Success(BusinessVerificationResponseDto("id", "PENDING"))
    override suspend fun getConfig() = AppResult.Success(RemoteConfigDto())
    override suspend fun getCountries() = AppResult.Success(emptyList<CountryDto>())
    override suspend fun getSpamList(region: String, since: Long?) =
        AppResult.Success(SpamListResponseDto(region, 0, emptyList()))
    override suspend fun verifyPurchase(productId: String, purchaseToken: String) =
        AppResult.Success(PurchaseVerificationResponseDto(true))
    override suspend fun deleteAccount() = AppResult.Success(Unit)
}

private class NoopSync : SyncController {
    var requested = 0
    override val isSyncing: Flow<Boolean> = flowOf(false)
    override fun requestSync() { requested++ }
    override fun schedulePeriodicSync() = Unit
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RepositoryTest {

    private lateinit var db: WhoCallerDatabase
    private val network = FakeNetwork()
    private var now = 1_750_000_000_000L
    private val key = "+919876543210"

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), WhoCallerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    private fun callerRepo(net: NetworkDataSource = network, dispatcher: kotlinx.coroutines.CoroutineDispatcher) =
        CallerRepositoryImpl(db.callerDao(), db.userSettingsDao(), net, { now }, dispatcher)

    @Test
    fun lookupCachesServerResultAndServesItOffline() = runTest {
        val repo = callerRepo(dispatcher = Dispatchers.IO)
        network.numberResult = AppResult.Success(NumberInfoDto(number = key, name = "ABC Services", reportCount = 5))
        val first = repo.lookup(key, LookupPolicy.CACHE_FIRST) as AppResult.Success
        assertEquals("ABC Services", first.data.displayName)

        // Offline now: fresh cache answers without a network call.
        network.numberResult = AppResult.Failure(AppError.NETWORK_UNAVAILABLE)
        val second = repo.lookup(key, LookupPolicy.CACHE_FIRST) as AppResult.Success
        assertEquals("ABC Services", second.data.displayName)
        assertEquals(1, network.numberCalls)
    }

    @Test
    fun networkFailureIsReportedAndCacheSurvives() = runTest {
        val repo = callerRepo(dispatcher = Dispatchers.IO)
        network.numberResult = AppResult.Success(NumberInfoDto(number = key, name = "X"))
        repo.lookup(key, LookupPolicy.NETWORK_FIRST)
        network.numberResult = AppResult.Failure(AppError.TIMEOUT)
        val r = repo.lookup(key, LookupPolicy.NETWORK_FIRST)
        assertEquals(AppError.TIMEOUT, (r as AppResult.Failure).error)
        assertEquals("X", repo.getCached(key)?.displayName)
    }

    @Test
    fun notFoundIsCachedAsEmptyIdentityNeverFabricated() = runTest {
        val repo = callerRepo(dispatcher = Dispatchers.IO)
        val r = repo.lookup(key, LookupPolicy.CACHE_FIRST) as AppResult.Success
        assertFalse(r.data.hasIdentity)
        assertEquals(0, r.data.reportCount)
        repo.lookup(key, LookupPolicy.CACHE_FIRST)
        assertEquals(1, network.numberCalls)
    }

    @Test
    fun unconfiguredBackendUsesCacheOnly() = runTest {
        val repo = callerRepo(UnconfiguredNetworkDataSource(), Dispatchers.IO)
        val r = repo.lookup(key, LookupPolicy.NETWORK_FIRST)
        assertEquals(AppError.BACKEND_NOT_CONFIGURED, (r as AppResult.Failure).error)
        assertNull(repo.getCached(key))
    }

    @Test
    fun reportIsStoredOfflineAndSyncedLater() = runTest {
        val sync = NoopSync()
        val dispatcher = Dispatchers.IO
        val repo = SpamRepositoryImpl(db.spamReportDao(), network, { sync }, { now }, dispatcher)

        network.reportResult = AppResult.Failure(AppError.NETWORK_UNAVAILABLE)
        val r = repo.submitReport(key, ReportReason.TELEMARKETING, "daily calls") as AppResult.Success
        assertEquals(SyncState.FAILED, r.data.syncState)
        assertEquals(1, sync.requested)

        network.reportResult = AppResult.Success(ReportResponseDto("r1", true))
        val synced = repo.syncPendingReports() as AppResult.Success
        assertEquals(1, synced.data)
        // The same client id is sent on retry so the server can de-duplicate.
        assertEquals(network.reports[0].clientReportId, network.reports[1].clientReportId)
        assertTrue(db.spamReportDao().pending(8, 10).isEmpty())
    }

    @Test
    fun serverDuplicateCountsAsSynced() = runTest {
        val repo = SpamRepositoryImpl(db.spamReportDao(), network, { NoopSync() }, { now }, Dispatchers.IO)
        network.reportResult = AppResult.Failure(AppError.DUPLICATE)
        val r = repo.submitReport(key, ReportReason.SPAM, null) as AppResult.Success
        assertEquals(SyncState.SYNCED, r.data.syncState)
    }
}
