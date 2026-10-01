package com.rskusum.whocaller.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Cached caller information, keyed by the normalized number (E.164 or `raw:` fallback) so that
 * `+91XXXXXXXXXX`, `91XXXXXXXXXX` and `0XXXXXXXXXX` share one row.
 */
@Entity(tableName = "callers", indices = [Index("updatedAt"), Index("spamScore")])
data class CallerEntity(
    @PrimaryKey val phoneNumber: String,
    val displayName: String?,
    val identityType: String,
    val category: String?,
    /** Server score, -1 when unknown. */
    val spamScore: Int,
    val serverConfidence: Float?,
    val reportCount: Int,
    val reportsLast24h: Int,
    val reportsLast7d: Int,
    val blockCount: Int,
    val lastReportedAt: Long?,
    /** "CATEGORY:count" pairs separated by commas. */
    val categoryVotes: String,
    val verified: Boolean,
    val businessId: String?,
    val carrier: String?,
    val lineType: String?,
    val regionCode: String?,
    /** True when the row came from the downloaded regional spam list rather than an explicit lookup. */
    val fromSpamList: Boolean,
    val updatedAt: Long,
    /** Outside spam list that flagged the number (v3). */
    val listedBy: String? = null,
)

@Entity(
    tableName = "spam_reports",
    indices = [Index("numberKey"), Index("createdAt"), Index(value = ["clientReportId"], unique = true)],
)
data class SpamReportEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val numberKey: String,
    val reason: String,
    val comment: String?,
    val createdAt: Long,
    val syncState: String,
    val clientReportId: String,
    val attempts: Int = 0,
    /** Comma-separated ReportCategory names (post-call screen), "" for single-reason reports. Added in v2. */
    @ColumnInfo(defaultValue = "") val categories: String = "",
    val callType: String? = null,
    val callAnswered: Boolean? = null,
)

/** Calls WhoCaller screened on this device. Never uploaded. */
@Entity(tableName = "call_history", indices = [Index("timestamp"), Index("numberKey")])
data class CallHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val numberKey: String,
    val displayNumber: String,
    val name: String?,
    val label: String,
    val decision: String,
    val spamScore: Int,
    val timestamp: Long,
)

@Entity(tableName = "blocked_numbers", indices = [Index("blockedAt")])
data class BlockedNumberEntity(
    @PrimaryKey val numberKey: String,
    val displayNumber: String,
    val label: String?,
    val category: String?,
    val blockedAt: Long,
    val source: String,
)

@Entity(tableName = "blocked_calls", indices = [Index("timestamp")])
data class BlockedCallEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val numberKey: String,
    val displayNumber: String,
    val reason: String,
    val category: String,
    val timestamp: Long,
)

@Entity(tableName = "search_history", indices = [Index(value = ["numberKey"], unique = true), Index("searchedAt")])
data class SearchHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val queryText: String,
    val numberKey: String,
    val displayNumber: String,
    val searchedAt: Long,
)

/** Short-lived local cache of number → saved contact name, to keep call screening fast. Never uploaded. */
@Entity(tableName = "contact_cache")
data class ContactCacheEntity(
    @PrimaryKey val numberKey: String,
    val contactId: Long?,
    val displayName: String?,
    val updatedAt: Long,
)

@Entity(tableName = "businesses", indices = [Index("name")])
data class BusinessEntity(
    @PrimaryKey val businessId: String,
    val name: String,
    /** Comma-separated E.164 numbers. */
    val phoneNumbers: String,
    val category: String?,
    val address: String?,
    val website: String?,
    val logoUrl: String?,
    val email: String?,
    val verified: Boolean,
    val verificationDate: Long?,
    val country: String?,
    val language: String?,
    val hours: String?,
    val rating: Float?,
    val ratingCount: Int?,
    val updatedAt: Long,
)

/** Small key–value table for local counters and sync bookkeeping (not preferences; those live in DataStore). */
@Entity(tableName = "user_settings")
data class UserSettingsEntity(
    @PrimaryKey val settingKey: String,
    val longValue: Long?,
    val stringValue: String?,
)
