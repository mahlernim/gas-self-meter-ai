package dev.mahlernim.gasselfmeter

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class DaesungClientTest {
    // Synthetic form mirrors the public login field names, never an authenticated capture.
    private val form = """<form action="/users/login" method="post"><input type="hidden" name="returl"><input name="id"><input type="password" name="password"></form>"""
    private fun monthly(clean: Boolean = false) = """<html><h2>${if (clean) "청구요금조회" else "월별요금"}</h2><a href="/users/logout">로그아웃</a><table><tr><th>청구월</th><th>사용량</th><th>synthetic-private-header</th></tr><tr><td>synthetic-customer-data</td><td>100</td></tr></table></html>"""
    private fun html(value: String) = MockResponse().setHeader("Content-Type", "text/html; charset=utf-8").setBody(value)

    @Test fun bothProvidersSendOneCredentialPostAndOnlyReturnAllowlistedObservations() {
        for (id in listOf("daesung", "daesungclean")) MockWebServer().use { server ->
            server.start()
            server.enqueue(html(form).addHeader("Set-Cookie", "probe=synthetic; Path=/; HttpOnly"))
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/charge/month"))
            server.enqueue(html(monthly(id == "daesungclean")))
            DaesungReadProbe(id, server.url("/")).use { probe ->
                val result = probe.check("synthetic-user", " secret & ")
                assertTrue(result.sessionStructureObserved)
                assertTrue(result.billingPageReached)
                assertEquals(1, result.tableCount)
                assertEquals(listOf("사용량", "청구월"), result.recognizedBillingColumns)
                assertFalse(result.toString().contains("synthetic-customer-data"))
                assertThrows(IllegalStateException::class.java) { probe.check("user", "password") }
            }
            val initial = server.takeRequest()
            val login = server.takeRequest()
            val bill = server.takeRequest()
            assertEquals("GET", initial.method)
            assertEquals("/users/login", initial.path)
            assertEquals("POST", login.method)
            assertEquals("/users/login", login.path)
            val fields = login.body.readUtf8().split('&').associate {
                val pair = it.split('=', limit = 2)
                pair[0] to java.net.URLDecoder.decode(pair[1], "UTF-8")
            }
            assertEquals(mapOf("id" to "synthetic-user", "password" to " secret & ", "returl" to "/charge/month"), fields)
            assertEquals("probe=synthetic", login.getHeader("Cookie"))
            assertEquals("GET", bill.method)
            assertEquals("/charge/month", bill.path)
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun loginFailureScriptStopsBeforeReadingBills() = MockWebServer().use { server ->
        server.start()
        server.enqueue(html(form))
        server.enqueue(html("<script>alert('아이디 또는 비밀번호가 일치하지 않습니다.');window.history.go(-1);</script>"))
        DaesungReadProbe("daesung", server.url("/")).use { probe ->
            val failure = assertThrows(ProviderFailure::class.java) { probe.check("user", "secret") }
            assertEquals("login", failure.stage)
            assertEquals("authentication", failure.category)
            assertNull(failure.cause)
            assertFalse(failure.message.orEmpty().contains("secret"))
        }
        assertEquals(2, server.requestCount)
    }

    @Test fun crossOriginAndUnverifiedRedirectsNeverReceiveCredentialsOrGetRequests() {
        for (location in listOf("https://foreign.invalid/charge/month", "/users/delete", "/charge/month?customer=123")) {
            MockWebServer().use { server ->
                server.start()
                server.enqueue(html(form))
                server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", location))
                DaesungReadProbe("daesung", server.url("/")).use { probe ->
                    val failure = assertThrows(ProviderFailure::class.java) { probe.check("user", "secret") }
                    assertEquals("unsupported", failure.category)
                }
                assertEquals(2, server.requestCount)
            }
        }
    }

    @Test fun missingSessionLikeStructureDoesNotReturnCompatibilityObservations() {
        for (page in listOf("<h2>월별요금</h2><table></table>", "<a href='/users/logout'>로그아웃</a>",
            "<h2>월별요금</h2><a href='https://foreign.invalid/logout'>로그아웃</a>",
            "<script>location.replace('/users/login');</script>")) {
            MockWebServer().use { server ->
                server.start()
                server.enqueue(html(form)); server.enqueue(html("<script>location.replace('/');</script>")); server.enqueue(html(page))
                DaesungReadProbe("daesung", server.url("/")).use { probe ->
                    val failure = assertThrows(ProviderFailure::class.java) { probe.check("user", "secret") }
                    assertEquals("bills", failure.stage)
                    assertEquals("unsupported", failure.category)
                }
            }
        }
    }

    @Test fun oversizedHtmlAndChangedFormFailBeforeCredentialPost() {
        for (page in listOf("x".repeat(1_000_001), form.replace("name=\"returl\"", "name=\"csrf\""))) {
            MockWebServer().use { server ->
                server.start(); server.enqueue(html(page))
                DaesungReadProbe("daesung", server.url("/")).use { probe ->
                    val failure = assertThrows(ProviderFailure::class.java) { probe.check("user", "secret") }
                    assertEquals("unsupported", failure.category)
                }
                assertEquals(1, server.requestCount)
            }
        }
    }

    @Test fun cookiesDoNotLeakBetweenProviderProbeInstances() = MockWebServer().use { server ->
        server.start()
        server.enqueue(html(form).addHeader("Set-Cookie", "probe=first; Path=/"))
        server.enqueue(html("<script>alert('아이디 또는 비밀번호가 일치하지 않습니다.');</script>"))
        server.enqueue(html(form))
        server.enqueue(html("<script>alert('아이디 또는 비밀번호가 일치하지 않습니다.');</script>"))
        for (id in listOf("daesung", "daesungclean")) DaesungReadProbe(id, server.url("/")).use { probe ->
            assertThrows(ProviderFailure::class.java) { probe.check("user", "secret") }
        }
        assertNull(server.takeRequest().getHeader("Cookie"))
        assertEquals("probe=first", server.takeRequest().getHeader("Cookie"))
        assertNull(server.takeRequest().getHeader("Cookie"))
        assertNull(server.takeRequest().getHeader("Cookie"))
    }

    @Test fun retryAfterResponseCannotReplayCredentialPost() = MockWebServer().use { server ->
        server.start()
        server.enqueue(html(form))
        server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
        DaesungReadProbe("daesung", server.url("/")).use { probe ->
            val failure = assertThrows(ProviderFailure::class.java) { probe.check("user", "secret") }
            assertEquals("login", failure.stage)
        }
        assertEquals(2, server.requestCount)
    }

    @Test fun redirectLoopIsBoundedAndCancelledProbeDoesNotSendRequests() = MockWebServer().use { server ->
        server.start()
        repeat(4) { server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/users/login")) }
        DaesungReadProbe("daesung", server.url("/")).use { probe ->
            val failure = assertThrows(ProviderFailure::class.java) { probe.check("user", "secret") }
            assertEquals("unsupported", failure.category)
        }
        assertEquals(4, server.requestCount)
        DaesungReadProbe("daesungclean", server.url("/")).use { probe ->
            probe.cancel()
            assertThrows(IllegalStateException::class.java) { probe.check("user", "secret") }
        }
        assertEquals(4, server.requestCount)
    }

    @Test fun cancellationAfterCallRegistrationPreventsCredentialSend() = MockWebServer().use { server ->
        server.start()
        server.enqueue(html(form))
        val postRegistered = java.util.concurrent.CountDownLatch(1)
        val releasePost = java.util.concurrent.CountDownLatch(1)
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
        val transport = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            if (chain.request().method == "POST") {
                postRegistered.countDown()
                check(releasePost.await(5, java.util.concurrent.TimeUnit.SECONDS))
            }
            chain.proceed(chain.request())
        }.build()
        DaesungReadProbe("daesung", server.url("/"), transport).use { probe ->
            try {
                val failure = worker.submit<ProviderFailure> {
                    assertThrows(ProviderFailure::class.java) { probe.check("user", "secret") }
                }
                assertTrue(postRegistered.await(5, java.util.concurrent.TimeUnit.SECONDS))
                probe.cancel()
                releasePost.countDown()
                assertEquals("login", failure.get(5, java.util.concurrent.TimeUnit.SECONDS).stage)
                assertEquals(1, server.requestCount)
                assertEquals("GET", server.takeRequest().method)
            } finally {
                releasePost.countDown()
                worker.shutdownNow()
            }
        }
    }
}
