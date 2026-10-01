package com.rskusum.whocaller.core.data.repository

import com.rskusum.whocaller.core.common.Clock
import com.rskusum.whocaller.core.common.IoDispatcher
import com.rskusum.whocaller.core.common.TimeUnits
import com.rskusum.whocaller.core.common.result.AppError
import com.rskusum.whocaller.core.common.result.AppResult
import com.rskusum.whocaller.core.data.mapper.toEntity
import com.rskusum.whocaller.core.data.mapper.toModel
import com.rskusum.whocaller.core.database.dao.BlockedCallDao
import com.rskusum.whocaller.core.database.dao.BlockedNumberDao
import com.rskusum.whocaller.core.database.dao.BusinessDao
import com.rskusum.whocaller.core.database.dao.CallHistoryDao
import com.rskusum.whocaller.core.database.dao.SearchHistoryDao
import com.rskusum.whocaller.core.database.dao.SpamReportDao
import com.rskusum.whocaller.core.database.dao.UserSettingsDao
import com.rskusum.whocaller.core.database.entity.SearchHistoryEntity
import com.rskusum.whocaller.core.database.entity.SpamReportEntity
import com.rskusum.whocaller.core.domain.repository.BlockRepository
import com.rskusum.whocaller.core.domain.repository.BusinessRepository
import com.rskusum.whocaller.core.domain.repository.IdentifiedCallRepository
import com.rskusum.whocaller.core.domain.repository.SearchHistoryRepository
import com.rskusum.whocaller.core.domain.repository.SpamRepository
import com.rskusum.whocaller.core.domain.repository.StatKey
import com.rskusum.whocaller.core.domain.repository.StatsRepository
import com.rskusum.whocaller.core.domain.repository.SyncController
import com.rskusum.whocaller.core.domain.repository.VerificationRequest
import com.rskusum.whocaller.core.model.BlockedCall
import com.rskusum.whocaller.core.model.BlockedNumber
import com.rskusum.whocaller.core.model.Business
import com.rskusum.whocaller.core.model.IdentifiedCall
import com.rskusum.whocaller.core.model.PhoneNumber
import com.rskusum.whocaller.core.model.ReportCallType
import com.rskusum.whocaller.core.model.ReportCategory
import com.rskusum.whocaller.core.model.ReportReason
import com.rskusum.whocaller.core.model.SearchHistoryItem
import com.rskusum.whocaller.core.model.SpamReport
import com.rskusum.whocaller.core.model.SyncState
import com.rskusum.whocaller.core.model.UserStats
import com.rskusum.whocaller.core.network.NetworkDataSource
import com.rskusum.whocaller.core.network.model.BusinessVerificationRequestDto
import com.rskusum.whocaller.core.network.model.ReportRequestDto
import dagger.Lazy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SpamRepositoryImpl @Inject constructor(
    private val dao: SpamReportDao,
    private val network: NetworkDataSource,
    private val syncController: Lazy<SyncController>,
    private val clock: Clock,
    @IoDispatcher private val io: CoroutineDispatcher,
) : SpamRepository {

    override suspend fun submitReport(numberKey: String, reason: ReportReason, comment: String?): AppResult<SpamReport> =
        save(
            SpamReportEntity(
                numberKey = numberKey,
                reason = reason.name,
                comment = comment,
                createdAt = clock.now(),
                syncState = SyncState.PENDING.name,
                clientReportId = UUID.randomUUID().toString(),
                callType = ReportCallType.MANUAL.name,
                callAnswered = false,
            ),
        )

    override suspend fun submitCallReport(
        numberKey: String,
        categories: List<ReportCategory>,
        callType: ReportCallType,
        callAnswered: Boolean,
    ): AppResult<SpamReport> {
        val picked = categories.distinct().take(ReportCategory.MAX_PER_REPORT)
        if (picked.isEmpty()) return AppResult.Failure(AppError.UNKNOWN)
        return save(
            SpamReportEntity(
                numberKey = numberKey,
                // Older code paths read a single reason; the first non-neutral pick fits best.
                reason = (picked.firstOrNull { !it.neutral } ?: picked.first()).reason.name,
                comment = null,
                createdAt = clock.now(),
                syncState = SyncState.PENDING.name,
                clientReportId = UUID.randomUUID().toString(),
                categories = picked.joinToString(",") { it.name },
                callType = callType.name,
                callAnswered = callAnswered,
            ),
        )
    }

    private suspend fun save(entity: SpamReportEntity): AppResult<SpamReport> =
        withContext(io) {
            val id = try {
                dao.insert(entity)
            } catch (e: android.database.SQLException) {
                return@withContext AppResult.Failure(AppError.STORAGE, e)
            }
            val saved = entity.copy(id = id)
            // Try to upload straight away; if that isn't possible the sync worker retries later.
            val state = withTimeoutOrNull(UPLOAD_TIMEOUT_MS) { upload(saved) } ?: SyncState.PENDING
            if (state != SyncState.SYNCED) syncController.get().requestSync()
            AppResult.Success(saved.toModel().copy(syncState = state))
        }

    override fun observeMyReports(): Flow<List<SpamReport>> = dao.observeAll().map { list -> list.map { it.toModel() } }

    override suspend fun latestReportFor(numberKey: String): SpamReport? = withContext(io) { dao.latestFor(numberKey)?.toModel() }

    override suspend fun countReportsSince(sinceMillis: Long): Int = withContext(io) { dao.countSince(sinceMillis) }

    override suspend fun syncPendingReports(): AppResult<Int> = withContext(io) {
        if (!network.isConfigured) return@withContext AppResult.Failure(AppError.BACKEND_NOT_CONFIGURED)
        var synced = 0
        for (report in dao.pending(MAX_ATTEMPTS, BATCH_SIZE)) {
            if (upload(report) == SyncState.SYNCED) synced++
        }
        AppResult.Success(synced)
    }

    /** Uploads one report and records the outcome. Numbers without E.164 can't be reported to the backend. */
    private suspend fun upload(report: SpamReportEntity): SyncState {
        if (!network.isConfigured) return SyncState.PENDING
        if (report.numberKey.startsWith(PhoneNumber.RAW_PREFIX)) {
            dao.updateState(report.id, SyncState.FAILED.name, MAX_ATTEMPTS)
            return SyncState.FAILED
        }
        val request = ReportRequestDto(
            reason = report.reason,
            comment = report.comment,
            clientReportId = report.clientReportId,
            reportedAt = report.createdAt,
            // Single-reason reports are sent with their matching category so every report has one.
            categories = report.categories.split(',').filter { it.isNotBlank() }
                .ifEmpty { listOf(categoryFor(report.reason)) },
            callType = report.callType ?: ReportCallType.MANUAL.name,
            callAnswered = report.callAnswered ?: false,
        )
        val state = when (val r = network.reportNumber(report.numberKey, request)) {
            is AppResult.Success -> SyncState.SYNCED
            // The server already has this report (idempotent retry).
            is AppResult.Failure -> if (r.error == AppError.DUPLICATE) SyncState.SYNCED else SyncState.FAILED
        }
        dao.updateState(report.id, state.name, if (state == SyncState.SYNCED) 0 else 1)
        return state
    }

    /** Category for an old single-reason report. */
    private fun categoryFor(reason: String): String = when (ReportReason.fromWire(reason)) {
        ReportReason.SPAM -> ReportCategory.SPAM
        ReportReason.SCAM -> ReportCategory.FINANCIAL_SCAM
        ReportReason.TELEMARKETING -> ReportCategory.TELEMARKETING
        ReportReason.FRAUD -> ReportCategory.FRAUD_FAKE_OFFER
        ReportReason.ROBOCALL -> ReportCategory.ROBOCALL
        ReportReason.HARASSMENT -> ReportCategory.HARASSMENT
        ReportReason.FAKE_BANK_CALL -> ReportCategory.BANKING_SCAM
        ReportReason.FAKE_DELIVERY_CALL -> ReportCategory.IMPERSONATION
        ReportReason.OTHER -> ReportCategory.OTHER
    }.name

    companion object {
        const val MAX_ATTEMPTS = 8
        const val BATCH_SIZE = 50
        const val UPLOAD_TIMEOUT_MS = 6_000L
    }
}

