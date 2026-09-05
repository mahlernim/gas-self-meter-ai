package dev.mahlernim.gasselfmeter

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/** Regression coverage for submission, storage and integrated estimates. */
class AlphaReliabilityTest {
    @Test fun issue48IncompleteTargetMustNotAuthorizeSubmission() {
        val provider = Providers.get("busan")
        val contract = Contract("synthetic-bp", "synthetic-ca")
        val date = today()
        val time = dayStart(date) + 1000L
        val serial = "synthetic-meter"
        val data = AppData(profile = Profile("busan", SkensClient.opaque(serial), SkensClient.contractKey(provider, contract)),
            ready = true, credentials = Credentials("synthetic", "synthetic"), submissionSettings = SubmissionSettings(automatic = true),
            observations = listOf(Observation(time - 86_400_000L, 100.0, SkensClient.opaque(serial)), Observation(time, 102.0, SkensClient.opaque(serial))))
        SkensClient(provider, data.credentials!!).use { client ->
            val method = SkensClient::class.java.getDeclaredMethod("parseSelfReadTarget", JSONObject::class.java, Contract::class.java).apply { isAccessible = true }
            var rejected = false
            val target = try { method.invoke(client, JSONObject().put("START_DATE", date.toString()).put("END_DATE", date.toString()).put("CERAET", serial), contract) as? SelfReadTarget }
                catch (_: Exception) { rejected = true; null }
            val manual = !rejected && SubmissionPolicy.decide(data, target, time, false).allowed
            val automatic = !rejected && SubmissionPolicy.decide(data, target, time, true).allowed
            println("issue48 manual=$manual automatic=$automatic previous=${target?.previousValue}")
            assertFalse("Missing provider status and previous reading must not authorize either submission path", manual || automatic)
        }
    }

    @Test fun issue49UnknownSubmissionResponseMustReconcile() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("{}"))
            server.enqueue(MockResponse().setBody("{\"list\":[]}"))
            SkensClient(Providers.get("busan"), Credentials("synthetic", "synthetic")).use { client ->
                val local = OkHttpClient.Builder().retryOnConnectionFailure(false).followRedirects(false)
                    .callTimeout(3, TimeUnit.SECONDS).addInterceptor { chain ->
                        chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build())
                    }.build()
                SkensClient::class.java.getDeclaredField("client").apply { isAccessible = true }.set(client, local)
                val date = today().toString()
                val target = SelfReadTarget("synthetic-cycle", date, date, true, false, null, 100.0,
                    Contract("synthetic-bp", "synthetic-ca"), "synthetic-meter", "", date, "synthetic", "synthetic")
                val result = runCatching { client.submitReading(target, 110.0) }.getOrNull()
                println("issue49 result=$result requests=${server.requestCount}")
                assertEquals("Unknown reply must trigger the receipt read rather than an immediate rejection", 2, server.requestCount)
                assertEquals("uncertain", result?.status)
            }
        }
    }

    @Test fun issue51LocalCodecMustRetainDataAfterClockMovesBack() {
        val readingTimeBeforeClockAdjustment = System.currentTimeMillis() + 120_000L
        val source = AppData(profile = Profile("busan", "synthetic-meter"),
            observations = listOf(Observation(readingTimeBeforeClockAdjustment, 100.0, "synthetic-meter")), ready = true)
        val encoded = DataCodec.encode(source, true)
        val restored = runCatching { DataCodec.decode(encoded, true) }.getOrNull()
        println("issue51 restored=" + (restored != null))
        assertNotNull("Local data must remain readable when system time is earlier than a saved observation", restored)
    }

    @Test fun issue52RobustRateMustReachSeasonalEstimate() {
        val last = dayStart(LocalDate.of(2026, 9, 1))
        val meter = "synthetic-meter"
        val data = AppData(profile = Profile("busan", meter), periods = listOf(
            UsagePeriod("2025-07-01", "2025-07-31", 62.0, meter),
            UsagePeriod("2025-08-01", "2025-08-31", 62.0, meter),
            UsagePeriod("2025-09-01", "2025-09-30", 60.0, meter)),
            observations = listOf(28L to 80.0, 21L to 114.0, 14L to 128.0, 7L to 142.0, 0L to 156.0)
                .map { (offset, value) -> Observation(last - offset * 86_400_000L, value, meter) })
        val robust = Estimator.robustDailyRate(data.observations)!!
        val actual = Estimator.estimate(data, last).daily!!
        println("issue52 robust=$robust estimateDaily=$actual")
        assertEquals("Seasonal estimate should resist the same erroneous endpoint as its robust slope", robust, actual, .000001)
    }
}
