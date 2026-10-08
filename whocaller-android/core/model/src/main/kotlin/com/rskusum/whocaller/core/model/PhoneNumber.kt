package com.rskusum.whocaller.core.model

/**
 * A phone number after normalization.
 *
 * [key] is the canonical identity used everywhere as a primary key: E.164 (`+919876543210`) when the
 * number could be parsed, otherwise a digits-only fallback prefixed with `raw:` so unparseable input
 * never collides with a real E.164 number.
 */
data class PhoneNumber(
    val key: String,
    val raw: String,
    val e164: String?,
    val regionCode: String?,
    val countryCallingCode: Int?,
    val nationalFormat: String?,
    val internationalFormat: String?,
    val isValid: Boolean,
    val type: NumberType,
) {
    /** Best human-readable representation. */
    val display: String get() = internationalFormat ?: e164 ?: raw

    companion object {
        const val RAW_PREFIX = "raw:"
    }
}

enum class NumberType {
    MOBILE,
    FIXED_LINE,
    FIXED_LINE_OR_MOBILE,
    TOLL_FREE,
    PREMIUM_RATE,
    SHARED_COST,
    VOIP,
    PERSONAL_NUMBER,
    PAGER,
    UAN,
    VOICEMAIL,
    UNKNOWN,
}
