package com.rskusum.whocaller.core.network

import com.rskusum.whocaller.core.common.result.AppError
import com.rskusum.whocaller.core.common.result.AppResult
import com.rskusum.whocaller.core.network.model.BlockSignalDto
import com.rskusum.whocaller.core.network.model.BusinessVerificationRequestDto
import com.rskusum.whocaller.core.network.model.PurchaseVerificationRequestDto
import com.rskusum.whocaller.core.network.model.ReportRequestDto
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class RetrofitNetworkDataSource(private val api: WhoCallerApi) : NetworkDataSource {

    override val isConfigured = true

    override suspend fun getNumber(e164: String) = call { api.getNumber(e164) }
    override suspend fun reportNumber(e164: String, request: ReportRequestDto) = call { api.reportNumber(e164, request) }
    override suspend fun signalBlock(e164: String) = call { api.signalBlock(e164, BlockSignalDto(blocked = true)) }
    override suspend fun searchBusinesses(query: String, region: String?) = call { api.searchBusinesses(query, region).results }
    override suspend fun getBusiness(businessId: String) = call { api.getBusiness(businessId) }
    override suspend fun requestBusinessVerification(request: BusinessVerificationRequestDto) =
        call { api.requestBusinessVerification(request) }
    override suspend fun getConfig() = call { api.getConfig() }
    override suspend fun getCountries() = call { api.getCountries() }
    override suspend fun getSpamList(region: String, since: Long?) = call { api.getSpamList(region, since) }
    override suspend fun verifyPurchase(productId: String, purchaseToken: String) =
        call { api.verifyPurchase(PurchaseVerificationRequestDto(productId, purchaseToken)) }
    override suspend fun deleteAccount() = call { api.deleteAccount() }

    private suspend inline fun <T> call(crossinline block: suspend () -> T): AppResult<T> = try {
        AppResult.Success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AppResult.Failure(mapError(e), e)
    }

    companion object {
        val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            coerceInputValues = true
        }

        fun mapError(e: Throwable): AppError = when (e) {
            is HttpException -> when (e.code()) {
                400, 422 -> AppError.INVALID_NUMBER
                401, 403 -> AppError.UNAUTHORIZED
                404 -> AppError.NOT_FOUND
                409 -> AppError.DUPLICATE
                429 -> AppError.RATE_LIMITED
                in 500..599 -> AppError.SERVER
                else -> AppError.UNKNOWN
            }
            is SocketTimeoutException -> AppError.TIMEOUT
            is InterruptedIOException -> AppError.TIMEOUT
            is UnknownHostException -> AppError.NETWORK_UNAVAILABLE
            is IOException -> AppError.NETWORK_UNAVAILABLE
            is SerializationException -> AppError.SERVER
            is IllegalArgumentException -> AppError.SERVER
            else -> AppError.UNKNOWN
        }

        fun createApi(baseUrl: String, client: OkHttpClient): WhoCallerApi {
            val url = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
            return Retrofit.Builder()
                .baseUrl(url)
                .client(client)
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(WhoCallerApi::class.java)
        }
    }
}
