package com.rskusum.whocaller.core.common.phone

import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.google.i18n.phonenumbers.Phonenumber
import com.rskusum.whocaller.core.model.NumberType
import com.rskusum.whocaller.core.model.PhoneNumber
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** Result of normalizing user- or system-supplied input. */
sealed interface NormalizationResult {
    /** A number we could parse. [PhoneNumber.isValid] may still be false for "possible" numbers. */
    data class Parsed(val number: PhoneNumber) : NormalizationResult

    /** Withheld / private / unknown caller (no number was presented). */
    data object Hidden : NormalizationResult

    /** Input that isn't a phone number at all. */
    data class Invalid(val reason: Reason) : NormalizationResult

    enum class Reason { EMPTY, TOO_SHORT, TOO_LONG, NOT_A_NUMBER }
}

/**
 * Normalizes phone numbers to a single canonical key so that `+919876543210`, `919876543210`,
 * `09876543210` and `98765 43210` (with region IN) all map to the same record.
 *
 * Country rules come from Google's libphonenumber; nothing country-specific is hand-written here.
 */
@Singleton
class PhoneNumberNormalizer @Inject constructor() {

    private val util: PhoneNumberUtil = PhoneNumberUtil.getInstance()

    /**
     * @param input raw text, e.g. from a search box, the call log or Telecom.
     * @param defaultRegion ISO 3166-1 alpha-2 region used when the input has no country code.
     */
    fun normalize(input: String?, defaultRegion: String?): NormalizationResult {
        val trimmed = input?.trim().orEmpty()
        if (trimmed.isEmpty()) return NormalizationResult.Hidden
        if (isHiddenMarker(trimmed)) return NormalizationResult.Hidden

        val cleaned = clean(trimmed)
        val digits = cleaned.filter { it.isDigit() }
        if (digits.isEmpty()) return NormalizationResult.Invalid(NormalizationResult.Reason.NOT_A_NUMBER)
        if (digits.length < MIN_DIGITS) return NormalizationResult.Invalid(NormalizationResult.Reason.TOO_SHORT)
        if (digits.length > MAX_DIGITS) return NormalizationResult.Invalid(NormalizationResult.Reason.TOO_LONG)

        val region = defaultRegion?.uppercase(Locale.ROOT)?.takeIf { util.supportedRegions.contains(it) }
            ?: UNKNOWN_REGION

        val candidates = buildList {
            // 1. As typed, relative to the default region ("09876543210", "+44 20…", "0044 20…").
            parse(cleaned, region)?.let(::add)
            // 2. Country code typed without "+" ("919876543210").
            if (!cleaned.startsWith("+")) parse("+$digits", UNKNOWN_REGION)?.let(::add)
        }

        val best = candidates.firstOrNull { util.isValidNumber(it) }
            ?: candidates.firstOrNull { util.isPossibleNumber(it) }

        return if (best != null) {
            NormalizationResult.Parsed(toModel(best, trimmed))
        } else {
            // Keep a deterministic, collision-free key for short codes and unparseable numbers.
            NormalizationResult.Parsed(
                PhoneNumber(
                    key = PhoneNumber.RAW_PREFIX + digits,
                    raw = trimmed,
                    e164 = null,
                    regionCode = null,
                    countryCallingCode = null,
                    nationalFormat = null,
                    internationalFormat = null,
                    isValid = false,
                    type = NumberType.UNKNOWN,
                ),
            )
        }
    }

    /** Canonical key, or null for hidden/invalid input. */
    fun keyOf(input: String?, defaultRegion: String?): String? =
        (normalize(input, defaultRegion) as? NormalizationResult.Parsed)?.number?.key

    /** Formats a number for display while the user types. */
    fun formatAsYouType(input: String, region: String?): String {
        val formatter = util.getAsYouTypeFormatter(region?.uppercase(Locale.ROOT) ?: UNKNOWN_REGION)
        var out = input
        input.forEach { c -> if (c.isDigit() || c == '+') out = formatter.inputDigit(c) }
        return out
    }

