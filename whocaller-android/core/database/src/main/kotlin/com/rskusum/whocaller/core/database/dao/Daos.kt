package com.rskusum.whocaller.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.rskusum.whocaller.core.database.entity.BlockedCallEntity
import com.rskusum.whocaller.core.database.entity.BlockedNumberEntity
import com.rskusum.whocaller.core.database.entity.BusinessEntity
import com.rskusum.whocaller.core.database.entity.CallHistoryEntity
import com.rskusum.whocaller.core.database.entity.CallerEntity
import com.rskusum.whocaller.core.database.entity.ContactCacheEntity
import com.rskusum.whocaller.core.database.entity.SearchHistoryEntity
import com.rskusum.whocaller.core.database.entity.SpamReportEntity
import com.rskusum.whocaller.core.database.entity.UserSettingsEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CallerDao {
    @Query("SELECT * FROM callers WHERE phoneNumber = :key LIMIT 1")
    suspend fun get(key: String): CallerEntity?

    @Query("SELECT * FROM callers WHERE phoneNumber = :key LIMIT 1")
    fun observe(key: String): Flow<CallerEntity?>

    @Query("SELECT * FROM callers WHERE phoneNumber IN (:keys)")
    suspend fun getAll(keys: List<String>): List<CallerEntity>

    @Upsert
    suspend fun upsert(entity: CallerEntity)

    @Upsert
    suspend fun upsertAll(entities: List<CallerEntity>)

    /** Keeps explicitly looked-up rows; replaces only rows that came from a previous spam list. */
    @Transaction
    suspend fun replaceSpamList(entries: List<CallerEntity>) {
        deleteSpamListRows()
        val existing = getAll(entries.map { it.phoneNumber }).associateBy { it.phoneNumber }
        upsertAll(entries.filter { existing[it.phoneNumber]?.fromSpamList != false })
    }

    @Query("DELETE FROM callers WHERE fromSpamList = 1")
    suspend fun deleteSpamListRows()

    @Query("DELETE FROM callers WHERE updatedAt < :before AND fromSpamList = 0")
    suspend fun deleteOlderThan(before: Long): Int

    @Query("SELECT phoneNumber FROM callers WHERE updatedAt < :before AND fromSpamList = 0 ORDER BY updatedAt ASC LIMIT :limit")
    suspend fun staleKeys(before: Long, limit: Int): List<String>

    @Query("SELECT COUNT(*) FROM callers")
    suspend fun count(): Int

    @Query("DELETE FROM callers")
    suspend fun clear()
}

@Dao
interface SpamReportDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: SpamReportEntity): Long

    @Query("SELECT * FROM spam_reports ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<SpamReportEntity>>

    @Query("SELECT * FROM spam_reports ORDER BY createdAt DESC")
    suspend fun getAll(): List<SpamReportEntity>

    @Query("SELECT * FROM spam_reports WHERE numberKey = :key ORDER BY createdAt DESC LIMIT 1")
    suspend fun latestFor(key: String): SpamReportEntity?

    @Query("SELECT COUNT(*) FROM spam_reports WHERE createdAt >= :since")
    suspend fun countSince(since: Long): Int

    @Query("SELECT * FROM spam_reports WHERE syncState != 'SYNCED' AND attempts < :maxAttempts ORDER BY createdAt ASC LIMIT :limit")
    suspend fun pending(maxAttempts: Int, limit: Int): List<SpamReportEntity>

    @Query("UPDATE spam_reports SET syncState = :state, attempts = attempts + :attemptDelta WHERE id = :id")
    suspend fun updateState(id: Long, state: String, attemptDelta: Int)

    @Query("DELETE FROM spam_reports")
    suspend fun clear()
}

@Dao
interface CallHistoryDao {
    @Insert
    suspend fun insert(entity: CallHistoryEntity): Long

    @Query("SELECT * FROM call_history ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<CallHistoryEntity>>

    @Query("SELECT * FROM call_history ORDER BY timestamp DESC")
    suspend fun getAll(): List<CallHistoryEntity>

    /** Keeps the table bounded on low-storage devices. */
    @Query("DELETE FROM call_history WHERE id NOT IN (SELECT id FROM call_history ORDER BY timestamp DESC LIMIT :keep)")
    suspend fun trim(keep: Int)

    @Query("DELETE FROM call_history")
    suspend fun clear()
}

@Dao
interface BlockedNumberDao {
    @Query("SELECT * FROM blocked_numbers ORDER BY blockedAt DESC")
    fun observeAll(): Flow<List<BlockedNumberEntity>>