@Singleton
class BlockRepositoryImpl @Inject constructor(
    private val numberDao: BlockedNumberDao,
    private val callDao: BlockedCallDao,
    @IoDispatcher private val io: CoroutineDispatcher,
) : BlockRepository {
    override fun observeBlockedNumbers(): Flow<List<BlockedNumber>> = numberDao.observeAll().map { l -> l.map { it.toModel() } }
    override suspend fun isBlocked(numberKey: String): Boolean = withContext(io) { numberDao.isBlocked(numberKey) }
    override fun observeIsBlocked(numberKey: String): Flow<Boolean> = numberDao.observeIsBlocked(numberKey)
    override suspend fun block(number: BlockedNumber) = withContext(io) { numberDao.upsert(number.toEntity()) }
    override suspend fun unblock(numberKey: String) = withContext(io) { numberDao.delete(numberKey) }
    override suspend fun recordBlockedCall(call: BlockedCall) = withContext(io) {
        callDao.insert(call.toEntity())
        callDao.trim(MAX_BLOCKED_CALLS)
    }
    override fun observeBlockedCalls(limit: Int): Flow<List<BlockedCall>> = callDao.observeRecent(limit).map { l -> l.map { it.toModel() } }
    override suspend fun clearBlockedCallHistory() = withContext(io) { callDao.clear() }

    private companion object {
        const val MAX_BLOCKED_CALLS = 1_000
    }
}

