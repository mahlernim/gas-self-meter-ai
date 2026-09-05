package dev.mahlernim.gasselfmeter

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/** Synthetic regression fixtures. All HTTP requests terminate on a loopback MockWebServer. */
class ProviderSafetyRegressionTest {
    private val account = GasappAccount("1", "synthetic-customer", "synthetic-contract", "Fixture", "N")
    private val session = GasappSession("synthetic-token", "synthetic-member", "synthetic-device")
    private val date = LocalDate.of(2026, 9, 5)
    private val meter = gasappHash("meter:1:synthetic-meter")
    private fun state() = JSONObject().put("useContractNum", account.contract).put("customerNum", account.customer)
        .put("selfInputAvailable", "Y").put("periodStart", "2026-09-01").put("periodEnd", "2026-09-05")
        .put("meterIdNum", "synthetic-meter").put("lastMonthIndicatorQty", 100).put("mtrDigitCnt", 5)
        .put("needChangeRegisteredChannel", "N").put("meterChange", JSONObject().put("changeYn", "N"))
        .put("inputYn", "N").put("selfInputYn", "N")
    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    private fun transport() = OkHttpClient.Builder().retryOnConnectionFailure(false)
        .followRedirects(false).followSslRedirects(false).callTimeout(3, TimeUnit.SECONDS).build()

    @Test fun gasappSubmissionMustNotReplayAfter503RetryAfterZero() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(json(state().toString()))
            server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
            server.enqueue(json("{\"inputYn\":\"Y\"}"))
            server.enqueue(json(state().put("inputYn", "Y").put("thisMonthIndicatorCustomer", 110).toString()))
            GasappApi(server.url("/api/"), transport(), session.deviceId).use { api ->
                api.submit(session, GasappApi.parseTarget(state(), account), 110.0, date)
            }
            val requests = List(server.requestCount) { server.takeRequest() }
            println("gasapp503 methods=" + requests.map { it.method } + " postCount=" + requests.count { it.method == "POST" })
            assertEquals("A submission may transmit only one POST", 1, requests.count { it.method == "POST" })
        }
    }

    @Test fun skensSubmissionMustNotReplayAfter503RetryAfterZero() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
            server.enqueue(json("{\"result\":\"N\"}"))
            SkensClient(Providers.get("busan"), Credentials("synthetic", "synthetic")).use { api ->
                val local = transport().newBuilder().addInterceptor { chain ->
                    chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build())
                }.build()
                SkensClient::class.java.getDeclaredField("client").apply { isAccessible = true }.set(api, local)
                val currentDate = today().toString()
                val target = SelfReadTarget("synthetic-cycle", currentDate, currentDate, true, false, null, 100.0,
                    Contract("synthetic-bp", "synthetic-ca"), "synthetic-meter", "", currentDate, "synthetic", "synthetic")
                api.submitReading(target, 110.0)
            }
            val requests = List(server.requestCount) { server.takeRequest() }
            println("skens503 paths=" + requests.map { it.path })
            assertEquals("Only one mutation is allowed, followed by a read-only receipt POST", listOf(
                "/busan/read/insertSelfRead.do", "/busan/read/call_EBPP_018.do"), requests.map { it.path })
        }
    }

    @Test fun unknownGasappSafetyFlagsAndDigitsMustBlockThePost() {
        val mutations = listOf<Pair<String, (JSONObject) -> JSONObject>>(
            "unknown inputYn" to { it.put("inputYn", "UNKNOWN") },
            "unknown channel status" to { it.put("needChangeRegisteredChannel", "UNKNOWN") },
            "unknown meter change" to { it.put("meterChange", JSONObject().put("changeYn", "UNKNOWN")) },
            "malformed digit count" to { it.put("mtrDigitCnt", "five") },
        )
        val transmitted = mutableListOf<String>()
        for ((label, mutate) in mutations) {
            MockWebServer().use { server ->
                server.start()
                val malformed = mutate(state())
                server.enqueue(json(malformed.toString()))
                server.enqueue(json("{\"inputYn\":\"N\"}"))
                GasappApi(server.url("/api/"), transport(), session.deviceId).use { api ->
                    runCatching { api.submit(session, GasappApi.parseTarget(malformed, account), 110.0, date) }
                }
                if (List(server.requestCount) { server.takeRequest() }.any { it.method == "POST" }) transmitted += label
            }
        }
        println("malformedGasappStatesThatTransmitted=" + transmitted)
        assertTrue("Unknown safety state must not authorize submission: $transmitted", transmitted.isEmpty())
    }

    @Test fun datedGasappBillWithoutRawUnitsOrMeterIdentityMustStayOutOfEstimator() {
        val target = GasappApi.parseTarget(state(), account)
        val source = AppData(profile = Profile("seoul", meter, account.key), ready = true)
        val bill = GasappBill("2026-08", 150.0, 10000.0, "2026-08-01", "2026-08-31")
        val merged = GasappBridge.merge(source, GasappConnection(session, account),
            GasappSnapshot(account, listOf(bill), emptyList(), target), dayStart(date))
        println("unverifiedBillPeriods=" + merged.periods)
        assertTrue("Dated billed usage alone establishes neither raw-volume units nor meter identity", merged.periods.isEmpty())
    }

    @Test fun duplicateGasappBillingMonthMustNotSilentlyDiscardACharge() {
        val rows = org.json.JSONArray().put(JSONObject().put("requestYm", "202608").put("chargeAmt", 10000))
            .put(JSONObject().put("requestYm", "202608").put("chargeAmt", 2500))
        var rejected = false
        val parsed = try { GasappApi.parseBills(rows) } catch (_: Exception) { rejected = true; emptyList() }
        println("duplicateBills rejected=$rejected retainedAmounts=" + parsed.map { it.amount })
        assertTrue("Ambiguous duplicate months must be rejected or represented without data loss", rejected || parsed.size == 2)
    }
}
