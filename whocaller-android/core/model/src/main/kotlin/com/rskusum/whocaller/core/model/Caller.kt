package com.rskusum.whocaller.core.model

enum class IdentityType { PERSON, BUSINESS, UNKNOWN }

/** Where a piece of caller information came from. Displayed so users can judge how much to trust it. */
enum class InfoSource { CONTACTS, LOCAL_CACHE, SERVER, USER_BLOCK_LIST, NONE }

/**
 * Information known about a number. Every field is optional because the honest answer is often
 * "we don't know". Nothing here is ever synthesized on the device.
 */
data class CallerInfo(
    val numberKey: String,
    val displayName: String? = null,
    val identityType: IdentityType = IdentityType.UNKNOWN,
    val category: SpamCategory = SpamCategory.UNKNOWN,
    /** Server-side score (0–100) if the backend supplied one. */
    val serverScore: Int? = null,
    val serverConfidence: Float? = null,
    val reportCount: Int = 0,
    val reportsLast24h: Int = 0,
    val reportsLast7d: Int = 0,
    val blockCount: Int = 0,
    val lastReportedAt: Long? = null,
    /** Report counts per category, used to pick the majority category. */
    val categoryVotes: Map<SpamCategory, Int> = emptyMap(),
    val verified: Boolean = false,
    val businessId: String? = null,
    val carrier: String? = null,
    val lineType: NumberType? = null,
    val regionCode: String? = null,
    val source: InfoSource = InfoSource.NONE,
    val updatedAt: Long = 0L,
    /** Outside spam list that flagged the number; null when the warning comes from WhoCaller users. */
    val listedBy: String? = null,
) {
    /** Spam warning based only on an outside list, with no WhoCaller reports yet. */
    val flaggedOnlyByList: Boolean get() = listedBy != null && reportCount == 0

    val hasIdentity: Boolean get() = !displayName.isNullOrBlank()

    companion object {
        fun empty(numberKey: String) = CallerInfo(numberKey = numberKey)
    }
}

/** What WhoCaller will do with an incoming call. */
enum class CallDecision { ALLOW, WARN, BLOCK }

/** Why a call was blocked or flagged. */
enum class DecisionReason {
    NONE,
    CONTACT,
    USER_BLOCK_LIST,
    HIDDEN_NUMBER,
    UNKNOWN_CALLER,
    BLOCKED_CATEGORY,
    HIGH_RISK,
    SUSPECTED_SPAM,
    PROTECTION_DISABLED,
}

/** How a caller is presented in the UI. */
enum class CallerLabel {
    CONTACT,
    VERIFIED_BUSINESS,
    BUSINESS,
    PERSON,
    SUSPECTED_SPAM,
    POSSIBLE_SCAM,
    TELEMARKETING,
    HIDDEN,
    UNKNOWN,
}

/** Final answer of caller identification. */
data class CallerResult(
    val number: PhoneNumber?,
    val contactName: String?,
    val info: CallerInfo?,
    val spamScore: SpamScore,
    val decision: CallDecision,
    val reason: DecisionReason,
    val label: CallerLabel,
    val isHidden: Boolean = false,
) {
    /** Name to show, preferring the user's own contact name. */
    val displayName: String? get() = contactName ?: info?.displayName?.takeIf { it.isNotBlank() }
}
