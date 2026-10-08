package com.rskusum.whocaller.core.domain

import com.rskusum.whocaller.core.common.TimeUnits
import com.rskusum.whocaller.core.common.phone.NormalizationResult
import com.rskusum.whocaller.core.common.phone.PhoneNumberNormalizer
import com.rskusum.whocaller.core.common.result.AppError
import com.rskusum.whocaller.core.common.result.AppResult
import com.rskusum.whocaller.core.domain.usecase.ReportNumberUseCase
import com.rskusum.whocaller.core.model.ReportReason
import com.rskusum.whocaller.core.testing.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportNumberUseCaseTest {
    private var now = 1_750_000_000_000L
    private val spam = FakeSpamRepository()
    private val stats = FakeStatsRepository()
    private val useCase = ReportNumberUseCase(spam, stats, RecordingAnalytics()) { now }
    private val normalizer = PhoneNumberNormalizer()
    private fun number(s: String) = (normalizer.normalize(s, "IN") as NormalizationResult.Parsed).number

    @Test
    fun `report is stored and sanitized`() = runTest {
        spam.now = now
        val r = useCase(number("9876543210"), ReportReason.FAKE_BANK_CALL, "  asked for OTP\u0000  ")
        assertTrue(r.isSuccess)
        assertEquals("asked for OTP", spam.reports.single().comment)
    }

    @Test
    fun `duplicate report within a day is rejected`() = runTest {
        spam.now = now
        useCase(number("9876543210"), ReportReason.SPAM, null)
        val second = useCase(number("+919876543210"), ReportReason.SCAM, null)
        assertEquals(AppError.DUPLICATE, (second as AppResult.Failure).error)

        now += TimeUnits.DAY + 1
        spam.now = now
        assertTrue(useCase(number("09876543210"), ReportReason.SCAM, null).isSuccess)
    }

    @Test
    fun `daily limit is enforced`() = runTest {
        spam.now = now
        repeat(ReportNumberUseCase.MAX_REPORTS_PER_DAY) { i ->
            assertTrue(useCase(number("98765432%02d".format(i)), ReportReason.SPAM, null).isSuccess)
        }
        val r = useCase(number("9123456789"), ReportReason.SPAM, null)
        assertEquals(AppError.RATE_LIMITED, (r as AppResult.Failure).error)
    }

    @Test
    fun `blank comment becomes null and long comment is capped`() {
        assertNull(ReportNumberUseCase.sanitizeComment("   "))
        assertEquals(ReportNumberUseCase.MAX_COMMENT_LENGTH, ReportNumberUseCase.sanitizeComment("a".repeat(2000))!!.length)
    }
}
