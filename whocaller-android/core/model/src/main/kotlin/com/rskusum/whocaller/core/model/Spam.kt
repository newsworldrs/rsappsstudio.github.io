package com.rskusum.whocaller.core.model

/** Classification of a number. Wire names are stable and shared with the backend. */
enum class SpamCategory {
    SAFE,
    UNKNOWN,
    TELEMARKETING,
    SPAM,
    SCAM,
    FRAUD,
    ROBOCALL,
    DEBT_COLLECTION,
    POLITICAL,
    CHARITY,
    BUSINESS,
    ;

    /** Categories that describe unwanted calls (as opposed to neutral descriptions like BUSINESS). */
    val isUnwanted: Boolean
        get() = this in UNWANTED

    /** Categories that must only be shown with strong evidence. */
    val isSevere: Boolean
        get() = this == SCAM || this == FRAUD

    companion object {
        private val UNWANTED = setOf(TELEMARKETING, SPAM, SCAM, FRAUD, ROBOCALL)

        fun fromWire(value: String?): SpamCategory =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: UNKNOWN
    }
}

/** Risk bands for a 0–100 score. */
enum class RiskLevel(val range: IntRange) {
    LOW(0..19),
    MODERATE(20..49),
    HIGH(50..74),
    VERY_HIGH(75..100),
    ;

    companion object {
        fun fromScore(score: Int): RiskLevel {
            val clamped = score.coerceIn(0, 100)
            return entries.first { clamped in it.range }
        }
    }
}

/**
 * Output of a spam scoring engine.
 *
 * @property score 0–100.
 * @property confidence 0.0–1.0; how much evidence backs the score. 0 means "no data".
 * @property evidenceCount number of independent signals that contributed (reports, server verdict…).
 */
data class SpamScore(
    val score: Int,
    val category: SpamCategory,
    val confidence: Float,
    val evidenceCount: Int = 0,
) {
    val riskLevel: RiskLevel get() = RiskLevel.fromScore(score)
    val hasEvidence: Boolean get() = evidenceCount > 0

    companion object {
        val NONE = SpamScore(score = 0, category = SpamCategory.UNKNOWN, confidence = 0f, evidenceCount = 0)
        val CONTACT = SpamScore(score = 0, category = SpamCategory.SAFE, confidence = 1f, evidenceCount = 1)
    }
}

/** Reasons a user can pick when reporting a number. */
enum class ReportReason(val category: SpamCategory) {
    SPAM(SpamCategory.SPAM),
    SCAM(SpamCategory.SCAM),
    TELEMARKETING(SpamCategory.TELEMARKETING),
    FRAUD(SpamCategory.FRAUD),
    ROBOCALL(SpamCategory.ROBOCALL),
    HARASSMENT(SpamCategory.SPAM),
    FAKE_BANK_CALL(SpamCategory.FRAUD),
    FAKE_DELIVERY_CALL(SpamCategory.SCAM),
    OTHER(SpamCategory.SPAM),
    ;

    companion object {
        fun fromWire(value: String?): ReportReason =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: OTHER
    }
}

enum class SyncState { PENDING, SYNCED, FAILED }

data class SpamReport(
    val id: Long,
    val numberKey: String,
    val reason: ReportReason,
    val comment: String?,
    val createdAt: Long,
    val syncState: SyncState,
    /** Categories picked on the "Know this caller?" screen (1–2). Empty for older single-reason reports. */
    val categories: List<ReportCategory> = emptyList(),
) {
    /** What this report says about the number on this device. Neutral picks (business, delivery) aren't complaints. */
    val primaryCategory: SpamCategory
        get() = categories.firstOrNull { !it.neutral }?.category
            ?: categories.firstOrNull()?.category
            ?: reason.category
}

/** Categories of the post-call "Know this caller?" screen. Names are the wire/Firestore values. */
enum class ReportCategory(val category: SpamCategory, val reason: ReportReason, val neutral: Boolean = false) {
    SPAM(SpamCategory.SPAM, ReportReason.SPAM),
    TELEMARKETING(SpamCategory.TELEMARKETING, ReportReason.TELEMARKETING),
    FINANCIAL_SCAM(SpamCategory.SCAM, ReportReason.SCAM),
    FRAUD_FAKE_OFFER(SpamCategory.FRAUD, ReportReason.FRAUD),
    IMPERSONATION(SpamCategory.SCAM, ReportReason.SCAM),
    BANKING_SCAM(SpamCategory.FRAUD, ReportReason.FAKE_BANK_CALL),
    BUSINESS_SERVICE(SpamCategory.BUSINESS, ReportReason.OTHER, neutral = true),
    DELIVERY(SpamCategory.BUSINESS, ReportReason.OTHER, neutral = true),
    ROBOCALL(SpamCategory.ROBOCALL, ReportReason.ROBOCALL),
    HARASSMENT(SpamCategory.SPAM, ReportReason.HARASSMENT),
    OTHER(SpamCategory.SPAM, ReportReason.OTHER),
    ;

    companion object {
        /** Users may pick at most this many categories per report. */
        const val MAX_PER_REPORT = 2

        fun fromWire(value: String?): ReportCategory? = entries.firstOrNull { it.name.equals(value, ignoreCase = true) }

        fun parseList(value: String?): List<ReportCategory> =
            value.orEmpty().split(',').mapNotNull { fromWire(it.trim()) }.distinct().take(MAX_PER_REPORT)
    }
}

/** How a report was made: from the post-call screen after an unknown call, or from the Report screen. */
enum class ReportCallType { INCOMING_UNKNOWN, MANUAL }
