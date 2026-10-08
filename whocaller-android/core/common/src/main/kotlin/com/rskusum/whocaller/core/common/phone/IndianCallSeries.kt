package com.rskusum.whocaller.core.common.phone

import com.rskusum.whocaller.core.model.CallerInfo
import com.rskusum.whocaller.core.model.IdentityType
import com.rskusum.whocaller.core.model.InfoSource
import com.rskusum.whocaller.core.model.SpamCategory

/**
 * Number series that India's telecom regulator (TRAI) reserves for commercial calls:
 *  - 140xxxxxxx  — promotional / telemarketing calls from registered telemarketers,
 *  - 1600xxxxxx — service and transactional calls from banks, insurers and other regulated entities.
 * Lets WhoCaller label these calls even when nobody has reported the exact number yet.
 */
object IndianCallSeries {

    enum class Series { PROMOTIONAL_140, SERVICE_1600 }

    /** Series of a number given as E.164 ("+91140…") or raw digits ("0140…", "140…"), or null. */
    fun of(number: String?): Series? {
        val digits = number?.filter(Char::isDigit) ?: return null
        val national = when {
            number.trim().startsWith("+") && digits.startsWith("91") -> digits.drop(2)
            digits.length == 12 && digits.startsWith("91") -> digits.drop(2)
            digits.length == 11 && digits.startsWith("0") -> digits.drop(1)
            number.trim().startsWith("+") -> return null // another country
            else -> digits
        }
        if (national.length != 10) return null
        return when {
            national.startsWith("1600") -> Series.SERVICE_1600
            national.startsWith("140") -> Series.PROMOTIONAL_140
            else -> null
        }
    }

    /** What to show for a number in one of the series when nothing more specific is known. */
    fun infoFor(numberKey: String, number: String?, now: Long): CallerInfo? = when (of(number)) {
        Series.PROMOTIONAL_140 -> CallerInfo(
            numberKey = numberKey,
            displayName = "Telemarketing (140 series)",
            identityType = IdentityType.BUSINESS,
            category = SpamCategory.TELEMARKETING,
            serverScore = PROMOTIONAL_SCORE,
            serverConfidence = 0.9f,
            regionCode = "IN",
            source = InfoSource.LOCAL_CACHE,
            updatedAt = now,
        )
        Series.SERVICE_1600 -> CallerInfo(
            numberKey = numberKey,
            displayName = "Bank / financial service (1600 series)",
            identityType = IdentityType.BUSINESS,
            category = SpamCategory.BUSINESS,
            serverScore = 0,
            serverConfidence = 0.9f,
            verified = true,
            regionCode = "IN",
            source = InfoSource.LOCAL_CACHE,
            updatedAt = now,
        )
        null -> null
    }

    private const val PROMOTIONAL_SCORE = 60
}
