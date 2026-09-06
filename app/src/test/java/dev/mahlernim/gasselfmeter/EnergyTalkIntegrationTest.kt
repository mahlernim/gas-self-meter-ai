package dev.mahlernim.gasselfmeter

import org.junit.Assert.*
import org.junit.Test
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlinx.coroutines.runBlocking

class EnergyTalkIntegrationTest {
    private val connection = EnergyTalkConnection("cncity", "synthetic-session-token-only")
    private val now = System.currentTimeMillis()
    private val snapshot = EnergyTalkSnapshot("cncity", "합성 주소", emptyList(), EnergyTalkMeter(true, "100", null, null), emptyList())

    @Test fun firstReadingAndLateConfirmationSurviveStorageRoundTrip() {
        val connected = EnergyTalkBridge.merge(AppData(), connection, snapshot, now)
        val recorded = connected.copy(observations = listOf(Observation(now - 1000, 110.8, connected.profile.meter)))
        assertEquals(110.0, EnergyTalkSubmissionPolicy.decide(recorded, recorded.cachedSelfRead, now, false).value!!, .001)
        assertFalse(EnergyTalkSubmissionPolicy.decide(recorded.copy(profile = recorded.profile.copy(meter = "replacement")), recorded.cachedSelfRead, now, false).allowed)
        val target = recorded.cachedSelfRead!!
        val pending = recorded.copy(submissions = listOf(SubmissionRecord(target.cycle, target.start, target.end, 110.0, now, "uncertain", "결과 조회")))
        val restored = DataCodec.decode(DataCodec.encode(pending, true), true)
        val confirmed = EnergyTalkBridge.merge(restored, connection, snapshot.copy(meter = EnergyTalkMeter(true, "100", "110", null)), now)
        assertEquals("confirmed", confirmed.submissions.single().status)
        assertTrue(confirmed.cachedSelfRead!!.submitted)
        assertFalse(EnergyTalkSubmissionPolicy.decide(confirmed, confirmed.cachedSelfRead, now, false).allowed)
        assertNull(DataCodec.decode(DataCodec.encode(confirmed)).energyTalkConnection)
    }

    @Test fun retryAfterResponseDoesNotReplaySubmission() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"responseCode":"ok","clientId":"cncity"}"""))
            server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
            server.enqueue(MockResponse().setBody("""{"responseCode":"ok"}"""))
            val transport = OkHttpClient.Builder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build())
            }.build()
            try { EnergyTalkReadClient(transport).submitReading(connection.session, connection.tenant, 110.0); fail("503 accepted") }
            catch (_: IllegalStateException) { }
            assertEquals(2, server.requestCount)
            assertEquals("/api/fetch", server.takeRequest().path)
            assertEquals("/api/formdata", server.takeRequest().path)
        }
    }
}
