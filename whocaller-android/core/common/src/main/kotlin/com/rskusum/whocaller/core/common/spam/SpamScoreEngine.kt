package com.rskusum.whocaller.core.common.spam

import com.rskusum.whocaller.core.common.Clock
import com.rskusum.whocaller.core.common.TimeUnits
import com.rskusum.whocaller.core.model.CallerInfo
import com.rskusum.whocaller.core.model.SpamCategory
import com.rskusum.whocaller.core.model.SpamScore
import javax.inject.Inject
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Every signal the engine may use. All values come from real data (community reports stored on the
 * backend, the user's own actions on this device, server verdicts). Missing data stays missing.
 */
data class SpamSignals(
    val reportCount: Int = 0,
    val reportsLast24h: Int = 0,
    val reportsLast7d: Int = 0,
    val blockCount: Int = 0,
    val lastReportedAt: Long? = null,
    val categoryVotes: Map<SpamCategory, Int> = emptyMap(),
    val serverScore: Int? = null,
    val serverConfidence: Float? = null,
    val serverCategory: SpamCategory? = null,
    val verifiedBusiness: Boolean = false,
    val isContact: Boolean = false,
    /** The user blocked this number themselves. */
    val userBlocked: Boolean = false,
    /** The user reported this number themselves (category of their latest report). */
    val userReportedCategory: SpamCategory? = null,
) {
    companion object {
        fun from(info: CallerInfo?, isContact: Boolean, userBlocked: Boolean, userReported: SpamCategory?) =
            SpamSignals(
                reportCount = info?.reportCount ?: 0,
                reportsLast24h = info?.reportsLast24h ?: 0,
                reportsLast7d = info?.reportsLast7d ?: 0,
                blockCount = info?.blockCount ?: 0,
                lastReportedAt = info?.lastReportedAt,
                categoryVotes = info?.categoryVotes.orEmpty(),
                serverScore = info?.serverScore,
                serverConfidence = info?.serverConfidence,
                serverCategory = info?.category?.takeIf { it != SpamCategory.UNKNOWN },
                verifiedBusiness = info?.verified == true,
                isContact = isContact,
                userBlocked = userBlocked,
                userReportedCategory = userReported,
            )
    }
}

/**
 * Scores a number from 0 (no concern) to 100 (very likely unwanted).
 *
 * The interface lets the local rules be replaced by a server-side or on-device ML model later without
 * touching callers.
 */
interface SpamScoreEngine {
    fun score(signals: SpamSignals): SpamScore
}

/**
 * Transparent, rule-based scoring used on the device (and offline).
 *
 * Design rules:
 *  - No evidence ⇒ score 0 and category UNKNOWN. An unknown number is never treated as spam.
 *  - Saved contacts are SAFE.
 *  - Verified businesses are capped low unless there is an overwhelming volume of recent reports.
 *  - SCAM/FRAUD are only shown when the score is high AND enough independent reports agree;
 *    otherwise the category is downgraded to SPAM.
 */
class RuleBasedSpamScoreEngine @Inject constructor(
    private val clock: Clock,
) : SpamScoreEngine {

    override fun score(signals: SpamSignals): SpamScore {
        if (signals.isContact && !signals.userBlocked && signals.userReportedCategory == null) {
            return SpamScore.CONTACT
        }

        val evidence = countEvidence(signals)

        // Community reports: logarithmic so a handful of reports can't dominate, capped at 55.
        val reportPoints = min(55.0, 13.0 * log2(1 + signals.reportCount))
        // Recency: reports in the last day/week indicate an active campaign.
        val recentPoints = min(15.0, 5.0 * log2(1 + signals.reportsLast24h)) +
            min(10.0, 3.0 * log2(1 + signals.reportsLast7d))
        // People blocking the number is a weaker signal than reports.
        val blockPoints = min(10.0, 3.0 * log2(1 + signals.blockCount))
        val stalePenalty = stalenessPenalty(signals.lastReportedAt)

        var local = (reportPoints + recentPoints + blockPoints - stalePenalty).coerceIn(0.0, 100.0)

        val server = signals.serverScore?.coerceIn(0, 100)
        var combined = if (server != null) {
            val w = (signals.serverConfidence ?: 0.7f).coerceIn(0f, 1f).toDouble()
            w * server + (1 - w) * local
        } else {
            local
        }

        if (signals.verifiedBusiness) {
            // Only a large, recent surge of reports can lift a verified business above "moderate".
            val cap = if (signals.reportsLast7d >= VERIFIED_OVERRIDE_REPORTS) 60.0 else 15.0
            combined = min(combined, cap)
            local = min(local, cap)
        }

        // The user's own judgement always wins on their device.
        if (signals.userBlocked || signals.userReportedCategory != null) {
            combined = max(combined, PERSONAL_FLOOR.toDouble())
        }

        val score = combined.roundToInt().coerceIn(0, 100)
        val category = pickCategory(signals, score)
        return SpamScore(
            score = score,
            category = category,
            confidence = confidence(signals, evidence),
            evidenceCount = evidence,
        )
    }

    private fun pickCategory(s: SpamSignals, score: Int): SpamCategory {
        s.userReportedCategory?.let { return guardSevere(it, s, score) }
        if (s.userBlocked && s.reportCount == 0 && s.serverCategory == null) return SpamCategory.SPAM
        if (s.verifiedBusiness && score < 20) return SpamCategory.BUSINESS

        val voted = s.categoryVotes.filterValues { it > 0 }.maxByOrNull { it.value }?.key
        val candidate = when {
            s.serverCategory != null && s.serverCategory != SpamCategory.UNKNOWN -> s.serverCategory
            voted != null -> voted
            else -> null
        }
        return when {
            candidate == null -> if (score >= 50) SpamCategory.SPAM else SpamCategory.UNKNOWN
            candidate.isUnwanted && score < 20 -> SpamCategory.UNKNOWN
            else -> guardSevere(candidate, s, score)
        }
    }

    /** SCAM/FRAUD need a high score and several agreeing reports, or the user's own report. */
    private fun guardSevere(category: SpamCategory, s: SpamSignals, score: Int): SpamCategory {
        if (!category.isSevere) return category
        if (s.userReportedCategory == category) return category
        val agreeing = (s.categoryVotes[SpamCategory.SCAM] ?: 0) + (s.categoryVotes[SpamCategory.FRAUD] ?: 0)
        val serverSaysSevere = s.serverCategory?.isSevere == true && (s.serverConfidence ?: 0f) >= 0.8f
        return if (score >= 50 && (agreeing >= MIN_SEVERE_REPORTS || serverSaysSevere)) category else SpamCategory.SPAM
    }

    private fun countEvidence(s: SpamSignals): Int {
        var n = s.reportCount + s.blockCount
        if (s.serverScore != null) n++
        if (s.verifiedBusiness) n++
        if (s.userBlocked) n++
        if (s.userReportedCategory != null) n++
        return n
    }

    private fun confidence(s: SpamSignals, evidence: Int): Float {
        if (evidence == 0) return 0f
        if (s.userBlocked || s.userReportedCategory != null) return 1f
        val fromReports = min(1.0, log2(1 + s.reportCount + s.blockCount) / log2(1 + SATURATION_REPORTS))
        val fromServer = s.serverConfidence?.toDouble() ?: 0.0
        val verified = if (s.verifiedBusiness) 0.9 else 0.0
        return max(max(fromReports, fromServer), verified).toFloat().coerceIn(0f, 1f)
    }

    private fun stalenessPenalty(lastReportedAt: Long?): Double {
        lastReportedAt ?: return 0.0
        val ageDays = (clock.now() - lastReportedAt).coerceAtLeast(0) / TimeUnits.DAY
        return when {
            ageDays > 365 -> 20.0
            ageDays > 180 -> 10.0
            ageDays > 90 -> 5.0
            else -> 0.0
        }
    }

    private fun log2(x: Number): Double = ln(x.toDouble()) / ln(2.0)

    companion object {
        const val PERSONAL_FLOOR = 80
        const val MIN_SEVERE_REPORTS = 3
        const val VERIFIED_OVERRIDE_REPORTS = 25
        private const val SATURATION_REPORTS = 50
    }
}
