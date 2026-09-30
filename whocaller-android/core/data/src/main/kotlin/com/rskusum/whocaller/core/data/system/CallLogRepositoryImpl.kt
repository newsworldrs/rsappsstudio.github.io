package com.rskusum.whocaller.core.data.system

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.database.Cursor
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import androidx.core.content.ContextCompat
import com.rskusum.whocaller.core.common.IoDispatcher
import com.rskusum.whocaller.core.common.phone.NormalizationResult
import com.rskusum.whocaller.core.common.phone.PhoneNumberNormalizer
import com.rskusum.whocaller.core.common.spam.SpamScoreEngine
import com.rskusum.whocaller.core.common.spam.SpamSignals
import com.rskusum.whocaller.core.data.mapper.toModel
import com.rskusum.whocaller.core.database.dao.BlockedNumberDao
import com.rskusum.whocaller.core.database.dao.CallerDao
import com.rskusum.whocaller.core.domain.repository.CallLogRepository
import com.rskusum.whocaller.core.domain.repository.CountryRepository
import com.rskusum.whocaller.core.model.CallFilter
import com.rskusum.whocaller.core.model.CallLogEntry
import com.rskusum.whocaller.core.model.CallType
import com.rskusum.whocaller.core.model.SpamCategory
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the system call log page by page (never all at once) and enriches each row with cached
 * WhoCaller data. Nothing here is uploaded.
 */