    fun countryCodeFor(region: String): Int = util.getCountryCodeForRegion(region.uppercase(Locale.ROOT))

    fun supportedRegions(): Set<String> = util.supportedRegions

    /** Example number for a region, used as an input placeholder. */
    fun exampleNumber(region: String): String? =
        util.getExampleNumberForType(region.uppercase(Locale.ROOT), PhoneNumberUtil.PhoneNumberType.MOBILE)
            ?.let { util.format(it, PhoneNumberUtil.PhoneNumberFormat.INTERNATIONAL) }

    private fun parse(text: String, region: String): Phonenumber.PhoneNumber? = try {
        util.parse(text, region)
    } catch (_: NumberParseException) {
        null
    }

    private fun toModel(number: Phonenumber.PhoneNumber, raw: String): PhoneNumber {
        val e164 = util.format(number, PhoneNumberUtil.PhoneNumberFormat.E164)
        return PhoneNumber(
            key = e164,
            raw = raw,
            e164 = e164,
            regionCode = util.getRegionCodeForNumber(number),
            countryCallingCode = number.countryCode,
            nationalFormat = util.format(number, PhoneNumberUtil.PhoneNumberFormat.NATIONAL),
            internationalFormat = util.format(number, PhoneNumberUtil.PhoneNumberFormat.INTERNATIONAL),
            isValid = util.isValidNumber(number),
            type = mapType(util.getNumberType(number)),
        )
    }

    private fun mapType(type: PhoneNumberUtil.PhoneNumberType): NumberType = when (type) {
        PhoneNumberUtil.PhoneNumberType.MOBILE -> NumberType.MOBILE
        PhoneNumberUtil.PhoneNumberType.FIXED_LINE -> NumberType.FIXED_LINE
        PhoneNumberUtil.PhoneNumberType.FIXED_LINE_OR_MOBILE -> NumberType.FIXED_LINE_OR_MOBILE
        PhoneNumberUtil.PhoneNumberType.TOLL_FREE -> NumberType.TOLL_FREE
        PhoneNumberUtil.PhoneNumberType.PREMIUM_RATE -> NumberType.PREMIUM_RATE
        PhoneNumberUtil.PhoneNumberType.SHARED_COST -> NumberType.SHARED_COST
        PhoneNumberUtil.PhoneNumberType.VOIP -> NumberType.VOIP
        PhoneNumberUtil.PhoneNumberType.PERSONAL_NUMBER -> NumberType.PERSONAL_NUMBER
        PhoneNumberUtil.PhoneNumberType.PAGER -> NumberType.PAGER
        PhoneNumberUtil.PhoneNumberType.UAN -> NumberType.UAN
        PhoneNumberUtil.PhoneNumberType.VOICEMAIL -> NumberType.VOICEMAIL
        else -> NumberType.UNKNOWN
    }

    companion object {
        private const val UNKNOWN_REGION = "ZZ"
        private const val MIN_DIGITS = 3
        private const val MAX_DIGITS = 17

        /** Values Android and carriers use when the caller withholds their number. */
        private val HIDDEN_MARKERS = setOf(
            "-1", "-2", "-3", "-4", "private", "unknown", "restricted", "anonymous", "withheld", "unavailable",
            "payphone",
        )

        fun isHiddenMarker(value: String): Boolean = value.trim().lowercase(Locale.ROOT) in HIDDEN_MARKERS

        /** Removes spaces, dashes, dots, brackets and other formatting; keeps a leading '+'. */
        fun clean(value: String): String {
            val t = value.trim()
            val sb = StringBuilder(t.length)
            t.forEach { c ->
                when {
                    c.isDigit() -> sb.append(c.digitToInt())
                    c == '+' && sb.isEmpty() -> sb.append('+')
                }
            }
            return sb.toString()
        }
    }
}
