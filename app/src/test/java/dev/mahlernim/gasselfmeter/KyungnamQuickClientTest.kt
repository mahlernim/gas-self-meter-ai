package dev.mahlernim.gasselfmeter

import java.io.IOException
import java.net.SocketTimeoutException
import java.time.YearMonth
import javax.net.ssl.SSLHandshakeException
import okhttp3.FormBody
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class KyungnamQuickClientTest {
    private val valid = """{"YEARMNTH":"202609","TOTAMT":"12,345","M3AMT":"12.5","MEJAMT":543.25}"""

    @Test fun closeCancelsRegisteredCallAndRejectsFurtherLookups() {
        val registered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
        var interceptedCall: okhttp3.Call? = null
        val transport = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            interceptedCall = chain.call()
            registered.countDown()
            check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("synthetic").body(valid.toResponseBody()).build()
        }.build()
        val client = KyungnamQuickClient.forTests(transport)
        try {
            val failure = worker.submit<ProviderFailure> {
                assertThrows(ProviderFailure::class.java) { client.lookup("123456789") }
            }
            assertTrue(registered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            client.close()
            assertTrue(interceptedCall!!.isCanceled())
            release.countDown()
            assertEquals("bills", failure.get(5, java.util.concurrent.TimeUnit.SECONDS).stage)
            assertThrows(ProviderFailure::class.java) { client.lookup("123456789") }
        } finally {
            release.countDown()
            client.close()
            worker.shutdownNow()
        }
    }

    @Test fun parsesOnlyOfficiallyLabeledValuesWithTheirDistinctUnits() {
        val bill = KyungnamQuickClient.parse(valid)!!
        assertEquals(YearMonth.of(2026, 9), bill.billMonth)
        assertEquals(12345.0, bill.billedAmount!!, 0.0)
        assertEquals(12.5, bill.correctedUsage!!, 0.0)
        assertEquals(543.25, bill.energyUsageMj!!, 0.0)
        val zero = KyungnamQuickClient.parse("""{"YEARMNTH":"2026-09","TOTAMT":0,"M3AMT":"0"}""")!!
        assertEquals(0.0, zero.billedAmount!!, 0.0)
        assertEquals(0.0, zero.correctedUsage!!, 0.0)
        assertNull(zero.energyUsageMj)
        val largeNumber = KyungnamQuickClient.parse("""{"YEARMNTH":"202609","TOTAMT":10000000.0}""")!!
        assertEquals(10000000.0, largeNumber.billedAmount!!, 0.0)
    }

    @Test fun noBillDoesNotAssertCustomerValidityAndMissingAmountsAreNotZero() {
        for (text in listOf("", " ", "null", "{}", """{"YEARMNTH":""}""", """{"YEARMNTH":null}""")) {
            assertNull(KyungnamQuickClient.parse(text))
        }
        val bill = KyungnamQuickClient.parse("""{"YEARMNTH":202609,"TOTAMT":"","M3AMT":null}""")!!
        assertNull(bill.billedAmount)
        assertNull(bill.correctedUsage)
    }

    @Test fun rejectsUnsupportedShapesDatesAndNumericValuesWithoutLeakingInput() {
        val invalid = listOf("<html>private error</html>", "[]", "false", "{", "{} trailing",
            """{"error":"private server error"}""", """{"YEARMNTH":"202613"}""",
            """{"YEARMNTH":"20260904"}""", """{"YEARMNTH":true}""") +
            listOf("-1", "NaN", "Infinity", "1e5", "12,34", "1원", "9".repeat(400)).map {
                """{"YEARMNTH":"202609","TOTAMT":"$it"}"""
            } + listOf("true", "{}", "[]", "-1").map { """{"YEARMNTH":"202609","M3AMT":$it}""" }
        invalid.forEach { text ->
            val failure = assertThrows(ProviderFailure::class.java) { KyungnamQuickClient.parse(text) }
            assertEquals("parse", failure.category)
            assertEquals("bills", failure.stage)
            assertNull(failure.cause)
            assertFalse(failure.message!!.contains("private"))
        }
    }

    @Test fun sendsSingleFormRequestToFixedTlsEndpointWithNoCustomerInUrl() {
        var requests = 0
        val http = readOnlyHttpClient().newBuilder().addInterceptor { chain ->
            requests++
            val request = chain.request()
            assertEquals("https://www.knenergy.co.kr/kcf010_search.do", request.url.toString())
            assertEquals("POST", request.method)
            val form = request.body as FormBody
            assertEquals(1, form.size)
            assertEquals("MSTCD", form.name(0))
            assertEquals("123456789", form.value(0))
            assertNull(request.header("Cookie"))
            assertNull(request.header("Authorization"))
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200)
                .message("synthetic").body(valid.toResponseBody()).build()
        }.build()
        KyungnamQuickClient.forTests(http).use { assertNotNull(it.lookup("123456789")) }
        assertEquals(1, requests)
    }

    @Test fun rejectsBadCustomerInputBeforeAnyRequest() {
        var requests = 0
        val http = readOnlyHttpClient().newBuilder().addInterceptor { requests++; error("must not send") }.build()
        KyungnamQuickClient.forTests(http).use { client ->
            listOf("", "1234567890", "12-34", "abc", "12\n34").forEach {
                assertEquals("validation", assertThrows(ProviderFailure::class.java) { client.lookup(it) }.category)
            }
        }
        assertEquals(0, requests)
    }

    @Test fun refusesRedirectsErrorsAndOversizedResponsesWithoutRetry() {
        for ((code, body) in listOf(302 to "private", 401 to "private", 403 to "private", 500 to "private",
            200 to "x".repeat(65_537))) {
            var requests = 0
            val http = readOnlyHttpClient().newBuilder().addInterceptor { chain ->
                requests++
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code)
                    .header("Location", "https://example.invalid/").message("synthetic")
                    .body(body.toResponseBody()).build()
            }.build()
            KyungnamQuickClient.forTests(http).use { client ->
                val failure = assertThrows(ProviderFailure::class.java) { client.lookup("123456789") }
                assertEquals(if (code == 200) "parse" else if (code in setOf(401, 403)) "authentication" else "http", failure.category)
                assertNull(failure.cause)
            }
            assertEquals(1, requests)
        }
    }

    @Test fun transportFailuresAreCategorizedAndDoNotRetainPrivateCauses() {
        for ((error, category) in listOf(IOException("private") to "network",
            SocketTimeoutException("private") to "timeout", SSLHandshakeException("private") to "tls")) {
            var requests = 0
            val http = readOnlyHttpClient().newBuilder().addInterceptor { requests++; throw error }.build()
            KyungnamQuickClient.forTests(http).use { client ->
                val failure = assertThrows(ProviderFailure::class.java) { client.lookup("123456789") }
                assertEquals(category, failure.category)
                assertNull(failure.cause)
            }
            assertEquals(1, requests)
        }
    }
}
