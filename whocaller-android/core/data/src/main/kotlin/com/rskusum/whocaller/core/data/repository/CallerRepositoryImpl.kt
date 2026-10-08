package com.rskusum.whocaller.core.data.repository

import com.rskusum.whocaller.core.common.Clock
import com.rskusum.whocaller.core.common.IoDispatcher
import com.rskusum.whocaller.core.common.TimeUnits
import com.rskusum.whocaller.core.common.result.AppError
import com.rskusum.whocaller.core.common.result.AppResult
import com.rskusum.whocaller.core.data.mapper.toCallerInfo
import com.rskusum.whocaller.core.data.mapper.toEntity
import com.rskusum.whocaller.core.data.mapper.toModel
import com.rskusum.whocaller.core.database.dao.CallerDao
import com.rskusum.whocaller.core.database.dao.UserSettingsDao
import com.rskusum.whocaller.core.database.entity.UserSettingsEntity
import com.rskusum.whocaller.core.domain.repository.CallerRepository
import com.rskusum.whocaller.core.domain.repository.LookupPolicy
import com.rskusum.whocaller.core.model.CallerInfo
import com.rskusum.whocaller.core.model.InfoSource
import com.rskusum.whocaller.core.model.PhoneNumber
import com.rskusum.whocaller.core.network.NetworkDataSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Offline-first caller lookups: Room is the source of truth, the backend refreshes it.
 * A "not found" answer is cached too (as an empty row) so unknown numbers aren't re-queried on every call.
 */
@Singleton
class CallerRepositoryImpl @Inject constructor(
    private val callerDao: CallerDao,
    private val userSettingsDao: UserSettingsDao,
    private val network: NetworkDataSource,
    private val clock: Clock,
    @IoDispatcher private val io: CoroutineDispatcher,
) : CallerRepository {

    override suspend fun getCached(numberKey: String): CallerInfo? = withContext(io) {
        callerDao.get(numberKey)?.toModel()
    }

    override fun observeCached(numberKey: String): Flow<CallerInfo?> =
        callerDao.observe(numberKey).map { it?.toModel() }

    override suspend fun lookup(numberKey: String, policy: LookupPolicy): AppResult<CallerInfo> = withContext(io) {
        val cachedEntity = callerDao.get(numberKey)
        val cached = cachedEntity?.toModel()
        val canQueryBackend = network.isConfigured && !numberKey.startsWith(PhoneNumber.RAW_PREFIX)

        val fresh = cachedEntity != null && clock.now() - cachedEntity.updatedAt < ttlFor(cachedEntity.fromSpamList, cached)
        when {
            policy == LookupPolicy.CACHE_ONLY || !canQueryBackend ->
                return@withContext cached?.let { AppResult.Success(it) }
                    ?: AppResult.Failure(if (canQueryBackend) AppError.NOT_FOUND else network.notConfiguredOr(AppError.NOT_FOUND))
            policy == LookupPolicy.CACHE_FIRST && fresh && cached != null -> return@withContext AppResult.Success(cached)
        }

        when (val remote = network.getNumber(numberKey)) {
            is AppResult.Success -> {
                val info = remote.data.toCallerInfo(numberKey, clock.now())
                callerDao.upsert(info.toEntity())
                AppResult.Success(info)
            }
            is AppResult.Failure -> if (remote.error == AppError.NOT_FOUND) {
                // Honest negative result: nothing known about this number.
                val empty = CallerInfo.empty(numberKey).copy(source = InfoSource.SERVER, updatedAt = clock.now())
                callerDao.upsert(empty.toEntity())
                AppResult.Success(empty)
            } else {
                remote
            }
        }
    }

    override suspend fun refreshSpamDatabase(regionCode: String): AppResult<Int> = withContext(io) {
        if (!network.isConfigured) return@withContext AppResult.Failure(AppError.BACKEND_NOT_CONFIGURED)
        val syncKey = SPAM_LIST_SYNC_PREFIX + regionCode
        when (val r = network.getSpamList(regionCode, since = null)) {
            is AppResult.Success -> {
                val now = clock.now()
                val entities = r.data.entries
                    .filter { it.number.startsWith("+") }
                    .take(MAX_SPAM_LIST_ENTRIES)
                    .map { it.toCallerInfo(it.number, now).toEntity(fromSpamList = true) }
                callerDao.replaceSpamList(entities)
                callerDao.deleteOlderThan(now - CACHE_RETENTION_MS)
                userSettingsDao.upsert(UserSettingsEntity(syncKey, r.data.generatedAt, null))
                AppResult.Success(entities.size)
            }
            is AppResult.Failure -> r
        }
    }

    override suspend fun clearCache() = withContext(io) { callerDao.clear() }

    private fun ttlFor(fromSpamList: Boolean, info: CallerInfo?): Long = when {
        fromSpamList -> SPAM_LIST_TTL_MS
        info != null && !info.hasIdentity && info.reportCount == 0 -> NEGATIVE_TTL_MS
        else -> POSITIVE_TTL_MS
    }

    private fun NetworkDataSource.notConfiguredOr(error: AppError) =
        if (isConfigured) error else AppError.BACKEND_NOT_CONFIGURED

    companion object {
        const val POSITIVE_TTL_MS = 72 * TimeUnits.HOUR
        const val NEGATIVE_TTL_MS = 24 * TimeUnits.HOUR
        const val SPAM_LIST_TTL_MS = 7 * TimeUnits.DAY
        const val CACHE_RETENTION_MS = 90 * TimeUnits.DAY
        const val MAX_SPAM_LIST_ENTRIES = 20_000
        const val SPAM_LIST_SYNC_PREFIX = "sync.spamlist."
    }
}
