package com.rskusum.whocaller.core.network

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit

/** Supplies a short-lived bearer token (Firebase ID token) or null for guests. */
fun interface AuthTokenProvider {
    suspend fun token(): String?
}

/** Supplies a Firebase App Check token (or another attestation token) so the backend can reject non-genuine clients. */
fun interface AppCheckTokenProvider {
    suspend fun token(): String?
}

data class NetworkConfig(
    /** HTTPS base URL of the backend, e.g. https://api.example.com/. Blank = not configured. */
    val baseUrl: String,
    val userAgent: String,
    val debugLogging: Boolean,
) {
    val isConfigured: Boolean get() = baseUrl.isNotBlank()

    init {
        require(baseUrl.isBlank() || baseUrl.startsWith("https://")) { "Backend URL must use HTTPS" }
    }
}

object HttpClientFactory {

    fun create(
        config: NetworkConfig,
        authTokenProvider: AuthTokenProvider,
        appCheckTokenProvider: AppCheckTokenProvider,
    ): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .addInterceptor(HeaderInterceptor(config.userAgent, authTokenProvider, appCheckTokenProvider))
        .apply {
            if (config.debugLogging) {
                // Headers only: bodies may contain phone numbers.
                addInterceptor(
                    HttpLoggingInterceptor().apply {
                        level = HttpLoggingInterceptor.Level.BASIC
                        redactHeader("Authorization")
                        redactHeader(HeaderInterceptor.APP_CHECK_HEADER)
                    },
                )
            }
        }
        .build()
}

/** Adds auth + App Check headers. Token providers are suspend; OkHttp threads are background threads. */
class HeaderInterceptor(
    private val userAgent: String,
    private val authTokenProvider: AuthTokenProvider,
    private val appCheckTokenProvider: AppCheckTokenProvider,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val (auth, appCheck) = kotlinx.coroutines.runBlocking {
            runCatching { authTokenProvider.token() }.getOrNull() to runCatching { appCheckTokenProvider.token() }.getOrNull()
        }
        val request = chain.request().newBuilder()
            .header("User-Agent", userAgent)
            .header("Accept", "application/json")
            .apply {
                if (!auth.isNullOrBlank()) header("Authorization", "Bearer $auth")
                if (!appCheck.isNullOrBlank()) header(APP_CHECK_HEADER, appCheck)
            }
            .build()
        return chain.proceed(request)
    }

    companion object {
        const val APP_CHECK_HEADER = "X-Firebase-AppCheck"
    }
}
