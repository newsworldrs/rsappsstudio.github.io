package com.rskusum.whocaller.core.domain

import com.rskusum.whocaller.core.common.phone.PhoneNumberNormalizer
import com.rskusum.whocaller.core.common.result.AppError
import com.rskusum.whocaller.core.common.spam.RuleBasedSpamScoreEngine
import com.rskusum.whocaller.core.domain.caller.CallerIdentificationManager
import com.rskusum.whocaller.core.domain.repository.StatKey
import com.rskusum.whocaller.core.model.AppSettings
import com.rskusum.whocaller.core.model.BlockSource
import com.rskusum.whocaller.core.model.BlockedNumber
import com.rskusum.whocaller.core.model.CallDecision
import com.rskusum.whocaller.core.model.CallerInfo
import com.rskusum.whocaller.core.model.CallerLabel
import com.rskusum.whocaller.core.model.DecisionReason
import com.rskusum.whocaller.core.model.IdentityType
import com.rskusum.whocaller.core.model.InfoSource
import com.rskusum.whocaller.core.model.SpamCategory
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallerIdentificationManagerTest {

    private val now = 1_750_000_000_000L
    private val settings = FakeSettingsRepository()
    private val contacts = FakeContactsRepository()
    private val blocks = FakeBlockRepository()
    private val callers = FakeCallerRepository()
    private val spam = FakeSpamRepository()
    private val identified = FakeIdentifiedCallRepository()
    private val stats = FakeStatsRepository()
    private val analytics = RecordingAnalytics()

    private val manager = CallerIdentificationManager(
        normalizer = PhoneNumberNormalizer(),
        countryRepository = FakeCountryRepository("IN"),
        settingsRepository = settings,
        contactsRepository = contacts,
        blockRepository = blocks,
        callerRepository = callers,
        spamRepository = spam,
        identifiedCallRepository = identified,
        statsRepository = stats,
        engine = RuleBasedSpamScoreEngine { now },
        analytics = analytics,
        clock = { now },
    )

    private val key = "+919876543210"
    private val spammer = CallerInfo(
        numberKey = key, category = SpamCategory.TELEMARKETING, reportCount = 84, reportsLast24h = 6, reportsLast7d = 30,
        categoryVotes = mapOf(SpamCategory.TELEMARKETING to 84), source = InfoSource.SERVER, lastReportedAt = now,
    )

    @Test
    fun `unknown number with no data is allowed and labelled unknown`() = runTest {
        val r = manager.identify("09876543210")
        assertEquals(CallDecision.ALLOW, r.decision)
        assertEquals(CallerLabel.UNKNOWN, r.label)
        assertEquals(key, r.number?.key)
        assertEquals(0, r.spamScore.score)
    }

    @Test
    fun `contacts are allowed without a network lookup`() = runTest {
        contacts.names["+919876543210"] = "Asha"
        val r = manager.identify("+919876543210")
        assertEquals(CallerLabel.CONTACT, r.label)
        assertEquals("Asha", r.displayName)
        assertEquals(0, callers.lookups)
    }

    @Test
    fun `reported number produces a warning, not a block, by default`() = runTest {
        callers.remote[key] = spammer
        val r = manager.identify("+91 98765 43210")
        assertEquals(CallDecision.WARN, r.decision)
        assertEquals(CallerLabel.TELEMARKETING, r.label)
        assertEquals(DecisionReason.SUSPECTED_SPAM, r.reason)
    }

    @Test
    fun `blocked category rejects high risk calls`() = runTest {
        settings.update { it.copy(blockedCategories = setOf(SpamCategory.TELEMARKETING)) }
        callers.remote[key] = spammer
        val r = manager.identify(key)
        assertEquals(CallDecision.BLOCK, r.decision)
        assertEquals(DecisionReason.BLOCKED_CATEGORY, r.reason)
        assertEquals(1, blocks.blockedCalls.size)
        assertEquals(1L, stats.counts[StatKey.SPAM_BLOCKED])
    }

    @Test
    fun `user block list always blocks`() = runTest {
        blocks.block(BlockedNumber(key, key, null, null, now, BlockSource.USER))
        val r = manager.identify("919876543210")
        assertEquals(CallDecision.BLOCK, r.decision)
        assertEquals(DecisionReason.USER_BLOCK_LIST, r.reason)
    }

    @Test
    fun `hidden numbers are blocked only when enabled`() = runTest {
        assertEquals(CallDecision.ALLOW, manager.identify(null).decision)
        settings.update { it.copy(blockHiddenNumbers = true) }
        val r = manager.identify("-2")
        assertEquals(CallDecision.BLOCK, r.decision)
        assertEquals(CallerLabel.HIDDEN, r.label)
        assertTrue(r.isHidden)
    }

    @Test
    fun `block unknown callers requires contacts access`() = runTest {
        settings.update { it.copy(blockUnknownCallers = true) }
        contacts.permission = false
        assertEquals(CallDecision.ALLOW, manager.identify(key).decision)
        contacts.permission = true
        assertEquals(CallDecision.BLOCK, manager.identify(key).decision)
    }

    @Test
    fun `backend failure falls back to cache without crashing`() = runTest {
        callers.remoteError = AppError.NETWORK_UNAVAILABLE
        callers.cache[key] = spammer
        val r = manager.identify(key)
        assertEquals(CallDecision.WARN, r.decision)
    }

    @Test
    fun `slow backend is abandoned after the budget`() = runTest {
        callers.remoteDelayMs = 10_000
        callers.remote[key] = spammer
        val r = manager.identify(key, networkBudgetMs = 100)
        assertEquals(CallDecision.ALLOW, r.decision)
        assertNull(r.info)
    }

    @Test
    fun `verified business is labelled verified`() = runTest {
        callers.remote[key] = CallerInfo(
            numberKey = key, displayName = "ABC Internet Services", identityType = IdentityType.BUSINESS,
            category = SpamCategory.BUSINESS, verified = true, source = InfoSource.SERVER,
        )
        val r = manager.identify(key)
        assertEquals(CallerLabel.VERIFIED_BUSINESS, r.label)
        assertEquals("ABC Internet Services", r.displayName)
        assertEquals(CallDecision.ALLOW, r.decision)
    }

    @Test
    fun `protection disabled skips lookups`() = runTest {
        settings.update { it.copy(callerIdEnabled = false, spamProtectionEnabled = false) }
        val r = manager.identify(key)
        assertEquals(DecisionReason.PROTECTION_DISABLED, r.reason)
        assertEquals(0, callers.lookups)
    }

    @Test
    fun `auto block very high risk is opt in`() = runTest {
        val s = RuleBasedSpamScoreEngine { now }.score(
            com.rskusum.whocaller.core.common.spam.SpamSignals(serverScore = 95, serverConfidence = 1f),
        )
        assertEquals(CallDecision.WARN, manager.decide(AppSettings(), s, userBlocked = false, canCheckContacts = true).first)
        assertEquals(
            CallDecision.BLOCK,
            manager.decide(AppSettings(autoBlockHighRisk = true), s, userBlocked = false, canCheckContacts = true).first,
        )
    }
}
