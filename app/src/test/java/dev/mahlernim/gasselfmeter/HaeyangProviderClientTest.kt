package dev.mahlernim.gasselfmeter

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.URLDecoder
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger

class HaeyangProviderClientTest {
    @Test fun loginDoesNotReplayWhenServerAsksForImmediateRetry() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
            server.enqueue(MockResponse().setResponseCode(500))
            server.start()
            HaeyangProviderClient(Credentials("synthetic-user", "synthetic-password"), baseUrl = server.url("/bizmob/")).use { client ->
                assertThrows(ProviderFailure::class.java) { client.login() }
            }
            assertEquals(1, server.requestCount)
        }
    }
    private val date = LocalDate.of(2026, 9, 6)
    private fun reply(body: JSONObject) = MockResponse().setHeader("Content-Type", "application/json; charset=utf-8").setBody(body.toString())
    private fun envelope(body: JSONObject = JSONObject()) = JSONObject().put("header", JSONObject().put("result", true)).put("body", body)
    private fun failure(code: String) = JSONObject().put("header", JSONObject().put("result", false).put("error_code", code)).put("body", JSONObject())
    private fun message(request: RecordedRequest): JSONObject {
        assertEquals("POST", request.method)
        assertTrue(request.path!!.endsWith(".json"))
        val form = URLDecoder.decode(request.body.clone().readUtf8(), "UTF-8")
        return JSONObject(form.substringAfter("message="))
    }
    private fun code(request: RecordedRequest) = message(request).getJSONObject("header").getString("trcode")
    private fun loginBody() = envelope(JSONObject().put("legacy_message", envelope(JSONObject().put("payerList", org.json.JSONArray().put(JSONObject()
        .put("PAYERNO", "payer-1").put("PAYERNM", "합성 고객"))))))
    private fun contractBody() = envelope(JSONObject().put("payerList", org.json.JSONArray().put(JSONObject()
        .put("PAYERNO", "payer-1").put("RETCODE", "00").put("ADDRESS", "합성 주소").put("INSTALLNO", "meter-1"))))
    private fun billsBody() = envelope(JSONObject().put("IT_TAB", org.json.JSONArray().put(JSONObject()
        .put("YEARMONTH", "202608").put("NOTICE_AMT", "123.45").put("CONSUME_QTY", "11.2").put("NOTICENO", "notice-1"))))
    private fun detailBody() = envelope(JSONObject().put("IT_TAB", org.json.JSONArray().put(JSONObject()
        .put("USE_PERIOD_FROM", "20260701").put("USE_PERIOD_TO", "20260731").put("PREV_INDCT", "100").put("CURR_INDCT", "111.2").put("CONSUME_QTY", "11.2").put("INSTALLNO", "retired-meter"))))
    private fun meter(order: String = "order-1", accepted: String? = null): JSONObject {
        val row = JSONObject().apply {
            put("INSTALLNO", "meter-1")
            put("PAYMENTDATE", "B")
            put("METERDATE", "20260815")
            put("PREV_INDCT", "100")
            put("MTORDERNO", order)
            if (accepted != null) {
                put("METERDATE_CM", "20260906")
                put("NREVB_INDCT", accepted)
            }
        }
        return envelope(JSONObject().put("RTNCD", if (accepted == null) "00" else "01")
            .put("IT_TAB", org.json.JSONArray().put(row)))
    }

    @Test fun loginReadImportsValidatedBillsAndStableMeterTarget() = MockWebServer().use { server ->
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = when (code(request)) {
                "LOGIN" -> reply(loginBody()); "MYPAGE3" -> reply(contractBody()); "BILL001" -> reply(billsBody()); "BILL002" -> reply(detailBody()); "SELF100" -> reply(meter())
                else -> MockResponse().setResponseCode(500)
            }
        }
        server.start()
        HaeyangProviderClient(Credentials("synthetic-user", "synthetic-password"), baseUrl = server.url("/bizmob/"), now = { date }).use { client ->
            val contract = client.login().single()
            assertEquals(DirectContract("payer-1", "합성 주소", "meter-1"), contract)
            val snapshot = client.read(contract)
            assertEquals(1, snapshot.bills.size)
            assertEquals("2026-08", snapshot.bills.single().month)
            assertEquals(12345.0, snapshot.bills.single().amount!!, .001)
            assertEquals(111.2, snapshot.bills.single().current!!, .001)
            assertEquals("meter-1", snapshot.contract.meterId)
            assertEquals("retired-meter", snapshot.bills.single().meterId)
            assertEquals("2026-09-06", snapshot.target!!.start)
            assertEquals("2026-09-10", snapshot.target.end)
            assertEquals(Contract("payer-1", "haeyang", "합성 주소"), snapshot.target.contract)
            assertEquals(DirectIdentity.meter("haeyang", "payer-1", "meter-1"), snapshot.target.installation)
            assertFalse(snapshot.target.cycle.contains("2026-09-06"))
        }
    }

    @Test fun billQueryUsesExactlyTwentyFourMonthsAndRejectsUndiscoveredContract() {
        MockWebServer().use { server ->
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (code(request)) {
                "LOGIN" -> reply(loginBody()); "MYPAGE3" -> reply(contractBody())
                "BILL001" -> {
                    val body = message(request).getJSONObject("body")
                    assertEquals("202410", body.getString("FROMYM")); assertEquals("202609", body.getString("TOYM"))
                    reply(billsBody())
                }
                "BILL002" -> reply(detailBody()); "SELF100" -> reply(meter())
                else -> MockResponse().setResponseCode(500)
            }
        }
        server.start()
        HaeyangProviderClient(Credentials("user", "password"), baseUrl = server.url("/bizmob/"), now = { date }).use { client ->
            val contract = client.login().single()
            client.read(contract)
            assertThrows(ProviderFailure::class.java) { client.read(contract.copy(id = "other-payer")) }
        }
        }
    }

    @Test fun submitUsesFreshMatchingOrderOnceThenRequiresReadbackReceipt() = MockWebServer().use { server ->
        val meterCalls = AtomicInteger()
        val writes = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (code(request)) {
                "LOGIN" -> reply(loginBody()); "MYPAGE3" -> reply(contractBody()); "BILL001" -> reply(billsBody()); "BILL002" -> reply(detailBody())
                "SELF100" -> reply(if (meterCalls.incrementAndGet() == 3) meter(accepted = "101") else meter())
                "SELF101" -> { writes.incrementAndGet(); reply(envelope()) }
                else -> MockResponse().setResponseCode(500)
            }
        }
        server.start()
        HaeyangProviderClient(Credentials("user", "password"), baseUrl = server.url("/bizmob/"), now = { date }).use { client ->
            val contract = client.login().single()
            val target = client.read(contract).target!!
            client.submit(contract, target, 101.0)
        }
        assertEquals(1, writes.get())
    }

    @Test fun changedFreshOrderStopsBeforeMutationAndLoginEnvelopeMapsToAuthentication() = MockWebServer().use { server ->
        val meterCalls = AtomicInteger()
        val writes = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (code(request)) {
                "LOGIN" -> reply(loginBody()); "MYPAGE3" -> reply(contractBody()); "BILL001" -> reply(billsBody()); "BILL002" -> reply(detailBody())
                "SELF100" -> reply(if (meterCalls.incrementAndGet() == 2) meter("new-order") else meter())
                "SELF101" -> { writes.incrementAndGet(); reply(envelope()) }
                else -> MockResponse().setResponseCode(500)
            }
        }
        server.start()
        HaeyangProviderClient(Credentials("user", "password"), baseUrl = server.url("/bizmob/"), now = { date }).use { client ->
            val contract = client.login().single(); val target = client.read(contract).target!!
            val failure = assertThrows(ProviderFailure::class.java) { client.submit(contract, target, 101.0) }
            assertEquals("submit", failure.stage)
        }
        assertEquals(0, writes.get())
        MockWebServer().use { auth ->
            auth.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = reply(failure("LOGIN01_INVALID")) }
            auth.start()
            HaeyangProviderClient(Credentials("user", "password"), baseUrl = auth.url("/bizmob/"), now = { date }).use { client ->
                val failure = assertThrows(ProviderFailure::class.java) { client.login() }
                assertEquals("login", failure.stage); assertEquals("authentication", failure.category)
            }
        }
    }
}
