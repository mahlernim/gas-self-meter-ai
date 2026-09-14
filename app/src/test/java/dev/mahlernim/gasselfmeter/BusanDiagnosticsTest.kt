package dev.mahlernim.gasselfmeter

import org.junit.Assert.*
import org.junit.Test
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class BusanDiagnosticsTest {
    private val target = SelfReadTarget(
        cycle = "2026-09-01:2026-09-05:meter", start = "2026-09-01", end = "2026-09-05",
        eligible = true, submitted = false, submittedValue = null, previousValue = 100.0,
        contract = Contract("bp", "ca"), serial = "meter", address = "", planned = "20260905",
        vLdo = "v", installation = "installation"
    )

    @Test fun formEvidenceKeepsOnlyStaticValidationStructure() {
        val evidence = SkensClient.analyzeSelfReadForm("""
            <script>
              function submit() {
                var input = document.getElementById('cust_readingresult').value;
                var address = data.GERAET_ADDR;
                if (isNaN(parseInt(input, 10))) alert('검침값을 입력해 주세요');
                insertSelfRead({}, function(data) { if (data.result == 'Y') alert('저장하였습니다.'); else if (data.result == 'N') fail(); });
              }
            </script>
        """.trimIndent())
        assertTrue(evidence.usesIsNaN)
        assertTrue(evidence.usesParseInt)
        assertTrue(evidence.validatesReading)
        assertTrue(evidence.acceptsY)
        assertTrue(evidence.rejectsN)
        assertEquals("저장하였습니다.", evidence.genericAlertMessage)
        assertEquals(SkensClient.opaque("저장하였습니다."), evidence.genericAlertHash)
        assertTrue(evidence.mapsGeraetAddr)
        assertFalse(evidence.mapsLegacyAddr)
    }

    @Test fun formEvidenceExportsOnlyBoundedReadStructure() {
        val evidence = SkensClient.analyzeSelfReadForm("""
            <script src="/js/ebpp_common.js"></script>
            <script src="https://ebpp.skens.com/busan/js/read.js"></script>
            <script src="https://other.example/tracker.js?account=private"></script>
            <script>
              var getMeterResult = function() {
                var current = $('#cust_readingresult').val();
                $.post('/busan/read/call_EBPP_099.do', { BPNO: value, CANO: value, pw: hidden, token: secret });
                $.post('read/askCurrent.do', { RESULT: value });
                $.post('call_EBPP_018.do', { CUST_READING_RESULT: value });
                $.post('/busan/read/insertSelfRead.do', { CUST_READING_RESULT: value });
              }
            </script>
        """.trimIndent())
        assertEquals(setOf("/busan/read/call_EBPP_099.do", "/read/askCurrent.do", "/read/call_EBPP_018.do"), evidence.readRoutes)
        assertEquals(setOf("BPNO", "CANO", "RESULT", "CUST_READING_RESULT"), evidence.readParameterKeys)
        assertEquals(setOf("cust_readingresult"), evidence.readDomIds)
        assertEquals(setOf("getMeterResult"), evidence.readFunctionNames)
        assertEquals(setOf("/js/ebpp_common.js", "/busan/js/read.js"), evidence.scriptPaths)
    }

    @Test fun reconciliationAcceptsDelayedMatchingReadbackAndFlagsEveryIdentityMismatch() {
        val delayed = target.copy(submitted = true, submittedValue = 110.0)
        assertTrue(SkensClient.reconciliation(target, delayed, 110.0).confirmed)
        val mismatch = target.copy(submitted = true, submittedValue = 111.0,
            contract = Contract("other-bp", "other-ca"), serial = "other-meter",
            cycle = "other-cycle", start = "2026-09-02", end = "2026-09-06",
            planned = "other-plan", installation = "other-installation")
        val result = SkensClient.reconciliation(target, mismatch, 110.0)
        assertFalse(result.confirmed)
        assertTrue(result.contractMismatch)
        assertTrue(result.meterMismatch)
        assertTrue(result.cycleMismatch)
        assertTrue(result.datesMismatch)
        assertTrue(result.plannedMismatch)
        assertTrue(result.installationMismatch)
        assertTrue(result.valueMismatch)
    }

    @Test fun targetAddressUsesGeraetAddrThenLegacyAddrAndRejectsConflicts() {
        val date = today().toString().replace("-", "")
        fun row() = JSONObject().put("START_DATE", date).put("END_DATE", date)
            .put("CERAET", "synthetic-meter").put("selfReadYn", "Y").put("SELF_READ_YN", "N")
            .put("LAST_READINGRESULT", 27).put("ADATSOLL1", "plan").put("V_LDO", "v").put("ANLAGE", "installation")
        fun parse(source: JSONObject): SelfReadTarget? = SkensClient(Providers.get("busan"), Credentials("synthetic", "synthetic")).use { client ->
            val method = SkensClient::class.java.getDeclaredMethod("parseSelfReadTarget", JSONObject::class.java, Contract::class.java)
                .apply { isAccessible = true }
            method.invoke(client, source, Contract("bp", "ca")) as? SelfReadTarget
        }
        assertEquals("primary", parse(row().put("GERAET_ADDR", "primary"))?.address)
        assertEquals("legacy", parse(row().put("ADDR", "legacy"))?.address)
        assertEquals("same", parse(row().put("GERAET_ADDR", "same").put("ADDR", "same"))?.address)
        assertTrue(runCatching { parse(row().put("GERAET_ADDR", "primary").put("ADDR", "other")) }.isFailure)
    }

    @Test fun onePostUsesExactWireLiteralAndConfirmsOnDelayedReadback() {
        val date = today().toString()
        val serial = "synthetic-meter"
        val liveTarget = target.copy(cycle = "$date:$date:${SkensClient.opaque(serial)}", start = date, end = date,
            serial = serial, planned = "plan")
        fun meter(submitted: String?) = JSONObject().put("list", JSONArray().put(JSONObject()
            .put("START_DATE", date.replace("-", "")).put("END_DATE", date.replace("-", ""))
            .put("CERAET", serial).put("selfReadYn", "Y").put("SELF_READ_YN", if (submitted == null) "N" else "Y")
            .put("GERAET_ADDR", "synthetic address")
            .put("LAST_READINGRESULT", 100).put("ADATSOLL1", "plan").put("V_LDO", "v").put("ANLAGE", "installation")
            .apply { if (submitted != null) put("CUST_READING_RESULT", submitted) }))
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("{\"result\":\"Y\"}"))
            server.enqueue(MockResponse().setBody(meter(null).toString()))
            server.enqueue(MockResponse().setBody(meter("110").toString()))
            server.enqueue(MockResponse().setBody(meter("110").toString()))
            val trace = mutableListOf<BusanTraceEvent>()
            SkensClient(Providers.get("busan"), Credentials("synthetic", "synthetic"), trace::add, {}).use { api ->
                val local = OkHttpClient.Builder().retryOnConnectionFailure(false).callTimeout(3, TimeUnit.SECONDS)
                    .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build()) }.build()
                SkensClient::class.java.getDeclaredField("client").apply { isAccessible = true }.set(api, local)
                val outcome = api.submitReading(liveTarget, 110.0)
                assertTrue(outcome.accepted)
                assertTrue(outcome.confirmed)
                assertTrue(outcome.responseReceived)
            }
            assertEquals("110", trace.single { it.stage == "submit_dispatch" }.requestWireValue)
            assertEquals("Y", trace.single { it.stage == "submit_response" && it.httpCode == 200 }.serverResultCode)
            assertEquals(3, server.requestCount)
            assertEquals(1, List(server.requestCount) { server.takeRequest() }.count { it.path?.contains("insertSelfRead") == true })
        }
    }

    @Test fun portalYReturnsImmediatelyWithoutReceiptReadsInProductionPath() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("{\"result\":\"Y\"}"))
            val date = today().toString()
            val current = target.copy(start = date, end = date, planned = "plan")
            SkensClient(Providers.get("busan"), Credentials("synthetic", "synthetic")).use { api ->
                val local = OkHttpClient.Builder().retryOnConnectionFailure(false).callTimeout(3, TimeUnit.SECONDS)
                    .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build()) }.build()
                SkensClient::class.java.getDeclaredField("client").apply { isAccessible = true }.set(api, local)
                val outcome = api.submitReading(current, 110.0)
                assertTrue(outcome.accepted)
                assertFalse(outcome.confirmed)
                assertEquals("confirmed", outcome.status)
                assertEquals("provider_response", outcome.confirmationSource)
            }
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun diagnosticYWithStaleReceiptsKeepsProviderConfirmationSeparate() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("{\"result\":\"Y\"}"))
            repeat(3) { server.enqueue(MockResponse().setBody("{\"list\":[]}")) }
            val date = today().toString()
            val current = target.copy(start = date, end = date, planned = "plan")
            SkensClient(Providers.get("busan"), Credentials("synthetic", "synthetic"), observer = {}, verificationPause = {}).use { api ->
                val local = OkHttpClient.Builder().retryOnConnectionFailure(false).callTimeout(3, TimeUnit.SECONDS)
                    .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build()) }.build()
                SkensClient::class.java.getDeclaredField("client").apply { isAccessible = true }.set(api, local)
                val outcome = api.submitReading(current, 110.0)
                assertTrue(outcome.accepted)
                assertFalse(outcome.confirmed)
                assertEquals("confirmed", outcome.status)
                assertEquals("provider_response", outcome.confirmationSource)
            }
            assertEquals(4, server.requestCount)
        }
    }
}
