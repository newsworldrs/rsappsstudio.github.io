package com.rskusum.whocaller.feature.postcall

import com.rskusum.whocaller.core.model.ReportCategory
import com.rskusum.whocaller.core.model.SpamCategory
import com.rskusum.whocaller.core.model.SpamReport
import com.rskusum.whocaller.core.model.SyncState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PostCallMemoryTest {

    @Test
    fun `asks again only after the quiet period`() {
        val now = 1_000_000L
        assertTrue(PostCallMemory.shouldAsk(0L, now))
        assertFalse(PostCallMemory.shouldAsk(now + PostCallMemory.ASK_COOLDOWN_MS, now))
        assertTrue(PostCallMemory.shouldAsk(now, now))
    }

    @Test
    fun `neutral categories are not complaints`() {
        val delivery = SpamReport(1, "k", ReportCategory.DELIVERY.reason, null, 0, SyncState.PENDING, listOf(ReportCategory.DELIVERY))
        assertEquals(SpamCategory.BUSINESS, delivery.primaryCategory)
        val mixed = SpamReport(2, "k", ReportCategory.TELEMARKETING.reason, null, 0, SyncState.PENDING, listOf(ReportCategory.BUSINESS_SERVICE, ReportCategory.TELEMARKETING))
        assertEquals(SpamCategory.TELEMARKETING, mixed.primaryCategory)
    }

    @Test
    fun `at most two categories are kept`() {
        assertEquals(
            listOf(ReportCategory.SPAM, ReportCategory.ROBOCALL),
            ReportCategory.parseList("SPAM,ROBOCALL,DELIVERY"),
        )
    }
}
