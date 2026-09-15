package dev.mahlernim.gasselfmeter

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class SkensAuthenticationTest {
    @Test fun immediateLoginRedirectsRequireReconnectionForEverySkensProvider() {
        Providers.all.filter { it.skens }.forEach { provider ->
            val html = "<script type='text/javascript'>parent.location.replace('/${provider.id}/login/login.do?returnURL=/${provider.id}/read/selfRead.do');</script>"
            val failure = assertThrows(ProviderFailure::class.java) { SkensClient.document(html) }
            assertTrue(BackgroundState.rejectedCredentials(failure))
        }
    }

    @Test fun passwordFormRequiresReconnection() {
        val failure = assertThrows(ProviderFailure::class.java) {
            SkensClient.document("<form><input type='password'></form>")
        }
        assertTrue(BackgroundState.rejectedCredentials(failure))
    }

    @Test fun jsonMeterEndpointRejectsLoginHtmlBeforeJsonParsing() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("<script>window.location.assign('/busan/login/login.do');</script>"))
            SkensClient(Providers.get("busan"), Credentials("synthetic", "synthetic")).use { api ->
                val transport = OkHttpClient.Builder().retryOnConnectionFailure(false).addInterceptor { chain ->
                    chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build())
                }.build()
                SkensClient::class.java.getDeclaredField("client").apply { isAccessible = true }.set(api, transport)
                val failure = assertThrows(ProviderFailure::class.java) {
                    api.selfReadTarget(Contract("synthetic-bp", "synthetic-ca"))
                }
                assertEquals("meter", failure.stage)
                assertTrue(BackgroundState.rejectedCredentials(failure))
            }
            assertEquals(1, server.requestCount)
            assertEquals("/busan/read/call_EBPP_018.do", server.takeRequest().path)
        }
    }

    @Test fun dormantLoginCodeAndOrdinaryErrorsAreNotAuthenticationFailures() {
        SkensClient.document("<a href='/busan/login/login.do'>Login</a><script>function login() { parent.location.replace('/busan/login/login.do'); }</script>")
        SkensClient.document("<script>parent.location.replace('/busan/read/selfRead.do');</script>")
        val failure = assertThrows(IllegalStateException::class.java) {
            SkensClient.document("<title>Error</title>")
        }
        assertFalse(BackgroundState.rejectedCredentials(failure))
    }

    @Test fun portalSessionLossAfterLoginIsClassifiedWithoutReplayingCredentials() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("<input type='password'>"))
            server.enqueue(MockResponse().setBody("{\"errCd\":\"S\"}"))
            server.enqueue(MockResponse().setBody("<script>parent.location.replace('/busan/login/login.do?returnURL=/busan/read/selfRead.do');</script>"))
            SkensClient(Providers.get("busan"), Credentials("synthetic", "synthetic")).use { api ->
                val transport = OkHttpClient.Builder().retryOnConnectionFailure(false).addInterceptor { chain ->
                    chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build())
                }.build()
                SkensClient::class.java.getDeclaredField("client").apply { isAccessible = true }.set(api, transport)
                val failure = assertThrows(ProviderFailure::class.java) { api.login() }
                assertTrue(BackgroundState.rejectedCredentials(failure))
            }
            assertEquals(3, server.requestCount)
            assertEquals(listOf("/busan/login/login.do", "/busan/login/loginProcess.do", "/busan/read/selfRead.do"),
                List(server.requestCount) { server.takeRequest().path })
        }
    }
}
