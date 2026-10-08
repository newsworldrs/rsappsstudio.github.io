package com.rskusum.whocaller.core.ui.util

import androidx.annotation.StringRes
import com.rskusum.whocaller.core.common.result.AppError
import com.rskusum.whocaller.core.model.CallerLabel
import com.rskusum.whocaller.core.model.ReportReason
import com.rskusum.whocaller.core.model.RiskLevel
import com.rskusum.whocaller.core.model.SpamCategory
import com.rskusum.whocaller.core.ui.R

@StringRes
fun CallerLabel.labelRes(): Int = when (this) {
    CallerLabel.CONTACT -> R.string.label_contact
    CallerLabel.VERIFIED_BUSINESS -> R.string.label_verified_business
    CallerLabel.BUSINESS -> R.string.label_business
    CallerLabel.PERSON -> R.string.label_person
    CallerLabel.SUSPECTED_SPAM -> R.string.label_suspected_spam
    CallerLabel.POSSIBLE_SCAM -> R.string.label_possible_scam
    CallerLabel.TELEMARKETING -> R.string.label_telemarketing
    CallerLabel.HIDDEN -> R.string.label_hidden
    CallerLabel.UNKNOWN -> R.string.label_unknown
}

@StringRes
fun SpamCategory.labelRes(): Int = when (this) {
    SpamCategory.SAFE -> R.string.category_safe
    SpamCategory.UNKNOWN -> R.string.category_unknown
    SpamCategory.TELEMARKETING -> R.string.category_telemarketing
    SpamCategory.SPAM -> R.string.category_spam
    SpamCategory.SCAM -> R.string.category_scam
    SpamCategory.FRAUD -> R.string.category_fraud
    SpamCategory.ROBOCALL -> R.string.category_robocall
    SpamCategory.DEBT_COLLECTION -> R.string.category_debt_collection
    SpamCategory.POLITICAL -> R.string.category_political
    SpamCategory.CHARITY -> R.string.category_charity
    SpamCategory.BUSINESS -> R.string.category_business
}

@StringRes
fun RiskLevel.labelRes(): Int = when (this) {
    RiskLevel.LOW -> R.string.risk_low
    RiskLevel.MODERATE -> R.string.risk_moderate
    RiskLevel.HIGH -> R.string.risk_high
    RiskLevel.VERY_HIGH -> R.string.risk_very_high
}

@StringRes
fun ReportReason.labelRes(): Int = when (this) {
    ReportReason.SPAM -> R.string.reason_spam
    ReportReason.SCAM -> R.string.reason_scam
    ReportReason.TELEMARKETING -> R.string.reason_telemarketing
    ReportReason.FRAUD -> R.string.reason_fraud
    ReportReason.ROBOCALL -> R.string.reason_robocall
    ReportReason.HARASSMENT -> R.string.reason_harassment
    ReportReason.FAKE_BANK_CALL -> R.string.reason_fake_bank_call
    ReportReason.FAKE_DELIVERY_CALL -> R.string.reason_fake_delivery_call
    ReportReason.OTHER -> R.string.reason_other
}

@StringRes
fun AppError.messageRes(): Int = when (this) {
    AppError.NETWORK_UNAVAILABLE -> R.string.error_network
    AppError.TIMEOUT -> R.string.error_timeout
    AppError.NOT_FOUND -> R.string.error_not_found
    AppError.INVALID_NUMBER -> R.string.error_invalid_number
    AppError.UNAUTHORIZED -> R.string.error_unauthorized
    AppError.RATE_LIMITED -> R.string.error_rate_limited
    AppError.DUPLICATE -> R.string.error_duplicate
    AppError.SERVER -> R.string.error_server
    AppError.BACKEND_NOT_CONFIGURED -> R.string.error_backend_not_configured
    AppError.PERMISSION_DENIED -> R.string.error_permission
    AppError.STORAGE -> R.string.error_storage
    AppError.UNKNOWN -> R.string.error_unknown
}
