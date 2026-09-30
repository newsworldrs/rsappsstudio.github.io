package com.rskusum.whocaller

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rskusum.whocaller.core.database.WhoCallerDatabase
import com.rskusum.whocaller.core.database.entity.BlockedNumberEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Runs on a device/emulator: `./gradlew connectedDebugAndroidTest`. */
@RunWith(AndroidJUnit4::class)
class DatabaseInstrumentedTest {
    @Test
    fun blockListRoundTripOnRealSqlite() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), WhoCallerDatabase::class.java).build()
        db.blockedNumberDao().upsert(BlockedNumberEntity("+919876543210", "+91 98765 43210", null, null, 1L, "USER"))
        assertTrue(db.blockedNumberDao().isBlocked("+919876543210"))
        db.close()
    }
}
