package com.rskusum.whocaller.core.network.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Wire models for the WhoCaller REST API v1 (see docs/API.md). Unknown fields are ignored. */

@Serializable
data class LoginRequestDto(@SerialName("idToken") val idToken: String)

@Serializable
data class LoginResponseDto(
    @SerialName("accessToken") val accessToken: String,
    @SerialName("expiresAt") val expiresAt: Long,
    @SerialName("userId") val userId: String,
)

@Serializable
data class NumberInfoDto(
    @SerialName("number") val number: String,
    @SerialName("name") val name: String? = null,
    /** PERSON | BUSINESS | UNKNOWN */
    @SerialName("identityType") val identityType: String? = null,
    @SerialName("category") val category: String? = null,
    @SerialName("spamScore") val spamScore: Int? = null,
    @SerialName("confidence") val confidence: Float? = null,
    @SerialName("reportCount") val reportCount: Int = 0,
    @SerialName("reportsLast24h") val reportsLast24h: Int = 0,
    @SerialName("reportsLast7d") val reportsLast7d: Int = 0,
    @SerialName("blockCount") val blockCount: Int = 0,
    @SerialName("lastReportedAt") val lastReportedAt: Long? = null,
    @SerialName("categoryVotes") val categoryVotes: Map<String, Int> = emptyMap(),
    @SerialName("verified") val verified: Boolean = false,
    @SerialName("businessId") val businessId: String? = null,
    @SerialName("carrier") val carrier: String? = null,
    @SerialName("lineType") val lineType: String? = null,
    @SerialName("region") val region: String? = null,
    @SerialName("updatedAt") val updatedAt: Long? = null,
    /** Name of the outside spam list that flagged this number (not WhoCaller users' reports). */
    @SerialName("listedBy") val listedBy: String? = null,
)

@Serializable
data class ReportRequestDto(
    @SerialName("reason") val reason: String,
    @SerialName("comment") val comment: String? = null,
    /** Client-generated id so retries are idempotent on the server. */
    @SerialName("clientReportId") val clientReportId: String,
    @SerialName("reportedAt") val reportedAt: Long,
    /** 1–2 post-call categories (see ReportCategory); empty for single-reason reports. */
    @SerialName("categories") val categories: List<String> = emptyList(),
    /** INCOMING_UNKNOWN (post-call screen) or MANUAL. */
    @SerialName("callType") val callType: String? = null,
    @SerialName("callAnswered") val callAnswered: Boolean? = null,
)

@Serializable
data class ReportResponseDto(
    @SerialName("reportId") val reportId: String,
    @SerialName("accepted") val accepted: Boolean,
)

@Serializable
data class ReportSummaryDto(
    @SerialName("number") val number: String,
    @SerialName("total") val total: Int,
    @SerialName("byReason") val byReason: Map<String, Int> = emptyMap(),
    @SerialName("lastReportedAt") val lastReportedAt: Long? = null,
)

@Serializable
data class BusinessDto(
    @SerialName("businessId") val businessId: String,
    @SerialName("name") val name: String,
    @SerialName("phoneNumbers") val phoneNumbers: List<String> = emptyList(),
    @SerialName("category") val category: String? = null,
    @SerialName("address") val address: String? = null,
    @SerialName("website") val website: String? = null,
    @SerialName("logo") val logo: String? = null,
    @SerialName("email") val email: String? = null,
    @SerialName("verified") val verified: Boolean = false,
    @SerialName("verificationDate") val verificationDate: Long? = null,
    @SerialName("country") val country: String? = null,
    @SerialName("language") val language: String? = null,
    @SerialName("hours") val hours: String? = null,
    @SerialName("rating") val rating: Float? = null,
    @SerialName("ratingCount") val ratingCount: Int? = null,
)

@Serializable
data class BusinessSearchResponseDto(
    @SerialName("results") val results: List<BusinessDto> = emptyList(),
)

@Serializable
data class BusinessVerificationRequestDto(
    @SerialName("businessName") val businessName: String,
    @SerialName("phoneNumber") val phoneNumber: String,
    @SerialName("category") val category: String,
    @SerialName("website") val website: String? = null,
    @SerialName("email") val email: String,
    @SerialName("country") val country: String,
)

@Serializable
data class BusinessVerificationResponseDto(
    @SerialName("requestId") val requestId: String,
    /** PENDING | NEEDS_DOCUMENTS | APPROVED | REJECTED */
    @SerialName("status") val status: String,
)

@Serializable
data class RemoteConfigDto(
    @SerialName("minSupportedVersion") val minSupportedVersion: Int = 0,
    @SerialName("lookupCacheTtlHours") val lookupCacheTtlHours: Int = 72,
    @SerialName("spamListMaxEntries") val spamListMaxEntries: Int = 20_000,
    @SerialName("freeSearchesPerDay") val freeSearchesPerDay: Int = 50,
)

@Serializable
data class CountryDto(
    @SerialName("region") val region: String,
    @SerialName("callingCode") val callingCode: Int,
    @SerialName("name") val name: String,
    @SerialName("supported") val supported: Boolean = true,
)

@Serializable
data class CategoryDto(
    @SerialName("id") val id: String,
    @SerialName("severity") val severity: Int = 0,
)

@Serializable
data class SpamListResponseDto(
    @SerialName("region") val region: String,
    @SerialName("generatedAt") val generatedAt: Long,
    @SerialName("entries") val entries: List<NumberInfoDto> = emptyList(),
)

@Serializable
data class BlockSignalDto(@SerialName("blocked") val blocked: Boolean)

@Serializable
data class PurchaseVerificationRequestDto(
    @SerialName("productId") val productId: String,
    @SerialName("purchaseToken") val purchaseToken: String,
)

@Serializable
data class PurchaseVerificationResponseDto(
    @SerialName("valid") val valid: Boolean,
    @SerialName("expiresAt") val expiresAt: Long? = null,
)
