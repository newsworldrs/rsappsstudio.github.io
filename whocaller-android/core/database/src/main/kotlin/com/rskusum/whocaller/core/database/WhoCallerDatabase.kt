package com.rskusum.whocaller.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.rskusum.whocaller.core.database.dao.BlockedCallDao
import com.rskusum.whocaller.core.database.dao.BlockedNumberDao
import com.rskusum.whocaller.core.database.dao.BusinessDao
import com.rskusum.whocaller.core.database.dao.CallHistoryDao
import com.rskusum.whocaller.core.database.dao.CallerDao
import com.rskusum.whocaller.core.database.dao.ContactCacheDao
import com.rskusum.whocaller.core.database.dao.SearchHistoryDao
import com.rskusum.whocaller.core.database.dao.SpamReportDao
import com.rskusum.whocaller.core.database.dao.UserSettingsDao
import com.rskusum.whocaller.core.database.entity.BlockedCallEntity
import com.rskusum.whocaller.core.database.entity.BlockedNumberEntity
import com.rskusum.whocaller.core.database.entity.BusinessEntity
import com.rskusum.whocaller.core.database.entity.CallHistoryEntity
import com.rskusum.whocaller.core.database.entity.CallerEntity
import com.rskusum.whocaller.core.database.entity.ContactCacheEntity
import com.rskusum.whocaller.core.database.entity.SearchHistoryEntity
import com.rskusum.whocaller.core.database.entity.SpamReportEntity
import com.rskusum.whocaller.core.database.entity.UserSettingsEntity

/**
 * Local database. Schema changes MUST bump [version] and add a migration in [Migrations]
 * (or an `@AutoMigration`); exported schemas live in `core/database/schemas`. See docs/DATABASE.md.
 */
@Database(
    entities = [
        CallerEntity::class,
        SpamReportEntity::class,
        CallHistoryEntity::class,
        BlockedNumberEntity::class,
        BlockedCallEntity::class,
        SearchHistoryEntity::class,
        ContactCacheEntity::class,
        BusinessEntity::class,
        UserSettingsEntity::class,
    ],
    version = WhoCallerDatabase.VERSION,
    exportSchema = true,
)
abstract class WhoCallerDatabase : RoomDatabase() {
    abstract fun callerDao(): CallerDao
    abstract fun spamReportDao(): SpamReportDao
    abstract fun callHistoryDao(): CallHistoryDao
    abstract fun blockedNumberDao(): BlockedNumberDao
    abstract fun blockedCallDao(): BlockedCallDao
    abstract fun searchHistoryDao(): SearchHistoryDao
    abstract fun contactCacheDao(): ContactCacheDao
    abstract fun businessDao(): BusinessDao
    abstract fun userSettingsDao(): UserSettingsDao

    companion object {
        const val VERSION = 2
        const val NAME = "whocaller.db"
    }
}

/** Manual migrations, applied in order. */
object Migrations {
    /** v2: post-call reports carry up to two categories, the call type and whether it was answered. */
    val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE spam_reports ADD COLUMN categories TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE spam_reports ADD COLUMN callType TEXT")
            db.execSQL("ALTER TABLE spam_reports ADD COLUMN callAnswered INTEGER")
        }
    }

    val ALL: Array<androidx.room.migration.Migration> = arrayOf(MIGRATION_1_2)
}
