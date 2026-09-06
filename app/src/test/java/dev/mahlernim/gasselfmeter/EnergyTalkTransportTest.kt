package dev.mahlernim.gasselfmeter

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Fixed production URL, but every request terminates in this local interceptor. No sockets. */
class EnergyTalkTransportTest {
    private val token = "synthetic-token-12345"
    private val user = """{"responseCode":"ok","clientId":"srb","address":"합성 주소"}"""
    private val usage = """{"responseCode":"ok","list":[{"dateVal":"202609","amount":"1000","usageVal":"1 MJ"}]}"""
    private val meter = """{"responseCode":"ok","checkYn":"N","checkMsg":"합성 대상 아님"}"""
    private fun response(request: Request, body: String, code: Int = 200): Response = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(code).message("Synthetic")
        .header("Location", "https://untrusted.invalid/never-follow")
        .body(body.toResponseBody()).build()
    private fun transport(replies: List<Pair<Int, String>>, requests: MutableList<String>): EnergyTalkReadClient {
        val base = OkHttpClient.Builder().followRedirects(true).addInterceptor { chain ->
            val request = chain.request()
            assertEquals("https://energytalk.ai/api/fetch", request.url.toString())
            assertEquals("POST", request.method)
            assertEquals("Bearer $token", request.header("Authorization"))
            assertEquals("https://energytalk.ai", request.header("Origin"))
            val buffer = Buffer()
            requireNotNull(request.body).writeTo(buffer)
            val envelope = JSONObject(buffer.readUtf8())
            assertEquals("GET", envelope.getString("method"))
            assertEquals(0, envelope.getJSONObject("body").length())
            val index = requests.size
            requests += envelope.getString("url")
            check(index < replies.size) { "Unexpected additional request" }
            val (code, body) = replies[index]
            response(request, body, code)
        }.build()
        return EnergyTalkReadClient(base)
    }

    @Test fun wrongTenantStopsBeforeAnySectionReads() = runBlocking {
        val requests = CopyOnWriteArrayList<String>()
        val client = transport(listOf(200 to user.replace("srb", "kne")), requests)
        try { client.verifyAndRead(token, "srb"); fail("Wrong tenant accepted") } catch (_: IllegalStateException) { }
        assertEquals(listOf("/gas/api/user/info"), requests)
    }
    @Test fun missingAddressStopsBeforeAnySectionReads() = runBlocking {
        val requests = CopyOnWriteArrayList<String>()
        val client = transport(listOf(200 to user.replace("합성 주소", "")), requests)
        try { client.verifyAndRead(token, "srb"); fail("Missing address accepted") } catch (_: IllegalStateException) { }
        assertEquals(listOf("/gas/api/user/info"), requests)
    }
    @Test fun redirectIsRejectedWithoutFollowingOrReplaying() = runBlocking {
        val requests = CopyOnWriteArrayList<String>()
        val client = transport(listOf(302 to ""), requests)
        try { client.verifyAndRead(token, "srb"); fail("Redirect accepted") } catch (_: IllegalStateException) { }
        assertEquals(1, requests.size)
    }
    @Test fun sectionSchemaFailureAllowsOtherSectionButDoesNotInventUsage() = runBlocking {
        val requests = CopyOnWriteArrayList<String>()
        val client = transport(listOf(200 to user, 200 to """{"responseCode":"ok","list":"changed"}""", 200 to meter), requests)
        val snapshot = client.verifyAndRead(token, "srb")
        assertTrue(snapshot.usage.isEmpty())
        assertNotNull(snapshot.meter)
        assertEquals(1, snapshot.unavailable.size)
        assertEquals(listOf("/gas/api/user/info", "/gas/api/pay/usage", "/gas/api/self-meter"), requests)
    }
    @Test fun authenticationFailureNeverBecomesPartialSuccess() = runBlocking {
        for (error in listOf("no-token", "expired-token", "invalid-token")) {
            for (failureAtMeter in listOf(false, true)) {
                val requests = CopyOnWriteArrayList<String>()
                val replies = mutableListOf(200 to user)
                if (failureAtMeter) replies += 200 to usage
                replies += 200 to """{"responseCode":"$error"}"""
                try { transport(replies, requests).verifyAndRead(token, "srb"); fail("Authentication failure accepted") }
                catch (_: EnergyTalkAuthException) { }
                assertEquals(if (failureAtMeter) 3 else 2, requests.size)
            }
        }
    }
    @Test fun httpUnauthorizedNeverBecomesPartialSuccess() = runBlocking {
        val requests = CopyOnWriteArrayList<String>()
        try { transport(listOf(200 to user, 401 to ""), requests).verifyAndRead(token, "srb"); fail("401 accepted") }
        catch (_: EnergyTalkAuthException) { }
        assertEquals(2, requests.size)
    }
    @Test fun oversizedSectionIsRejectedAndMeterCanStillBeShown() = runBlocking {
        val requests = CopyOnWriteArrayList<String>()
        val snapshot = transport(listOf(200 to user, 200 to "x".repeat(262145), 200 to meter), requests).verifyAndRead(token, "srb")
        assertTrue(snapshot.usage.isEmpty())
        assertEquals(1, snapshot.unavailable.size)
        assertNotNull(snapshot.meter)
    }
    @Test fun preflightAndSubmissionUseOneCheckedFormRequest() = runBlocking {
        val requests = CopyOnWriteArrayList<String>()
        val base = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body = Buffer().also { requireNotNull(request.body).writeTo(it) }.readUtf8()
            val key = if (request.url.encodedPath == "/api/formdata") {
                assertEquals("POST", request.method)
                assertTrue(body.contains("name=\"method\""))
                assertTrue(body.contains("name=\"url\""))
                assertTrue(body.contains("/gas/api/self-meter"))
                assertTrue(body.contains("101"))
                "form"
            } else {
                val envelope = JSONObject(body)
                envelope.getString("method") + ":" + envelope.getString("url")
            }
            requests += key
            val responseBody = when (key) {
                "GET:/gas/api/user/info" -> user
                "POST:/gas/api/self-meter/check" -> """{"responseCode":"ok","addableYn":"Y","notificationMsg":"합성 통과"}"""
                "form" -> """{"responseCode":"ok"}"""
                else -> error("unexpected request $key")
            }
            response(request, responseBody)
        }.build()
        val client = EnergyTalkReadClient(base)
        assertTrue(client.checkReading(token, "srb", 101.0).allowed)
        client.submitReading(token, "srb", 101.0)
        assertEquals(listOf("GET:/gas/api/user/info", "POST:/gas/api/self-meter/check", "GET:/gas/api/user/info", "form"), requests)
    }
    @Test fun cancellationCancelsInFlightCallAndDoesNotStartAnotherRequest() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val observed = AtomicReference<Call>()
        val base = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            observed.set(chain.call())
            started.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            throw IOException("Synthetic request released")
        }).build()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { EnergyTalkReadClient(base).verifyAndRead(token, "srb") }
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            job.cancelAndJoin()
            assertTrue(observed.get().isCanceled())
        } finally { release.countDown(); job.cancelAndJoin() }
    }
}
