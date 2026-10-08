package com.rskusum.whocaller.core.network

import com.rskusum.whocaller.core.common.result.AppError
import com.rskusum.whocaller.core.common.result.AppResult
import com.rskusum.whocaller.core.network.model.BusinessDto
import com.rskusum.whocaller.core.network.model.BusinessVerificationRequestDto
import com.rskusum.whocaller.core.network.model.BusinessVerificationResponseDto
import com.rskusum.whocaller.core.network.model.CountryDto
import com.rskusum.whocaller.core.network.model.NumberInfoDto
import com.rskusum.whocaller.core.network.model.PurchaseVerificationResponseDto
import com.rskusum.whocaller.core.network.model.RemoteConfigDto
import com.rskusum.whocaller.core.network.model.ReportRequestDto
import com.rskusum.whocaller.core.network.model.ReportResponseDto
import com.rskusum.whocaller.core.network.model.SpamListResponseDto

/**
 * Marks the REST/dev/unconfigured data source built from BuildConfig. The app module may wrap it
 * (e.g. with Firestore) and provide the unqualified [NetworkDataSource].
 */
@javax.inject.Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class BaseNetwork

/**
 * Backend abstraction used by repositories. Swapping Firebase/Cloud Functions for another server
 * only requires a different implementation (or just a different base URL for the REST one).
 */
interface NetworkDataSource {
    val isConfigured: Boolean
    suspend fun getNumber(e164: String): AppResult<NumberInfoDto>
    suspend fun reportNumber(e164: String, request: ReportRequestDto): AppResult<ReportResponseDto>
    suspend fun signalBlock(e164: String): AppResult<Unit>
    suspend fun searchBusinesses(query: String, region: String?): AppResult<List<BusinessDto>>
    suspend fun getBusiness(businessId: String): AppResult<BusinessDto>
    suspend fun requestBusinessVerification(request: BusinessVerificationRequestDto): AppResult<BusinessVerificationResponseDto>
    suspend fun getConfig(): AppResult<RemoteConfigDto>
    suspend fun getCountries(): AppResult<List<CountryDto>>
    suspend fun getSpamList(region: String, since: Long?): AppResult<SpamListResponseDto>
    suspend fun verifyPurchase(productId: String, purchaseToken: String): AppResult<PurchaseVerificationResponseDto>
    suspend fun deleteAccount(): AppResult<Unit>

    /** Latest on-device SMS spam model (text format of SpamTextModel), if the backend publishes one. */
    suspend fun getSmsSpamModel(): AppResult<SmsModelDto> = AppResult.Failure(AppError.BACKEND_NOT_CONFIGURED)
}

/** A published SMS spam model: [version] increases with every update. */
data class SmsModelDto(val version: Int, val model: String)

/** Used in release builds when no backend URL was configured: fails cleanly, never fakes data. */
class UnconfiguredNetworkDataSource : NetworkDataSource {
    override val isConfigured = false
    private val fail = AppResult.Failure(AppError.BACKEND_NOT_CONFIGURED)
    override suspend fun getNumber(e164: String) = fail
    override suspend fun reportNumber(e164: String, request: ReportRequestDto) = fail
    override suspend fun signalBlock(e164: String) = fail
    override suspend fun searchBusinesses(query: String, region: String?) = fail
    override suspend fun getBusiness(businessId: String) = fail
    override suspend fun requestBusinessVerification(request: BusinessVerificationRequestDto) = fail
    override suspend fun getConfig() = fail
    override suspend fun getCountries() = fail
    override suspend fun getSpamList(region: String, since: Long?) = fail
    override suspend fun verifyPurchase(productId: String, purchaseToken: String) = fail
    override suspend fun deleteAccount() = fail
}
