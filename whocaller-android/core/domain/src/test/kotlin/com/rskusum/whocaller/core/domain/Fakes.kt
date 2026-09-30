package com.rskusum.whocaller.core.domain

import com.rskusum.whocaller.core.common.analytics.AnalyticsEvent
import com.rskusum.whocaller.core.common.analytics.AnalyticsTracker
import com.rskusum.whocaller.core.common.result.AppError
import com.rskusum.whocaller.core.common.result.AppResult
import com.rskusum.whocaller.core.domain.repository.BlockRepository
import com.rskusum.whocaller.core.domain.repository.CallerRepository
import com.rskusum.whocaller.core.domain.repository.ContactsRepository
import com.rskusum.whocaller.core.domain.repository.CountryRepository
import com.rskusum.whocaller.core.domain.repository.IdentifiedCallRepository
import com.rskusum.whocaller.core.domain.repository.LookupPolicy
import com.rskusum.whocaller.core.domain.repository.SearchHistoryRepository
import com.rskusum.whocaller.core.domain.repository.SettingsRepository
import com.rskusum.whocaller.core.domain.repository.SpamRepository
import com.rskusum.whocaller.core.domain.repository.StatKey
import com.rskusum.whocaller.core.domain.repository.StatsRepository
import com.rskusum.whocaller.core.model.AppSettings
import com.rskusum.whocaller.core.model.BlockedCall
import com.rskusum.whocaller.core.model.BlockedNumber
import com.rskusum.whocaller.core.model.CallerInfo
import com.rskusum.whocaller.core.model.Contact
import com.rskusum.whocaller.core.model.Country
import com.rskusum.whocaller.core.model.IdentifiedCall
import com.rskusum.whocaller.core.model.ReportReason
import com.rskusum.whocaller.core.model.SearchHistoryItem
import com.rskusum.whocaller.core.model.SpamReport
import com.rskusum.whocaller.core.model.SyncState
import com.rskusum.whocaller.core.model.UserStats
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

class FakeSettingsRepository(initial: AppSettings = AppSettings()) : SettingsRepository {
    private val state = MutableStateFlow(initial)
    override val settings: Flow<AppSettings> = state
    override suspend fun current() = state.value
    override suspend fun update(transform: (AppSettings) -> AppSettings) { state.value = transform(state.value) }
}

class FakeCountryRepository(private val region: String = "IN") : CountryRepository {
    override fun countries() = listOf(Country("IN", 91, "India"))
    override suspend fun defaultRegion() = region
}

class FakeContactsRepository(
    var permission: Boolean = true,
    val names: MutableMap<String, String> = mutableMapOf(),
) : ContactsRepository {
    override fun hasPermission() = permission
    override suspend fun lookupContactName(rawNumber: String) = names[rawNumber]
    override fun observeContacts(query: String): Flow<List<Contact>> = flowOf(emptyList())
    override suspend fun getContact(contactId: Long): Contact? = null
    override suspend fun setStarred(contactId: Long, starred: Boolean) = AppResult.Success(Unit)
    override suspend fun deleteContact(contactId: Long) = AppResult.Success(Unit)
}

class FakeBlockRepository : BlockRepository {
    val blocked = MutableStateFlow<Map<String, BlockedNumber>>(emptyMap())
    val blockedCalls = mutableListOf<BlockedCall>()
    override fun observeBlockedNumbers() = blocked.map { it.values.toList() }
    override suspend fun isBlocked(numberKey: String) = numberKey in blocked.value
    override fun observeIsBlocked(numberKey: String) = blocked.map { numberKey in it }
    override suspend fun block(number: BlockedNumber) { blocked.value = blocked.value + (number.numberKey to number) }
    override suspend fun unblock(numberKey: String) { blocked.value = blocked.value - numberKey }
    override suspend fun recordBlockedCall(call: BlockedCall) { blockedCalls += call }
    override fun observeBlockedCalls(limit: Int) = flowOf(blockedCalls.toList())
    override suspend fun clearBlockedCallHistory() { blockedCalls.clear() }
}

class FakeCallerRepository(
    val remote: MutableMap<String, CallerInfo> = mutableMapOf(),
    val cache: MutableMap<String, CallerInfo> = mutableMapOf(),
    var remoteError: AppError? = null,
    var remoteDelayMs: Long = 0,
) : CallerRepository {
    var lookups = 0
    override suspend fun getCached(numberKey: String) = cache[numberKey]
    override fun observeCached(numberKey: String) = flowOf(cache[numberKey])
    override suspend fun lookup(numberKey: String, policy: LookupPolicy): AppResult<CallerInfo> {
        lookups++
        if (policy == LookupPolicy.CACHE_ONLY) {
            return cache[numberKey]?.let { AppResult.Success(it) } ?: AppResult.Failure(AppError.NOT_FOUND)
        }
        if (remoteDelayMs > 0) delay(remoteDelayMs)
        remoteError?.let { return AppResult.Failure(it) }
        val info = remote[numberKey] ?: return AppResult.Failure(AppError.NOT_FOUND)
        cache[numberKey] = info
        return AppResult.Success(info)
    }
    override suspend fun refreshSpamDatabase(regionCode: String) = AppResult.Success(0)
    override suspend fun clearCache() = cache.clear()
}

class FakeSpamRepository : SpamRepository {
    val reports = mutableListOf<SpamReport>()
    override suspend fun submitReport(numberKey: String, reason: ReportReason, comment: String?): AppResult<SpamReport> {
        val r = SpamReport(reports.size + 1L, numberKey, reason, comment, now, SyncState.PENDING)
        reports += r
        return AppResult.Success(r)
    }
    var now = 0L
    override fun observeMyReports() = flowOf(reports.toList())
    override suspend fun latestReportFor(numberKey: String) = reports.filter { it.numberKey == numberKey }.maxByOrNull { it.createdAt }
    override suspend fun countReportsSince(sinceMillis: Long) = reports.count { it.createdAt >= sinceMillis }
    override suspend fun syncPendingReports() = AppResult.Success(0)
}

class FakeIdentifiedCallRepository : IdentifiedCallRepository {
    val calls = mutableListOf<IdentifiedCall>()
    override suspend fun record(call: IdentifiedCall) { calls += call }
    override fun observeRecent(limit: Int) = flowOf(calls.toList())
    override suspend fun clear() = calls.clear()
}

class FakeStatsRepository : StatsRepository {
    val counts = mutableMapOf<StatKey, Long>()
    override val stats: Flow<UserStats> = flowOf(UserStats())
    override suspend fun increment(key: StatKey) { counts[key] = (counts[key] ?: 0) + 1 }
    override suspend fun reset() = counts.clear()
}

class FakeSearchHistoryRepository : SearchHistoryRepository {
    val items = mutableListOf<SearchHistoryItem>()
    override fun observe(limit: Int) = flowOf(items.toList())
    override suspend fun add(query: String, numberKey: String, displayNumber: String) {
        items.removeAll { it.numberKey == numberKey }
        items += SearchHistoryItem(items.size + 1L, query, numberKey, displayNumber, 0)
    }
    override suspend fun delete(id: Long) { items.removeAll { it.id == id } }
    override suspend fun clear() = items.clear()
}

class RecordingAnalytics : AnalyticsTracker {
    val events = mutableListOf<AnalyticsEvent>()
    override fun track(event: AnalyticsEvent) { events += event }
    override fun setEnabled(enabled: Boolean) = Unit
}
