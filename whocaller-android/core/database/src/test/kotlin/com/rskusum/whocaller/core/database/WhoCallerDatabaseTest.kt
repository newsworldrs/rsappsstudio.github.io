package com.rskusum.whocaller.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rskusum.whocaller.core.database.entity.BlockedNumberEntity
import com.rskusum.whocaller.core.database.entity.CallerEntity
import com.rskusum.whocaller.core.database.entity.SearchHistoryEntity
import com.rskusum.whocaller.core.database.entity.SpamReportEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class WhoCallerDatabaseTest {

    private lateinit var db: WhoCallerDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, WhoCallerDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private fun caller(key: String, updatedAt: Long = 1, fromSpamList: Boolean = false, name: String? = null) = CallerEntity(
        phoneNumber = key, displayName = name, identityType = "UNKNOWN", category = null, spamScore = -1,
        serverConfidence = null, reportCount = 0, reportsLast24h = 0, reportsLast7d = 0, blockCount = 0,
        lastReportedAt = null, categoryVotes = "", verified = false, businessId = null, carrier = null,
        lineType = null, regionCode = null, fromSpamList = fromSpamList, updatedAt = updatedAt,
    )

    @Test
    fun emptyDatabase() = runTest {
        assertNull(db.callerDao().get("+10000000000"))
        assertEquals(0, db.callerDao().count())
        assertTrue(db.blockedNumberDao().getAll().isEmpty())
    }

    @Test
    fun callerUpsertKeepsOneRowPerNumber() = runTest {
        db.callerDao().upsert(caller("+919876543210", name = "A"))
        db.callerDao().upsert(caller("+919876543210", name = "B"))
        assertEquals(1, db.callerDao().count())
        assertEquals("B", db.callerDao().get("+919876543210")?.displayName)
    }

    @Test
    fun spamListReplacementKeepsExplicitLookups() = runTest {
        db.callerDao().upsert(caller("+1", name = "looked up"))
        db.callerDao().upsert(caller("+2", fromSpamList = true))
        db.callerDao().replaceSpamList(listOf(caller("+1", fromSpamList = true), caller("+3", fromSpamList = true)))
        assertEquals("looked up", db.callerDao().get("+1")?.displayName)
        assertNull(db.callerDao().get("+2"))
        assertTrue(db.callerDao().get("+3")!!.fromSpamList)
    }

    @Test
    fun staleCleanup() = runTest {
        db.callerDao().upsert(caller("+1", updatedAt = 10))
        db.callerDao().upsert(caller("+2", updatedAt = 100))
        assertEquals(1, db.callerDao().deleteOlderThan(50))
        assertNull(db.callerDao().get("+1"))
    }

    @Test
    fun blockAndUnblock() = runTest {
        val dao = db.blockedNumberDao()
        dao.upsert(BlockedNumberEntity("+919876543210", "+91 98765 43210", null, "SPAM", 1, "USER"))
        assertTrue(dao.isBlocked("+919876543210"))
        assertTrue(dao.observeIsBlocked("+919876543210").first())
        dao.delete("+919876543210")
        assertFalse(dao.isBlocked("+919876543210"))
    }

    @Test
    fun searchHistoryDeduplicatesByNumber() = runTest {
        val dao = db.searchHistoryDao()
        dao.record(SearchHistoryEntity(queryText = "9876543210", numberKey = "+919876543210", displayNumber = "x", searchedAt = 1))
        dao.record(SearchHistoryEntity(queryText = "+919876543210", numberKey = "+919876543210", displayNumber = "x", searchedAt = 2))
        val all = dao.getAll()
        assertEquals(1, all.size)
        assertEquals(2, all.single().searchedAt)
    }

    @Test
    fun duplicateClientReportIdIsRejected() = runTest {
        val dao = db.spamReportDao()
        dao.insert(SpamReportEntity(numberKey = "+1", reason = "SPAM", comment = null, createdAt = 1, syncState = "PENDING", clientReportId = "c1"))
        val duplicate = runCatching {
            dao.insert(SpamReportEntity(numberKey = "+1", reason = "SPAM", comment = null, createdAt = 2, syncState = "PENDING", clientReportId = "c1"))
        }
        assertTrue(duplicate.isFailure)
        assertEquals(1, dao.getAll().size)
    }

    @Test
    fun pendingReportsRespectAttempts() = runTest {
        val dao = db.spamReportDao()
        val id = dao.insert(SpamReportEntity(numberKey = "+1", reason = "SPAM", comment = null, createdAt = 1, syncState = "PENDING", clientReportId = "c1"))
        assertEquals(1, dao.pending(maxAttempts = 3, limit = 10).size)
        repeat(3) { dao.updateState(id, "FAILED", 1) }
        assertTrue(dao.pending(maxAttempts = 3, limit = 10).isEmpty())
        dao.updateState(id, "SYNCED", 0)
        assertEquals(0, dao.countSince(0) - 1)
    }

    @Test
    fun counterIncrements() = runTest {
        val dao = db.userSettingsDao()
        repeat(3) { dao.increment("stat.calls") }
        assertEquals(3L, dao.get("stat.calls")?.longValue)
    }
}
