package com.rskusum.whocaller.core.data.system

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.rskusum.whocaller.core.common.Clock
import com.rskusum.whocaller.core.common.IoDispatcher
import com.rskusum.whocaller.core.common.result.AppError
import com.rskusum.whocaller.core.common.result.AppResult
import com.rskusum.whocaller.core.domain.repository.BlockRepository
import com.rskusum.whocaller.core.domain.repository.CallerRepository
import com.rskusum.whocaller.core.domain.repository.ContactsRepository
import com.rskusum.whocaller.core.domain.repository.CountryRepository
import com.rskusum.whocaller.core.domain.repository.SmsRepository
import com.rskusum.whocaller.core.domain.repository.SpamRepository
import com.rskusum.whocaller.core.domain.sms.SmsClassifier
import com.rskusum.whocaller.core.common.phone.PhoneNumberNormalizer
import com.rskusum.whocaller.core.model.SmsConversation
import com.rskusum.whocaller.core.model.SmsMessage
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
 * SMS access through the Telephony provider. When WhoCaller is the default SMS app it is also
 * responsible for writing sent messages (incoming ones are written by `SmsDeliverReceiver`).
 * Message text is classified on the device and never uploaded.
 */
@Singleton
class SmsRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val classifier: SmsClassifier,
    private val blockRepository: BlockRepository,
    private val spamRepository: SpamRepository,
    private val contactsRepository: ContactsRepository,
    private val callerRepository: CallerRepository,
    private val normalizer: PhoneNumberNormalizer,
    private val countryRepository: CountryRepository,
    private val clock: Clock,
    @IoDispatcher private val io: CoroutineDispatcher,
) : SmsRepository {

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    override fun isDefaultSmsApp(): Boolean =
        Telephony.Sms.getDefaultSmsPackage(context) == context.packageName

    override fun isInboxAvailable(): Boolean = granted(Manifest.permission.READ_SMS)

    override fun canSend(): Boolean = granted(Manifest.permission.SEND_SMS)

    override suspend fun loadInbox(limit: Int): List<SmsMessage> = withContext(io) {
        query(selection = "${Telephony.Sms.TYPE} = ?", args = arrayOf(Telephony.Sms.MESSAGE_TYPE_INBOX.toString()), limit = limit)
    }

    override suspend fun loadConversations(limit: Int): List<SmsConversation> = withContext(io) {
        if (!isInboxAvailable()) return@withContext emptyList()
        // Scan recent messages and group by thread; bounded so huge inboxes stay cheap.
        val latest = LinkedHashMap<Long, SmsMessage>()
        val unread = HashMap<Long, Int>()
        query(selection = null, args = null, limit = SCAN_LIMIT).forEach { m ->
            if (!latest.containsKey(m.threadId)) latest[m.threadId] = m
            if (!m.read && !m.outgoing) unread[m.threadId] = (unread[m.threadId] ?: 0) + 1
        }
        latest.values.take(limit).map { m ->
            SmsConversation(
                threadId = m.threadId,
                address = m.address,
                displayName = nameFor(m.address),
                snippet = m.body.take(SNIPPET),
                timestamp = m.timestamp,
                unreadCount = unread[m.threadId] ?: 0,
                classification = m.classification,
            )
        }
    }

    override suspend fun loadThread(threadId: Long, limit: Int): List<SmsMessage> = withContext(io) {
        query(selection = "${Telephony.Sms.THREAD_ID} = ?", args = arrayOf(threadId.toString()), limit = limit).reversed()
    }

    override suspend fun threadIdFor(address: String): Long = withContext(io) {
        if (!isInboxAvailable() || address.isBlank()) return@withContext 0L
        try {
            Telephony.Threads.getOrCreateThreadId(context, address)
        } catch (_: Exception) {
            0L
        }
    }

    override suspend fun send(address: String, body: String): AppResult<Unit> = withContext(io) {
        val text = body.trim()
        if (address.isBlank() || text.isEmpty()) return@withContext AppResult.Failure(AppError.INVALID_NUMBER)
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            return@withContext AppResult.Failure(AppError.PERMISSION_DENIED)
        }
        try {
            val manager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            } ?: return@withContext AppResult.Failure(AppError.UNKNOWN)
            val parts = manager.divideMessage(text)
            if (parts.size > 1) {
                manager.sendMultipartTextMessage(address, null, parts, null, null)
            } else {
                manager.sendTextMessage(address, null, text, null, null)
            }
            // The default SMS app must record its own sent messages.
            if (isDefaultSmsApp()) {
                val values = ContentValues().apply {
                    put(Telephony.Sms.ADDRESS, address)
                    put(Telephony.Sms.BODY, text)
                    put(Telephony.Sms.DATE, clock.now())
                    put(Telephony.Sms.READ, 1)
                    put(Telephony.Sms.SEEN, 1)
                    put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_SENT)
                }
                context.contentResolver.insert(Telephony.Sms.Sent.CONTENT_URI, values)
            }
            AppResult.Success(Unit)
        } catch (e: SecurityException) {
            AppResult.Failure(AppError.PERMISSION_DENIED, e)
        } catch (e: Exception) {
            AppResult.Failure(AppError.UNKNOWN, e)
        }
    }

    override suspend fun markThreadRead(threadId: Long) = withContext(io) {
        if (!isDefaultSmsApp()) return@withContext
        try {
            val values = ContentValues().apply {
                put(Telephony.Sms.READ, 1)
                put(Telephony.Sms.SEEN, 1)
            }
            context.contentResolver.update(
                Telephony.Sms.CONTENT_URI,
                values,
                "${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms.READ} = 0",
                arrayOf(threadId.toString()),
            )
            Unit
        } catch (_: Exception) {
            Unit
        }
    }

    override fun changes(): Flow<Unit> {
        if (!isInboxAvailable()) return emptyFlow()
        return callbackFlow {
            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    trySend(Unit)
                }
            }
            try {
                context.contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, observer)
            } catch (_: SecurityException) {
                close()
            }
            awaitClose { context.contentResolver.unregisterContentObserver(observer) }
        }
    }

    private suspend fun query(selection: String?, args: Array<String>?, limit: Int): List<SmsMessage> {
        if (!isInboxAvailable()) return emptyList()
        val region = countryRepository.defaultRegion()
        val projection = arrayOf(
            Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE,
            Telephony.Sms.THREAD_ID, Telephony.Sms.TYPE, Telephony.Sms.READ,
        )
        val out = ArrayList<SmsMessage>()
        val blockedCache = HashMap<String, Boolean>()
        try {
            context.contentResolver.query(Telephony.Sms.CONTENT_URI, projection, selection, args, "${Telephony.Sms.DATE} DESC")?.use { c ->
                while (c.moveToNext() && out.size < limit) {
                    val address = c.getString(1).orEmpty()
                    val body = c.getString(2).orEmpty()
                    val type = c.getInt(5)
                    val outgoing = type != Telephony.Sms.MESSAGE_TYPE_INBOX
                    val key = normalizer.keyOf(address, region)
                    val flagged = if (key == null || outgoing) {
                        false
                    } else {
                        blockedCache.getOrPut(key) { blockRepository.isBlocked(key) || spamRepository.latestReportFor(key) != null }
                    }
                    out += SmsMessage(
                        id = c.getLong(0),
                        address = address,
                        body = body,
                        timestamp = c.getLong(3),
                        classification = classifier.classify(address, if (outgoing) "" else body, senderBlocked = flagged, senderReported = false),
                        threadId = c.getLong(4),
                        outgoing = outgoing,
                        read = c.getInt(6) == 1,
                    )
                }
            }
        } catch (_: SecurityException) {
            return emptyList()
        }
        return out
    }

    /** Contact name first, then a name WhoCaller already knows (cache only, no network). */
    private suspend fun nameFor(address: String): String? {
        if (address.none { it.isDigit() }) return null
        contactsRepository.lookupContactName(address)?.let { return it }
        val key = normalizer.keyOf(address, countryRepository.defaultRegion()) ?: return null
        return callerRepository.getCached(key)?.displayName
    }

    private companion object {
        const val SCAN_LIMIT = 1_500
        const val SNIPPET = 140
    }
}
