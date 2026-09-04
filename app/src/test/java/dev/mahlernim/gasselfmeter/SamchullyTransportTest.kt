package dev.mahlernim.gasselfmeter

import java.io.IOException
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class SamchullyTransportTest {
    @Test fun badResponsesFailWithoutNetworkOrRetry() {
        for ((code, body) in listOf(401 to "{}", 403 to "{}", 302 to "{}", 500 to "{}",
            200 to "<html>login</html>", 200 to "", 200 to "x".repeat(4_000_001))) {
            var requests = 0
            val http = readOnlyHttpClient().newBuilder().addInterceptor { chain ->
                requests++
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(code).message("synthetic").body(body.toResponseBody()).build()
            }.build()
            SamchullyReadClient(Providers.get("samchully"), Credentials("synthetic", "synthetic"), http).use {
                assertThrows(Exception::class.java) { it.user(SamchullySession("synthetic-token", "PER")) }
            }
            assertEquals(1, requests)
        }
    }

    @Test fun connectionLossIsNotRetried() {
        var requests = 0
        val http = readOnlyHttpClient().newBuilder().addInterceptor {
            requests++
            throw IOException("synthetic connection loss")
        }.build()
        SamchullyReadClient(Providers.get("samchully"), Credentials("synthetic", "synthetic"), http).use {
            val failure = assertThrows(ProviderFailure::class.java) { it.user(SamchullySession("synthetic-token", "PER")) }
            assertEquals("user", failure.stage)
            assertEquals("network", failure.category)
        }
        assertEquals(1, requests)
        assertFalse(http.retryOnConnectionFailure)
        assertFalse(http.followRedirects)
        assertFalse(http.followSslRedirects)
    }
}
