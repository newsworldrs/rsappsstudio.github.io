package com.rskusum.whocaller.core.data.system

import android.Manifest
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.rskusum.whocaller.core.common.Clock
import com.rskusum.whocaller.core.common.IoDispatcher
import com.rskusum.whocaller.core.common.TimeUnits
import com.rskusum.whocaller.core.common.phone.PhoneNumberNormalizer
import com.rskusum.whocaller.core.common.result.AppError
import com.rskusum.whocaller.core.common.result.AppResult
import com.rskusum.whocaller.core.database.dao.ContactCacheDao
import com.rskusum.whocaller.core.database.entity.ContactCacheEntity
import com.rskusum.whocaller.core.domain.repository.ContactsRepository
import com.rskusum.whocaller.core.domain.repository.CountryRepository
import com.rskusum.whocaller.core.model.Contact
import com.rskusum.whocaller.core.model.ContactPhone
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the on-device Contacts Provider. Contacts never leave the device.
 * All calls are permission-checked and return empty results instead of throwing.
 */
@Singleton
class ContactsRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cacheDao: ContactCacheDao,
    private val normalizer: PhoneNumberNormalizer,
    private val countryRepository: CountryRepository,
    private val clock: Clock,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ContactsRepository {

    private val resolver: ContentResolver get() = context.contentResolver

    override fun hasPermission(): Boolean = granted(Manifest.permission.READ_CONTACTS)

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    override suspend fun lookupContactName(rawNumber: String): String? = withContext(io) {
        if (!hasPermission() || rawNumber.isBlank()) return@withContext null
        val key = normalizer.keyOf(rawNumber, countryRepository.defaultRegion())
        if (key != null) {
            cacheDao.get(key)?.let { if (clock.now() - it.updatedAt < CACHE_TTL_MS) return@withContext it.displayName }
        }
        val name = queryPhoneLookup(rawNumber)
        if (key != null) cacheDao.upsert(ContactCacheEntity(key, null, name, clock.now()))
        name
    }

    private fun queryPhoneLookup(rawNumber: String): String? = try {
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(rawNumber))
        resolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (_: SecurityException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override fun observeContacts(query: String): Flow<List<Contact>> {
        if (!hasPermission()) return flowOf(emptyList())
        return changes(ContactsContract.Contacts.CONTENT_URI)
            .conflate()
            .mapLatest { loadContacts(query = query, contactId = null) }
            .flowOn(io)
    }

    override suspend fun getContact(contactId: Long): Contact? = withContext(io) {
        if (!hasPermission()) null else loadContacts(query = null, contactId = contactId).firstOrNull()
    }

    override suspend fun setStarred(contactId: Long, starred: Boolean): AppResult<Unit> = withContext(io) {
        if (!granted(Manifest.permission.WRITE_CONTACTS)) return@withContext AppResult.Failure(AppError.PERMISSION_DENIED)
        try {
            val values = ContentValues().apply { put(ContactsContract.Contacts.STARRED, if (starred) 1 else 0) }
            val uri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_URI, contactId.toString())
            resolver.update(uri, values, null, null)
            AppResult.Success(Unit)
        } catch (e: Exception) {
            AppResult.Failure(AppError.STORAGE, e)
        }
    }

    override suspend fun deleteContact(contactId: Long): AppResult<Unit> = withContext(io) {
        if (!granted(Manifest.permission.WRITE_CONTACTS)) return@withContext AppResult.Failure(AppError.PERMISSION_DENIED)
        try {
            val uri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_URI, contactId.toString())
            val deleted = resolver.delete(uri, null, null)
            cacheDao.clear()
            if (deleted > 0) AppResult.Success(Unit) else AppResult.Failure(AppError.NOT_FOUND)
        } catch (e: Exception) {
            AppResult.Failure(AppError.STORAGE, e)
        }
    }

    /** Loads contacts with phone numbers, grouped per contact and sorted by name. */
    private suspend fun loadContacts(query: String?, contactId: Long?): List<Contact> {
        val region = countryRepository.defaultRegion()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
            ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI,
            ContactsContract.CommonDataKinds.Phone.STARRED,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE,
            ContactsContract.CommonDataKinds.Phone.LABEL,
        )
        val selection = StringBuilder()
        val args = mutableListOf<String>()
        if (contactId != null) {
            selection.append("${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?")
            args += contactId.toString()
        }
        val q = query?.trim().orEmpty()
        if (q.isNotEmpty()) {
            if (selection.isNotEmpty()) selection.append(" AND ")
            selection.append(
                "(${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY} LIKE ? OR " +
                    "${ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER} LIKE ? OR " +
                    "${ContactsContract.CommonDataKinds.Phone.NUMBER} LIKE ?)",
            )
            val digits = q.filter { it.isDigit() }
            args += "%$q%"
            args += if (digits.isNotEmpty()) "%$digits%" else "%$q%"
            args += "%$q%"
        }
        val byId = LinkedHashMap<Long, Contact>()
        try {
            resolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                selection.takeIf { it.isNotEmpty() }?.toString(),
                args.toTypedArray().takeIf { it.isNotEmpty() },
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY} COLLATE LOCALIZED ASC",
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val number = c.getString(5) ?: continue
                    val type = c.getInt(6)
                    val customLabel = c.getString(7)
                    val label = ContactsContract.CommonDataKinds.Phone.getTypeLabel(context.resources, type, customLabel).toString()
                    val key = normalizer.keyOf(number, region) ?: number
                    val entry = ContactPhone(number = number, numberKey = key, label = label)
                    val existing = byId[id]
                    if (existing == null) {
                        byId[id] = Contact(
                            id = id,
                            lookupKey = c.getString(1).orEmpty(),
                            displayName = c.getString(2) ?: number,
                            photoUri = c.getString(3),
                            starred = c.getInt(4) == 1,
                            phones = listOf(entry),
                        )
                    } else if (existing.phones.none { it.numberKey == key }) {
                        // Duplicate numbers across raw contacts are merged.
                        byId[id] = existing.copy(phones = existing.phones + entry)
                    }
                }
            }
        } catch (_: SecurityException) {
            return emptyList()
        }
        return byId.values.toList()
    }

    private fun changes(uri: Uri): Flow<Unit> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        trySend(Unit)
        try {
            resolver.registerContentObserver(uri, true, observer)
        } catch (_: SecurityException) {
            // Permission revoked while running: emit the initial (empty) state only.
        }
        awaitClose { resolver.unregisterContentObserver(observer) }
    }

    private companion object {
        /** Contact names change rarely; short TTL keeps screening fast without going stale. */
        const val CACHE_TTL_MS = 30 * TimeUnits.MINUTE
    }
}
