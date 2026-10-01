package com.rskusum.whocaller.core.domain.repository

import com.rskusum.whocaller.core.common.result.AppResult
import com.rskusum.whocaller.core.model.AppSettings
import com.rskusum.whocaller.core.model.BlockedCall
import com.rskusum.whocaller.core.model.BlockedNumber
import com.rskusum.whocaller.core.model.Business
import com.rskusum.whocaller.core.model.CallFilter
import com.rskusum.whocaller.core.model.CallLogEntry
import com.rskusum.whocaller.core.model.CallerInfo
import com.rskusum.whocaller.core.model.Contact
import com.rskusum.whocaller.core.model.Country
import com.rskusum.whocaller.core.model.IdentifiedCall
import com.rskusum.whocaller.core.model.ReportReason
import com.rskusum.whocaller.core.model.SearchHistoryItem
import com.rskusum.whocaller.core.model.LocalProfile
import com.rskusum.whocaller.core.model.SmsConversation
import com.rskusum.whocaller.core.model.SmsMessage
import com.rskusum.whocaller.core.model.SpamReport
import com.rskusum.whocaller.core.model.UserProfile
import com.rskusum.whocaller.core.model.UserStats
import kotlinx.coroutines.flow.Flow

enum class LookupPolicy {
    /** Never touch the network (offline, or latency-critical paths without budget). */
    CACHE_ONLY,

    /** Use a fresh cached entry; otherwise ask the backend and fall back to stale cache. */
    CACHE_FIRST,

    /** Always ask the backend; fall back to cache on failure. */
    NETWORK_FIRST,
}

interface CallerRepository {
    suspend fun getCached(numberKey: String): CallerInfo?
    fun observeCached(numberKey: String): Flow<CallerInfo?>
    suspend fun lookup(numberKey: String, policy: LookupPolicy): AppResult<CallerInfo>
    /** Downloads the regional list of frequently reported numbers for offline protection. */
    suspend fun refreshSpamDatabase(regionCode: String): AppResult<Int>
    suspend fun clearCache()
}

interface SpamRepository {
    /** Stores the report locally (so it works offline) and uploads it when possible. */
    suspend fun submitReport(numberKey: String, reason: ReportReason, comment: String?): AppResult<SpamReport>
    fun observeMyReports(): Flow<List<SpamReport>>
    suspend fun latestReportFor(numberKey: String): SpamReport?
    suspend fun countReportsSince(sinceMillis: Long): Int
    suspend fun syncPendingReports(): AppResult<Int>
}

interface BlockRepository {
    fun observeBlockedNumbers(): Flow<List<BlockedNumber>>
    suspend fun isBlocked(numberKey: String): Boolean
    fun observeIsBlocked(numberKey: String): Flow<Boolean>
    suspend fun block(number: BlockedNumber)
    suspend fun unblock(numberKey: String)
    suspend fun recordBlockedCall(call: BlockedCall)
    fun observeBlockedCalls(limit: Int): Flow<List<BlockedCall>>
    suspend fun clearBlockedCallHistory()
}

interface ContactsRepository {
    fun hasPermission(): Boolean
    /** Name of the saved contact for a raw number, using the platform's fuzzy phone matching. */
    suspend fun lookupContactName(rawNumber: String): String?
    fun observeContacts(query: String): Flow<List<Contact>>
    suspend fun getContact(contactId: Long): Contact?
    suspend fun setStarred(contactId: Long, starred: Boolean): AppResult<Unit>
    suspend fun deleteContact(contactId: Long): AppResult<Unit>
}

interface CallLogRepository {
    fun hasPermission(): Boolean
    suspend fun loadPage(filter: CallFilter, offset: Int, limit: Int): List<CallLogEntry>
    suspend fun callsForNumber(numberKey: String, limit: Int): List<CallLogEntry>
    suspend fun recentUnidentified(limit: Int): List<CallLogEntry>
    /** Emits whenever the system call log changes. */
    fun changes(): Flow<Unit>
}

interface IdentifiedCallRepository {
    suspend fun record(call: IdentifiedCall)
    fun observeRecent(limit: Int): Flow<List<IdentifiedCall>>
    suspend fun clear()
}

