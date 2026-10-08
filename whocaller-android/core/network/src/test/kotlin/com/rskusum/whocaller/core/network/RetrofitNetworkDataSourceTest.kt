package com.rskusum.whocaller.core.network

import com.rskusum.whocaller.core.common.result.AppError
import com.rskusum.whocaller.core.common.result.AppResult
import com.rskusum.whocaller.core.network.model.ReportRequestDto
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class RetrofitNetworkDataSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: RetrofitNetworkDataSource

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        val client = OkHttpClient.Builder()
            .readTimeout(500, TimeUnit.MILLISECONDS)
            .addInterceptor(HeaderInterceptor("WhoCaller-test", { "token-123" }, { "appcheck-456" }))
            .build()
        source = RetrofitNetworkDataSource(RetrofitNetworkDataSource.createApi(server.url("/").toString(), client))
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `number lookup parses and ignores unknown fields`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"number":"+919876543210","name":"ABC Services","identityType":"BUSINESS","category":"TELEMARKETING",
                   "reportCount":127,"categoryVotes":{"TELEMARKETING":100},"verified":false,"futureField":{"x":1}}""",
            ),
        )
        val r = source.getNumber("+919876543210") as AppResult.Success
        assertEquals("ABC Services", r.data.name)
        assertEquals(127, r.data.reportCount)
        assertEquals(100, r.data.categoryVotes["TELEMARKETING"])

        val req = server.takeRequest()
        assertTrue(req.path!!.startsWith("/api/v1/number/"))
        assertEquals("Bearer token-123", req.getHeader("Authorization"))
        assertEquals("appcheck-456", req.getHeader(HeaderInterceptor.APP_CHECK_HEADER))
        assertEquals("WhoCaller-test", req.getHeader("User-Agent"))
    }

    @Test
    fun `http errors map to app errors`() = runTest {
        mapOf(404 to AppError.NOT_FOUND, 429 to AppError.RATE_LIMITED, 401 to AppError.UNAUTHORIZED, 409 to AppError.DUPLICATE, 503 to AppError.SERVER)
            .forEach { (code, expected) ->
                server.enqueue(MockResponse().setResponseCode(code))
                val r = source.getNumber("+10000000000")
                assertEquals("HTTP $code", expected, (r as AppResult.Failure).error)
            }
    }

    @Test
    fun `slow network maps to timeout`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val r = source.getNumber("+10000000000")
        assertEquals(AppError.TIMEOUT, (r as AppResult.Failure).error)
    }

    @Test
    fun `malformed body maps to server error`() = runTest {
        server.enqueue(MockResponse().setBody("<html>oops</html>"))
        assertEquals(AppError.SERVER, (source.getNumber("+10000000000") as AppResult.Failure).error)
    }

    @Test
    fun `report posts json body`() = runTest {
        server.enqueue(MockResponse().setBody("""{"reportId":"r1","accepted":true}"""))
        val r = source.reportNumber("+919876543210", ReportRequestDto("SPAM", "calls daily", "client-1", 1L))
        assertTrue(r.isSuccess)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"reason\":\"SPAM\""))
        assertTrue(body.contains("\"clientReportId\":\"client-1\""))
    }

    @Test
    fun `unconfigured backend never fabricates data`() = runTest {
        val r = UnconfiguredNetworkDataSource().getNumber("+919876543210")
        assertEquals(AppError.BACKEND_NOT_CONFIGURED, (r as AppResult.Failure).error)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `plain http backend is rejected`() {
        NetworkConfig(baseUrl = "http://insecure.example.com", userAgent = "x", debugLogging = false)
    }
}
