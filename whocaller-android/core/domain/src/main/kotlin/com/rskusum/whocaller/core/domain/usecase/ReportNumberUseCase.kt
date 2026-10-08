package com.rskusum.whocaller.core.domain.usecase

import com.rskusum.whocaller.core.common.Clock
import com.rskusum.whocaller.core.common.TimeUnits
import com.rskusum.whocaller.core.common.analytics.AnalyticsEvent
import com.rskusum.whocaller.core.common.analytics.AnalyticsTracker
import com.rskusum.whocaller.core.common.result.AppError
import com.rskusum.whocaller.core.common.result.AppResult
import com.rskusum.whocaller.core.domain.repository.SpamRepository
import com.rskusum.whocaller.core.domain.repository.StatKey
import com.rskusum.whocaller.core.domain.repository.StatsRepository
import com.rskusum.whocaller.core.model.PhoneNumber
import com.rskusum.whocaller.core.model.ReportReason
import com.rskusum.whocaller.core.model.SpamReport
import javax.inject.Inject

/**
 * Validates and submits a spam report.
 *
 * Client-side limits are a courtesy to keep honest users from double-submitting; the backend
 * enforces the authoritative rate limits and abuse checks (see docs/API.md).
 */
class ReportNumberUseCase @Inject constructor(
    private val spamRepository: SpamRepository,
    private val statsRepository: StatsRepository,
    private val analytics: AnalyticsTracker,
    private val clock: Clock,
) {
    suspend operator fun invoke(number: PhoneNumber, reason: ReportReason, comment: String?): AppResult<SpamReport> {
        if (number.key.startsWith(PhoneNumber.RAW_PREFIX) && !number.isValid && number.raw.count { it.isDigit() } < 5) {
            return AppResult.Failure(AppError.INVALID_NUMBER)
        }
        val now = clock.now()
        val previous = spamRepository.latestReportFor(number.key)
        if (previous != null && now - previous.createdAt < DUPLICATE_WINDOW_MS) {
            return AppResult.Failure(AppError.DUPLICATE)
        }
        if (spamRepository.countReportsSince(now - TimeUnits.DAY) >= MAX_REPORTS_PER_DAY) {
            return AppResult.Failure(AppError.RATE_LIMITED)
        }
        val result = spamRepository.submitReport(number.key, reason, sanitizeComment(comment))
        if (result is AppResult.Success) {
            statsRepository.increment(StatKey.NUMBERS_REPORTED)
            analytics.track(AnalyticsEvent.NumberReported(reason.name))
        }
        return result
    }

    companion object {
        const val MAX_COMMENT_LENGTH = 500
        const val MAX_REPORTS_PER_DAY = 30
        const val DUPLICATE_WINDOW_MS = TimeUnits.DAY

        /** Trims, strips control characters and caps length. Returns null for blank comments. */
        fun sanitizeComment(comment: String?): String? {
            val cleaned = comment
                ?.filter { !it.isISOControl() || it == '\n' }
                ?.replace(Regex("\\n{3,}"), "\n\n")
                ?.trim()
                ?.take(MAX_COMMENT_LENGTH)
            return cleaned?.takeIf { it.isNotEmpty() }
        }
    }
}