@Singleton
class IdentifiedCallRepositoryImpl @Inject constructor(
    private val dao: CallHistoryDao,
    @IoDispatcher private val io: CoroutineDispatcher,
) : IdentifiedCallRepository {
    override suspend fun record(call: IdentifiedCall) = withContext(io) {
        dao.insert(call.toEntity())
        dao.trim(MAX_ROWS)
    }
    override fun observeRecent(limit: Int): Flow<List<IdentifiedCall>> = dao.observeRecent(limit).map { l -> l.map { it.toModel() } }
    override suspend fun clear() = withContext(io) { dao.clear() }

    private companion object {
        const val MAX_ROWS = 500
    }
}

@Singleton
class SearchHistoryRepositoryImpl @Inject constructor(
    private val dao: SearchHistoryDao,
    private val clock: Clock,
    @IoDispatcher private val io: CoroutineDispatcher,
) : SearchHistoryRepository {
    override fun observe(limit: Int): Flow<List<SearchHistoryItem>> = dao.observe(limit).map { l -> l.map { it.toModel() } }
    override suspend fun add(query: String, numberKey: String, displayNumber: String) = withContext(io) {
        dao.record(SearchHistoryEntity(queryText = query.take(64), numberKey = numberKey, displayNumber = displayNumber, searchedAt = clock.now()))
    }
    override suspend fun delete(id: Long) = withContext(io) { dao.delete(id) }
    override suspend fun clear() = withContext(io) { dao.clear() }
}

@Singleton
class StatsRepositoryImpl @Inject constructor(
    private val dao: UserSettingsDao,
    @IoDispatcher private val io: CoroutineDispatcher,
) : StatsRepository {
    override val stats: Flow<UserStats> = dao.observeAll().map { rows ->
        val values = rows.associate { it.settingKey to (it.longValue ?: 0L) }
        UserStats(
            callsIdentified = values[key(StatKey.CALLS_IDENTIFIED)] ?: 0,
            spamBlocked = values[key(StatKey.SPAM_BLOCKED)] ?: 0,
            numbersReported = values[key(StatKey.NUMBERS_REPORTED)] ?: 0,
            numbersSearched = values[key(StatKey.NUMBERS_SEARCHED)] ?: 0,
        )
    }

    override suspend fun increment(key: StatKey) = withContext(io) { dao.increment(key(key)) }

    override suspend fun reset() = withContext(io) { dao.deleteWithPrefix(PREFIX) }

    private fun key(k: StatKey) = PREFIX + k.name.lowercase()

    private companion object {
        const val PREFIX = "stat."
    }
}

@Singleton
class BusinessRepositoryImpl @Inject constructor(
    private val dao: BusinessDao,
    private val network: NetworkDataSource,
    private val clock: Clock,
    @IoDispatcher private val io: CoroutineDispatcher,
) : BusinessRepository {

    override suspend fun getBusiness(businessId: String, forceRefresh: Boolean): AppResult<Business> = withContext(io) {
        val cached = dao.get(businessId)
        if (cached != null && !forceRefresh && clock.now() - cached.updatedAt < TTL_MS) {
            return@withContext AppResult.Success(cached.toModel())
        }
        when (val r = network.getBusiness(businessId)) {
            is AppResult.Success -> {
                val entity = r.data.toEntity(clock.now())
                dao.upsertAll(listOf(entity))
                AppResult.Success(entity.toModel())
            }
            is AppResult.Failure -> cached?.let { AppResult.Success(it.toModel()) } ?: r
        }
    }

    override suspend fun search(query: String, regionCode: String?): AppResult<List<Business>> = withContext(io) {
        val q = query.trim()
        if (q.length < 2) return@withContext AppResult.Success(emptyList())
        when (val r = network.searchBusinesses(q, regionCode)) {
            is AppResult.Success -> {
                val entities = r.data.map { it.toEntity(clock.now()) }
                dao.upsertAll(entities)
                AppResult.Success(entities.map { it.toModel() })
            }
            is AppResult.Failure -> {
                // Offline: fall back to businesses seen before.
                val local = dao.search(q, 50)
                if (local.isNotEmpty()) AppResult.Success(local.map { it.toModel() }) else r
            }
        }
    }

    override suspend fun requestVerification(request: VerificationRequest): AppResult<String> = withContext(io) {
        val dto = BusinessVerificationRequestDto(
            businessName = request.businessName.trim().take(120),
            phoneNumber = request.phoneNumber,
            category = request.category.trim().take(60),
            website = request.website?.trim()?.takeIf { it.isNotEmpty() },
            email = request.email.trim(),
            country = request.country,
        )
        when (val r = network.requestBusinessVerification(dto)) {
            is AppResult.Success -> AppResult.Success(r.data.requestId)
            is AppResult.Failure -> r
        }
    }

    private companion object {
        const val TTL_MS = 7 * TimeUnits.DAY
    }
}
