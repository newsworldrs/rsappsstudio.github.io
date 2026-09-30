package com.rskusum.whocaller.core.network

import com.rskusum.whocaller.core.network.model.BlockSignalDto
import com.rskusum.whocaller.core.network.model.BusinessDto
import com.rskusum.whocaller.core.network.model.BusinessSearchResponseDto
import com.rskusum.whocaller.core.network.model.BusinessVerificationRequestDto
import com.rskusum.whocaller.core.network.model.BusinessVerificationResponseDto
import com.rskusum.whocaller.core.network.model.CategoryDto
import com.rskusum.whocaller.core.network.model.CountryDto
import com.rskusum.whocaller.core.network.model.LoginRequestDto
import com.rskusum.whocaller.core.network.model.LoginResponseDto
import com.rskusum.whocaller.core.network.model.NumberInfoDto
import com.rskusum.whocaller.core.network.model.PurchaseVerificationRequestDto
import com.rskusum.whocaller.core.network.model.PurchaseVerificationResponseDto
import com.rskusum.whocaller.core.network.model.RemoteConfigDto
import com.rskusum.whocaller.core.network.model.ReportRequestDto
import com.rskusum.whocaller.core.network.model.ReportResponseDto
import com.rskusum.whocaller.core.network.model.ReportSummaryDto
import com.rskusum.whocaller.core.network.model.SpamListResponseDto
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/** Retrofit definition of the WhoCaller REST API v1. Numbers are always E.164. */
interface WhoCallerApi {
    @POST("api/v1/auth/login")
    suspend fun login(@Body body: LoginRequestDto): LoginResponseDto

    @GET("api/v1/number/{number}")
    suspend fun getNumber(@Path("number") number: String): NumberInfoDto

    @POST("api/v1/number/{number}/report")
    suspend fun reportNumber(@Path("number") number: String, @Body body: ReportRequestDto): ReportResponseDto

    @GET("api/v1/number/{number}/reports")
    suspend fun getReports(@Path("number") number: String): ReportSummaryDto

    /** Anonymous "someone blocked this" signal; contributes to block frequency. */
    @POST("api/v1/number/{number}/block")
    suspend fun signalBlock(@Path("number") number: String, @Body body: BlockSignalDto)

    @GET("api/v1/search")
    suspend fun searchBusinesses(@Query("q") query: String, @Query("region") region: String?): BusinessSearchResponseDto

    @GET("api/v1/business/{id}")
    suspend fun getBusiness(@Path("id") businessId: String): BusinessDto

    @POST("api/v1/business/verify")
    suspend fun requestBusinessVerification(@Body body: BusinessVerificationRequestDto): BusinessVerificationResponseDto

    @GET("api/v1/config")
    suspend fun getConfig(): RemoteConfigDto

    @GET("api/v1/countries")
    suspend fun getCountries(): List<CountryDto>

    @GET("api/v1/categories")
    suspend fun getCategories(): List<CategoryDto>

    @GET("api/v1/spam/top")
    suspend fun getSpamList(@Query("region") region: String, @Query("since") since: Long?): SpamListResponseDto

    @POST("api/v1/billing/verify")
    suspend fun verifyPurchase(@Body body: PurchaseVerificationRequestDto): PurchaseVerificationResponseDto

    @DELETE("api/v1/account")
    suspend fun deleteAccount()
}