    @Query("SELECT * FROM blocked_numbers ORDER BY blockedAt DESC")
    suspend fun getAll(): List<BlockedNumberEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM blocked_numbers WHERE numberKey = :key)")
    suspend fun isBlocked(key: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM blocked_numbers WHERE numberKey = :key)")
    fun observeIsBlocked(key: String): Flow<Boolean>

    @Upsert
    suspend fun upsert(entity: BlockedNumberEntity)

    @Query("DELETE FROM blocked_numbers WHERE numberKey = :key")
    suspend fun delete(key: String)

    @Query("DELETE FROM blocked_numbers")
    suspend fun clear()
}

@Dao
interface BlockedCallDao {
    @Insert
    suspend fun insert(entity: BlockedCallEntity): Long

    @Query("SELECT * FROM blocked_calls ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<BlockedCallEntity>>

    @Query("SELECT * FROM blocked_calls ORDER BY timestamp DESC LIMIT 1")
    suspend fun latest(): BlockedCallEntity?

    @Query("DELETE FROM blocked_calls WHERE id NOT IN (SELECT id FROM blocked_calls ORDER BY timestamp DESC LIMIT :keep)")
    suspend fun trim(keep: Int)

    @Query("DELETE FROM blocked_calls")
    suspend fun clear()
}

@Dao
interface SearchHistoryDao {
    @Query("SELECT * FROM search_history ORDER BY searchedAt DESC LIMIT :limit")
    fun observe(limit: Int): Flow<List<SearchHistoryEntity>>

    @Query("SELECT * FROM search_history ORDER BY searchedAt DESC")
    suspend fun getAll(): List<SearchHistoryEntity>

    /** One row per number: searching again moves it to the top. */
    @Transaction
    suspend fun record(entity: SearchHistoryEntity) {
        deleteByNumber(entity.numberKey)
        insert(entity)
        trim(MAX_ROWS)
    }

    @Insert
    suspend fun insert(entity: SearchHistoryEntity): Long

    @Query("DELETE FROM search_history WHERE numberKey = :key")
    suspend fun deleteByNumber(key: String)

    @Query("DELETE FROM search_history WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM search_history WHERE id NOT IN (SELECT id FROM search_history ORDER BY searchedAt DESC LIMIT :keep)")
    suspend fun trim(keep: Int)

    @Query("DELETE FROM search_history")
    suspend fun clear()

    companion object {
        const val MAX_ROWS = 200
    }
}

@Dao
interface ContactCacheDao {
    @Query("SELECT * FROM contact_cache WHERE numberKey = :key LIMIT 1")
    suspend fun get(key: String): ContactCacheEntity?

    @Upsert
    suspend fun upsert(entity: ContactCacheEntity)

    @Query("DELETE FROM contact_cache")
    suspend fun clear()
}

@Dao
interface BusinessDao {
    @Query("SELECT * FROM businesses WHERE businessId = :id LIMIT 1")
    suspend fun get(id: String): BusinessEntity?

    @Query("SELECT * FROM businesses WHERE name LIKE '%' || :query || '%' OR category LIKE '%' || :query || '%' ORDER BY verified DESC, name LIMIT :limit")
    suspend fun search(query: String, limit: Int): List<BusinessEntity>

    @Upsert
    suspend fun upsertAll(entities: List<BusinessEntity>)

    @Query("DELETE FROM businesses")
    suspend fun clear()
}

@Dao
interface UserSettingsDao {
    @Query("SELECT * FROM user_settings")
    fun observeAll(): Flow<List<UserSettingsEntity>>

    @Query("SELECT * FROM user_settings WHERE settingKey = :key LIMIT 1")
    suspend fun get(key: String): UserSettingsEntity?

    @Upsert
    suspend fun upsert(entity: UserSettingsEntity)

    @Transaction
    suspend fun increment(key: String) {
        val current = get(key)?.longValue ?: 0L
        upsert(UserSettingsEntity(settingKey = key, longValue = current + 1, stringValue = null))
    }

    @Query("DELETE FROM user_settings WHERE settingKey LIKE :prefix || '%'")
    suspend fun deleteWithPrefix(prefix: String)

    @Query("DELETE FROM user_settings")
    suspend fun clear()
}