interface SearchHistoryRepository {
    fun observe(limit: Int): Flow<List<SearchHistoryItem>>
    suspend fun add(query: String, numberKey: String, displayNumber: String)
    suspend fun delete(id: Long)
    suspend fun clear()
}

data class VerificationRequest(
    val businessName: String,
    val phoneNumber: String,
    val category: String,
    val website: String?,
    val email: String,
    val country: String,
)

interface BusinessRepository {
    suspend fun getBusiness(businessId: String, forceRefresh: Boolean = false): AppResult<Business>
    suspend fun search(query: String, regionCode: String?): AppResult<List<Business>>
    /** Starts the verification workflow on the backend. Returns a request id. */
    suspend fun requestVerification(request: VerificationRequest): AppResult<String>
}

interface SettingsRepository {
    val settings: Flow<AppSettings>
    suspend fun current(): AppSettings
    suspend fun update(transform: (AppSettings) -> AppSettings)
}

enum class StatKey { CALLS_IDENTIFIED, SPAM_BLOCKED, NUMBERS_REPORTED, NUMBERS_SEARCHED }

interface StatsRepository {
    val stats: Flow<UserStats>
    suspend fun increment(key: StatKey)
    suspend fun reset()
}

interface AuthRepository {
    val currentUser: Flow<UserProfile>
    val isBackendAvailable: Boolean
    suspend fun signInWithGoogleIdToken(idToken: String): AppResult<UserProfile>
    suspend fun signInWithEmail(email: String, password: String): AppResult<UserProfile>
    suspend fun createAccountWithEmail(email: String, password: String): AppResult<UserProfile>
    suspend fun sendPasswordReset(email: String): AppResult<Unit>
    suspend fun updateDisplayName(name: String): AppResult<Unit>
    suspend fun signOut()
    /** Deletes the account on the backend (reports are anonymised server-side) and signs out. */
    suspend fun deleteAccount(): AppResult<Unit>
    /** Short-lived ID token for the REST backend, or null for guests. */
    suspend fun idToken(forceRefresh: Boolean = false): String?
}

interface SmsRepository {
    /** True if WhoCaller may read SMS (default SMS app, or READ_SMS granted). */
    fun isInboxAvailable(): Boolean
    /** True when WhoCaller is the default SMS app (it then stores and sends all messages). */
    fun isDefaultSmsApp(): Boolean
    fun canSend(): Boolean
    suspend fun loadInbox(limit: Int): List<SmsMessage>
    suspend fun loadConversations(limit: Int): List<SmsConversation>
    suspend fun loadThread(threadId: Long, limit: Int): List<SmsMessage>
    /** Finds the conversation for an address, or 0 if there is none yet. */
    suspend fun threadIdFor(address: String): Long
    suspend fun send(address: String, body: String): AppResult<Unit>
    suspend fun markThreadRead(threadId: Long)
    /** Emits whenever the SMS provider changes. */
    fun changes(): Flow<Unit>
}

interface LocalProfileRepository {
    val profile: Flow<LocalProfile>
    suspend fun update(transform: (LocalProfile) -> LocalProfile)
    /** Copies a picked image (content:// URI string) into private storage, downscaled. Returns the stored path. */
    suspend fun importPhoto(uri: String): AppResult<String>
    suspend fun removePhoto()
}

interface CountryRepository {
    fun countries(): List<Country>
    /** Region from settings override, then SIM, then network, then locale. */
    suspend fun defaultRegion(): String
}

interface PremiumRepository {
    val isPremium: Flow<Boolean>
}

interface NetworkMonitor {
    val isOnline: Flow<Boolean>
}

interface UserDataRepository {
    /** Everything WhoCaller stores about the user on this device, as JSON. */
    suspend fun exportAsJson(): String
    suspend fun clearLocalData()
}

/** Background synchronisation of reports and the offline spam list. */
interface SyncController {
    /** True while protection data is being synced ("Syncing protection data…"). */
    val isSyncing: Flow<Boolean>
    fun requestSync()
    fun schedulePeriodicSync()
}