@Singleton
class CallLogRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val normalizer: PhoneNumberNormalizer,
    private val countryRepository: CountryRepository,
    private val callerDao: CallerDao,
    private val blockedNumberDao: BlockedNumberDao,
    private val engine: SpamScoreEngine,
    @IoDispatcher private val io: CoroutineDispatcher,
) : CallLogRepository {

    override fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED

    override suspend fun loadPage(filter: CallFilter, offset: Int, limit: Int): List<CallLogEntry> = withContext(io) {
        if (!hasPermission()) return@withContext emptyList()
        when (filter) {
            CallFilter.SPAM -> scanFiltered(offset, limit) { it.isSpam }
            CallFilter.UNKNOWN -> scanFiltered(offset, limit) { it.isUnknown && !it.isHidden }
            else -> enrich(queryRaw(selectionFor(filter), offset, limit))
        }
    }

    override suspend fun callsForNumber(numberKey: String, limit: Int): List<CallLogEntry> = withContext(io) {
        if (!hasPermission()) return@withContext emptyList()
        scanFiltered(0, limit) { it.numberKey == numberKey }
    }

    override suspend fun recentUnidentified(limit: Int): List<CallLogEntry> = withContext(io) {
        if (!hasPermission()) return@withContext emptyList()
        scanFiltered(0, limit * 3) { it.isUnknown && !it.isHidden }
            .distinctBy { it.numberKey }
            .take(limit)
    }

    override fun changes(): Flow<Unit> {
        if (!hasPermission()) return emptyFlow()
        return callbackFlow {
            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    trySend(Unit)
                }
            }
            try {
                context.contentResolver.registerContentObserver(CallLog.Calls.CONTENT_URI, true, observer)
            } catch (_: SecurityException) {
                close()
            }
            awaitClose { context.contentResolver.unregisterContentObserver(observer) }
        }
    }

    private fun selectionFor(filter: CallFilter): Pair<String?, Array<String>?> = when (filter) {
        CallFilter.MISSED -> "${CallLog.Calls.TYPE} = ?" to arrayOf(CallLog.Calls.MISSED_TYPE.toString())
        CallFilter.INCOMING -> "${CallLog.Calls.TYPE} = ?" to arrayOf(CallLog.Calls.INCOMING_TYPE.toString())
        CallFilter.OUTGOING -> "${CallLog.Calls.TYPE} = ?" to arrayOf(CallLog.Calls.OUTGOING_TYPE.toString())
        else -> null to null
    }

    /** Scans the log in chunks until [limit] matches after skipping [offset] matches (bounded work). */
    private suspend fun scanFiltered(offset: Int, limit: Int, predicate: (CallLogEntry) -> Boolean): List<CallLogEntry> {
        val out = ArrayList<CallLogEntry>(limit)
        var skipped = 0
        var cursorOffset = 0
        while (out.size < limit && cursorOffset < MAX_SCAN_ROWS) {
            val chunk = enrich(queryRaw(null to null, cursorOffset, SCAN_CHUNK))
            if (chunk.isEmpty()) break
            for (entry in chunk) {
                if (!predicate(entry)) continue
                if (skipped < offset) {
                    skipped++
                } else {
                    out += entry
                    if (out.size == limit) break
                }
            }
            cursorOffset += SCAN_CHUNK
        }
        return out
    }

    private data class RawCall(
        val id: Long,
        val number: String?,
        val cachedName: String?,
        val type: Int,
        val date: Long,
        val duration: Long,
        val presentation: Int,
    )

    private fun queryRaw(selection: Pair<String?, Array<String>?>, offset: Int, limit: Int): List<RawCall> {
        val uri = CallLog.Calls.CONTENT_URI.buildUpon()
            .appendQueryParameter(CallLog.Calls.LIMIT_PARAM_KEY, limit.toString())
            .appendQueryParameter(CallLog.Calls.OFFSET_PARAM_KEY, offset.toString())
            .build()
        val projection = arrayOf(
            CallLog.Calls._ID,
            CallLog.Calls.NUMBER,
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION,
            CallLog.Calls.NUMBER_PRESENTATION,
        )
        return try {
            context.contentResolver.query(uri, projection, selection.first, selection.second, "${CallLog.Calls.DATE} DESC")
                ?.use { c -> readAll(c) }
                .orEmpty()
        } catch (_: SecurityException) {
            emptyList()
        } catch (_: IllegalArgumentException) {
            emptyList()
        }
    }

    private fun readAll(c: Cursor): List<RawCall> {
        val list = ArrayList<RawCall>(c.count.coerceAtLeast(0))
        while (c.moveToNext()) {
            list += RawCall(
                id = c.getLong(0),
                number = c.getString(1),
                cachedName = c.getString(2)?.takeIf { it.isNotBlank() },
                type = c.getInt(3),
                date = c.getLong(4),
                duration = c.getLong(5),
                presentation = c.getInt(6),
            )
        }
        return list
    }

    private suspend fun enrich(rows: List<RawCall>): List<CallLogEntry> {
        if (rows.isEmpty()) return emptyList()
        val region = countryRepository.defaultRegion()
        val parsed = rows.map { row ->
            val hidden = row.presentation != CallLog.Calls.PRESENTATION_ALLOWED || row.number.isNullOrBlank()
            val n = if (hidden) null else (normalizer.normalize(row.number, region) as? NormalizationResult.Parsed)?.number
            Triple(row, n, hidden || n == null && PhoneNumberNormalizer.isHiddenMarker(row.number.orEmpty()))
        }
        val keys = parsed.mapNotNull { it.second?.key }.distinct()
        val cached = if (keys.isEmpty()) emptyMap() else callerDao.getAll(keys).associateBy { it.phoneNumber }
        val blocked = blockedNumberDao.getAll().map { it.numberKey }.toHashSet()

        return parsed.map { (row, number, hidden) ->
            val key = number?.key ?: HIDDEN_KEY
            val info = cached[key]?.toModel()
            val score = if (row.cachedName != null) {
                0
            } else {
                engine.score(SpamSignals.from(info, isContact = false, userBlocked = key in blocked, userReported = null)).score
            }
            CallLogEntry(
                id = row.id,
                rawNumber = row.number.orEmpty(),
                numberKey = key,
                displayNumber = number?.display ?: row.number.orEmpty(),
                contactName = row.cachedName,
                type = mapType(row.type),
                timestamp = row.date,
                durationSeconds = row.duration,
                cachedName = info?.displayName,
                category = info?.category ?: SpamCategory.UNKNOWN,
                spamScore = score,
                isHidden = hidden,
            )
        }
    }

    private fun mapType(type: Int): CallType = when (type) {
        CallLog.Calls.INCOMING_TYPE -> CallType.INCOMING
        CallLog.Calls.OUTGOING_TYPE -> CallType.OUTGOING
        CallLog.Calls.MISSED_TYPE -> CallType.MISSED
        CallLog.Calls.REJECTED_TYPE -> CallType.REJECTED
        CallLog.Calls.BLOCKED_TYPE -> CallType.BLOCKED
        CallLog.Calls.VOICEMAIL_TYPE -> CallType.VOICEMAIL
        else -> CallType.UNKNOWN
    }

    private companion object {
        const val HIDDEN_KEY = "hidden"
        const val SCAN_CHUNK = 200
        const val MAX_SCAN_ROWS = 3_000
    }
}
