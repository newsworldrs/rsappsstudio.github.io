package com.rskusum.whocaller.core.model

enum class CallType { INCOMING, OUTGOING, MISSED, REJECTED, BLOCKED, VOICEMAIL, UNKNOWN }

/** Filter tabs of the call history screen. */
enum class CallFilter { ALL, MISSED, INCOMING, OUTGOING, SPAM, UNKNOWN }

data class CallLogEntry(
    val id: Long,
    val rawNumber: String,
    val numberKey: String,
    val displayNumber: String,
    val contactName: String?,
    val type: CallType,
    val timestamp: Long,
    val durationSeconds: Long,
    /** Cached identification, if WhoCaller knows anything about the number. */
    val cachedName: String? = null,
    val category: SpamCategory = SpamCategory.UNKNOWN,
    val spamScore: Int = 0,
    val isHidden: Boolean = false,
) {
    val isSpam: Boolean get() = RiskLevel.fromScore(spamScore) >= RiskLevel.HIGH
    val isUnknown: Boolean get() = contactName == null && cachedName == null
    val title: String? get() = contactName ?: cachedName
}

enum class BlockSource { USER, CATEGORY_RULE, REPORT }

data class BlockedNumber(
    val numberKey: String,
    val displayNumber: String,
    val label: String?,
    val category: SpamCategory?,
    val blockedAt: Long,
    val source: BlockSource,
)

/** A call that WhoCaller rejected. */
data class BlockedCall(
    val id: Long,
    val numberKey: String,
    val displayNumber: String,
    val reason: DecisionReason,
    val category: SpamCategory,
    val timestamp: Long,
)

/** A call WhoCaller screened (identified, warned or blocked). Stored locally only. */
data class IdentifiedCall(
    val id: Long,
    val numberKey: String,
    val displayNumber: String,
    val name: String?,
    val label: CallerLabel,
    val decision: CallDecision,
    val spamScore: Int,
    val timestamp: Long,
)

data class SearchHistoryItem(
    val id: Long,
    val query: String,
    val numberKey: String,
    val displayNumber: String,
    val searchedAt: Long,
)

data class ContactPhone(
    val number: String,
    val numberKey: String,
    /** Localized label from the Contacts Provider (e.g. "Mobile"). */
    val label: String?,
)

data class Contact(
    val id: Long,
    val lookupKey: String,
    val displayName: String,
    val photoUri: String?,
    val starred: Boolean,
    val phones: List<ContactPhone>,
) {
    val initial: Char
        get() = displayName.firstOrNull { it.isLetter() }?.uppercaseChar() ?: '#'
}

data class Business(
    val businessId: String,
    val name: String,
    val phoneNumbers: List<String>,
    val category: String?,
    val address: String?,
    val website: String?,
    val logoUrl: String?,
    val email: String?,
    val verified: Boolean,
    val verificationDate: Long?,
    val country: String?,
    val language: String?,
    val hours: String?,
    /** Average community rating 0–5, only when the backend has ratings. */
    val rating: Float?,
    val ratingCount: Int?,
    val updatedAt: Long,
)

enum class SmsCategory { PERSONAL, TRANSACTIONS, PROMOTIONS, SPAM, SCAM, UNKNOWN }

/** Specific risk indicators found in a message, used to explain a classification. */
enum class SmsSignal {
    CONTAINS_LINK,
    SHORTENED_LINK,
    URGENCY,
    PRIZE_OR_LOTTERY,
    ACCOUNT_THREAT,
    CREDENTIAL_REQUEST,
    PAYMENT_REQUEST,
    OTP,
    TRANSACTION,
    PROMOTION,
    SENDER_BLOCKED,
    SENDER_REPORTED,
}

data class SmsClassification(
    val category: SmsCategory,
    val signals: Set<SmsSignal>,
    /** 0–100, how suspicious the message looks. */
    val riskScore: Int,
)

data class SmsMessage(
    val id: Long,
    val address: String,
    val body: String,
    val timestamp: Long,
    val classification: SmsClassification,
    val threadId: Long = 0,
    val outgoing: Boolean = false,
    val read: Boolean = true,
)

/** One SMS conversation, newest message first. */
data class SmsConversation(
    val threadId: Long,
    val address: String,
    /** Saved contact name or WhoCaller name for the address, if known. */
    val displayName: String?,
    val snippet: String,
    val timestamp: Long,
    val unreadCount: Int,
    val classification: SmsClassification,
)

/** Profile details the user enters about themselves. Stored on this device only. */
data class LocalProfile(
    val name: String = "",
    val profession: String = "",
    val institute: String = "",
    val email: String = "",
    /** Absolute path of the profile photo inside app storage, if the user picked one. */
    val photoPath: String? = null,
    /** Index of a built-in avatar, used when there is no photo. */
    val avatarId: Int? = null,
    val updatedAt: Long = 0,
)

data class UserProfile(
    val uid: String,
    val name: String?,
    val email: String?,
    val phone: String?,
    val isGuest: Boolean,
    val photoUrl: String? = null,
) {
    companion object {
        val GUEST = UserProfile(uid = "guest", name = null, email = null, phone = null, isGuest = true)
    }
}

/** Counters measured on this device only. Never estimated. */
data class UserStats(
    val callsIdentified: Long = 0,
    val spamBlocked: Long = 0,
    val numbersReported: Long = 0,
    val numbersSearched: Long = 0,
)

data class Country(
    val regionCode: String,
    val callingCode: Int,
    /** English name; UI localizes via java.util.Locale. */
    val name: String,
)
