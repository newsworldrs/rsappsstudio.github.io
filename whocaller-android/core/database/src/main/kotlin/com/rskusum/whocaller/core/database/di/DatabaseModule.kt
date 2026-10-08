package com.rskusum.whocaller.core.database.di

import android.content.Context
import androidx.room.Room
import com.rskusum.whocaller.core.database.Migrations
import com.rskusum.whocaller.core.database.WhoCallerDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): WhoCallerDatabase =
        Room.databaseBuilder(context, WhoCallerDatabase::class.java, WhoCallerDatabase.NAME)
            .addMigrations(*Migrations.ALL)
            .build()

    @Provides fun callerDao(db: WhoCallerDatabase) = db.callerDao()
    @Provides fun spamReportDao(db: WhoCallerDatabase) = db.spamReportDao()
    @Provides fun callHistoryDao(db: WhoCallerDatabase) = db.callHistoryDao()
    @Provides fun blockedNumberDao(db: WhoCallerDatabase) = db.blockedNumberDao()
    @Provides fun blockedCallDao(db: WhoCallerDatabase) = db.blockedCallDao()
    @Provides fun searchHistoryDao(db: WhoCallerDatabase) = db.searchHistoryDao()
    @Provides fun contactCacheDao(db: WhoCallerDatabase) = db.contactCacheDao()
    @Provides fun businessDao(db: WhoCallerDatabase) = db.businessDao()
    @Provides fun userSettingsDao(db: WhoCallerDatabase) = db.userSettingsDao()
}
