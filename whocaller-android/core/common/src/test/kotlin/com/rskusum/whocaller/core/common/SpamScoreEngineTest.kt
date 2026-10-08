package com.rskusum.whocaller.core.common

import com.rskusum.whocaller.core.common.spam.RuleBasedSpamScoreEngine
import com.rskusum.whocaller.core.common.spam.SpamSignals
import com.rskusum.whocaller.core.model.RiskLevel
import com.rskusum.whocaller.core.model.SpamCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpamScoreEngineTest {

    private val now = 1_750_000_000_000L
    private val engine = RuleBasedSpamScoreEngine { now }

    @Test
    fun `no evidence means zero and unknown - unknown numbers are never spam`() {
        val s = engine.score(SpamSignals())
        assertEquals(0, s.score)
        assertEquals(SpamCategory.UNKNOWN, s.category)
        assertEquals(0f, s.confidence)
        assertEquals(RiskLevel.LOW, s.riskLevel)
    }

    @Test
    fun `contacts are safe`() {
        val s = engine.score(SpamSignals(isContact = true, reportCount = 500))
        assertEquals(SpamCategory.SAFE, s.category)
        assertEquals(0, s.score)
    }

    @Test
    fun `a single report stays low risk`() {
        val s = engine.score(SpamSignals(reportCount = 1, categoryVotes = mapOf(SpamCategory.SPAM to 1)))
        assertTrue("score=${s.score}", s.riskLevel == RiskLevel.LOW)
        assertEquals(SpamCategory.UNKNOWN, s.category)
    }

    @Test
    fun `many recent reports are high risk with majority category`() {
        val s = engine.score(
            SpamSignals(
                reportCount = 120, reportsLast24h = 10, reportsLast7d = 40, blockCount = 30,
                lastReportedAt = now - TimeUnits.HOUR,
                categoryVotes = mapOf(SpamCategory.TELEMARKETING to 100, SpamCategory.SPAM to 20),
            ),
        )
        assertTrue("score=${s.score}", s.riskLevel >= RiskLevel.HIGH)
        assertEquals(SpamCategory.TELEMARKETING, s.category)
        assertTrue(s.confidence > 0.8f)
    }

    @Test
    fun `scam needs several agreeing reports`() {
        val weak = engine.score(
            SpamSignals(reportCount = 60, reportsLast24h = 8, reportsLast7d = 20, categoryVotes = mapOf(SpamCategory.SCAM to 2, SpamCategory.SPAM to 1)),
        )
        assertEquals(SpamCategory.SPAM, weak.category)

        val strong = engine.score(
            SpamSignals(reportCount = 60, reportsLast24h = 8, reportsLast7d = 20, categoryVotes = mapOf(SpamCategory.SCAM to 40)),
        )
        assertEquals(SpamCategory.SCAM, strong.category)
    }

    @Test
    fun `verified business is capped low`() {
        val s = engine.score(SpamSignals(verifiedBusiness = true, reportCount = 30, reportsLast7d = 2))
        assertTrue("score=${s.score}", s.score <= 15)
        assertEquals(SpamCategory.BUSINESS, s.category)
    }

    @Test
    fun `server verdict is weighted by its confidence`() {
        val s = engine.score(SpamSignals(serverScore = 90, serverConfidence = 1f, serverCategory = SpamCategory.ROBOCALL))
        assertEquals(90, s.score)
        assertEquals(SpamCategory.ROBOCALL, s.category)
    }

    @Test
    fun `user's own block or report wins`() {
        val blocked = engine.score(SpamSignals(userBlocked = true))
        assertTrue(blocked.score >= RuleBasedSpamScoreEngine.PERSONAL_FLOOR)
        assertEquals(SpamCategory.SPAM, blocked.category)

        val reported = engine.score(SpamSignals(userReportedCategory = SpamCategory.FRAUD))
        assertEquals(SpamCategory.FRAUD, reported.category)
        assertEquals(1f, reported.confidence)
    }

    @Test
    fun `old reports decay`() {
        val fresh = engine.score(SpamSignals(reportCount = 20, lastReportedAt = now - TimeUnits.DAY))
        val stale = engine.score(SpamSignals(reportCount = 20, lastReportedAt = now - 400 * TimeUnits.DAY))
        assertTrue(stale.score < fresh.score)
    }

    @Test
    fun `score is always within bounds`() {
        val s = engine.score(
            SpamSignals(reportCount = Int.MAX_VALUE / 2, reportsLast24h = 100000, reportsLast7d = 100000, blockCount = 100000, serverScore = 500),
        )
        assertTrue(s.score in 0..100)
    }

    @Test
    fun `risk bands match the specification`() {
        assertEquals(RiskLevel.LOW, RiskLevel.fromScore(0))
        assertEquals(RiskLevel.LOW, RiskLevel.fromScore(19))
        assertEquals(RiskLevel.MODERATE, RiskLevel.fromScore(20))
        assertEquals(RiskLevel.MODERATE, RiskLevel.fromScore(49))
        assertEquals(RiskLevel.HIGH, RiskLevel.fromScore(50))
        assertEquals(RiskLevel.HIGH, RiskLevel.fromScore(74))
        assertEquals(RiskLevel.VERY_HIGH, RiskLevel.fromScore(75))
        assertEquals(RiskLevel.VERY_HIGH, RiskLevel.fromScore(100))
    }
}
