package dev.mahlernim.gasselfmeter

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class GasappApiTest {
    private val session = GasappSession("secret-token", "member", "device")
    private val account = GasappAccount("1", "customer", "contract", "우리집", "Y")
    private val targetJson = """{"useContractNum":"contract","customerNum":"customer","selfInputAvailable":"Y","periodStart":"2026-09-13","periodEnd":"2026-09-18","meterIdNum":"meter","lastMonthIndicatorQty":30,"mtrDigitCnt":5}"""
    private fun withApi(block: (MockWebServer, GasappApi) -> Unit) {
        MockWebServer().use { server ->
            server.start()
            GasappApi(server.url("/api/"), OkHttpClient.Builder().retryOnConnectionFailure(false).followRedirects(false).build(), "device").use { block(server, it) }
        }
    }
    private fun response(json: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(json)

    @Test fun smsThenMemberUsesExplicitConsentAndPrivateHeaders() = withApi { server, api ->
        server.enqueue(response("""{"requestNo":"r","responseUniqId":"u"}"""))
        server.enqueue(response("""{"ci":"ci","di":"di"}"""))
        server.enqueue(response("""{"member":"member","token":"secret-token"}"""))
        val person = GasappIdentity("홍길동", "01012345678", "900101", "1", "1")
        val terms = listOf(GasappTerms("본인인증 약관 SKT", "required"), GasappTerms("member", "required"), GasappTerms("query", "required"))
        val challenge = api.requestSms(person, terms)
        val active = api.confirmSms(person, challenge, "123456")
        assertEquals("secret-token", active.token)
        val sms = server.takeRequest()
        assertEquals("/api/extern/auth/nice/sms/request", sms.path)
        assertEquals("", sms.getHeader("X-TOKEN"))
        assertEquals("900101", JSONObject(sms.body.readUtf8()).getString("birthday"))
        assertEquals("/api/extern/auth/nice/sms/confirm", server.takeRequest().path)
        val member = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("N", member.getString("marketingAcceptance"))
        assertEquals("19900101", member.getString("birthDate"))
        assertFalse(active.toString().contains("secret-token"))
        assertFalse(person.toString().contains("010"))
    }

    @Test fun noSmsWithoutConsent() = withApi { server, api ->
        assertThrows(IllegalArgumentException::class.java) {
            api.requestSms(GasappIdentity("홍길동", "01012345678", "900101", "1", "1"), emptyList())
        }
        assertEquals(0, server.requestCount)
    }

    @Test fun accountsPreserveCompanyAndAmi() = withApi { server, api ->
        server.enqueue(response("""{"contracts":[{"company":1,"customerNum":"customer","useContractNum":"contract","amiYn":"Y"},{"company":9,"customerNum":"customer","useContractNum":"contract","amiYn":"N"}]}"""))
        val accounts = api.accounts(session)
        assertEquals(2, accounts.size)
        assertNotEquals(accounts[0].key, accounts[1].key)
        assertEquals("Y", accounts[0].ami)
        assertEquals("0", server.takeRequest().getHeader("X-COMPANY"))
    }

    @Test fun expiredSessionIsNotSilentlyRetried() = withApi { server, api ->
        server.enqueue(MockResponse().setResponseCode(418))
        assertThrows(GasappAuthExpired::class.java) { api.accounts(session) }
        assertEquals(1, server.requestCount)
    }

    @Test fun submitConfirmsOnlySameMeterPeriodAndValue() = withApi { server, api ->
        val expected = GasappApi.parseTarget(JSONObject(targetJson), account)
        server.enqueue(response(targetJson))
        server.enqueue(response("""{"inputYn":"Y"}"""))
        server.enqueue(response(JSONObject(targetJson).put("inputYn", "Y").put("thisMonthIndicatorCustomer", 36).toString()))
        val result = api.submit(session, expected, 36.0, LocalDate.of(2026, 9, 18))
        assertEquals(GasappSubmitStatus.CONFIRMED, result.status)
        assertEquals("GET", server.takeRequest().method)
        val post = server.takeRequest()
        assertEquals("POST", post.method)
        assertEquals("36", JSONObject(post.body.readUtf8()).getString("thisMonthIndicatorCustomer"))
        assertEquals("GET", server.takeRequest().method)
    }

    @Test fun successfulResponseWithoutValueIsUncertain() = withApi { server, api ->
        val expected = GasappApi.parseTarget(JSONObject(targetJson), account)
        server.enqueue(response(targetJson))
        server.enqueue(response("""{"inputYn":"Y"}"""))
        server.enqueue(response(JSONObject(targetJson).put("inputYn", "Y").toString()))
        assertEquals(GasappSubmitStatus.UNCERTAIN, api.submit(session, expected, 36.0, LocalDate.of(2026, 9, 18)).status)
        assertEquals(3, server.requestCount)
    }

    @Test fun changedMeterBlocksPost() = withApi { server, api ->
        server.enqueue(response(JSONObject(targetJson).put("meterIdNum", "another").toString()))
        assertThrows(IllegalArgumentException::class.java) {
            api.submit(session, GasappApi.parseTarget(JSONObject(targetJson), account), 36.0, LocalDate.of(2026, 9, 18))
        }
        assertEquals(1, server.requestCount)
    }

    @Test fun replacementFlagChangingAfterConfirmationBlocksPost() = withApi { server, api ->
        server.enqueue(response(JSONObject(targetJson).put("meterChange", JSONObject().put("changeYn", "Y")).toString()))
        assertThrows(IllegalArgumentException::class.java) {
            api.submit(session, GasappApi.parseTarget(JSONObject(targetJson), account), 36.0, LocalDate.of(2026, 9, 18))
        }
        assertEquals(1, server.requestCount)
    }

    @Test fun lostPostResponseReconcilesWithoutSecondPost() = withApi { server, api ->
        server.enqueue(response(targetJson))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.enqueue(response(JSONObject(targetJson).put("inputYn", "Y").put("thisMonthIndicatorCustomer", 36).toString()))
        val expected = GasappApi.parseTarget(JSONObject(targetJson), account)
        assertEquals(GasappSubmitStatus.CONFIRMED, api.submit(session, expected, 36.0, LocalDate.of(2026, 9, 18)).status)
        assertEquals(listOf("GET", "POST", "GET"), List(3) { server.takeRequest().method })
        assertEquals(3, server.requestCount)
    }

    @Test fun successfulHttpWithoutReceiptIsNotRejectedOrConfirmed() = withApi { server, api ->
        server.enqueue(response(targetJson))
        server.enqueue(response("{}"))
        server.enqueue(response(targetJson))
        assertEquals(GasappSubmitStatus.UNCERTAIN, api.submit(session, GasappApi.parseTarget(JSONObject(targetJson), account),
            36.0, LocalDate.of(2026, 9, 18)).status)
    }

    @Test fun snapshotUsesActualAmiAndWorksBeforeSelfReadingRegistration() = withApi { server, api ->
        server.enqueue(response("""{"cards":{}}"""))
        server.enqueue(response("""[{"requestYm":"2026-08","useQty":12,"chargeAmtQty":14000}]"""))
        server.enqueue(response("""[{"meterIdNum":"meter"}]"""))
        server.enqueue(response("null"))
        val snapshot = api.snapshot(session, account)
        assertTrue(server.takeRequest().path!!.contains("amiYn=Y"))
        assertFalse(snapshot.target.registered)
        assertNotNull(snapshot.target.meter)
        assertEquals(12.0, snapshot.bills.single().usage!!, 0.0)
        assertTrue(snapshot.readings.isEmpty())
        assertEquals(4, server.requestCount)
    }

    @Test fun decimalsAndOutOfWindowNeverSubmit() = withApi { server, api ->
        repeat(2) { server.enqueue(response(targetJson)) }
        val expected = GasappApi.parseTarget(JSONObject(targetJson), account)
        assertThrows(IllegalArgumentException::class.java) { api.submit(session, expected, 36.7, LocalDate.of(2026, 9, 18)) }
        assertThrows(IllegalArgumentException::class.java) { api.submit(session, expected, 36.0, LocalDate.of(2026, 9, 19)) }
        repeat(2) { assertEquals("GET", server.takeRequest().method) }
    }

    @Test fun historyUsesInclusiveSixthRowCursorWithoutLosingIt() = withApi { server, api ->
        val rows = (1..6).joinToString(",") { """{"id":"$it","gmtrJobYmd":"2026-08-${it.toString().padStart(2, '0')}","thisMonthIndicator":$it}""" }
        server.enqueue(response("[$rows]"))
        server.enqueue(response("""[{"id":"6","gmtrJobYmd":"2026-08-06","thisMonthIndicator":6}]"""))
        assertEquals(6, api.history(session, account).size)
        server.takeRequest()
        assertTrue(server.takeRequest().path!!.contains("lastId=6"))
    }

    @Test fun malformedAmountsDoNotBecomeZero() {
        assertThrows(IllegalArgumentException::class.java) { GasappApi.parseBills(JSONObject("""{"history":[{"requestYm":"202608","useQty":"NaN"}]}""")) }
        val bill = GasappApi.parseBills(JSONObject("""{"history":[{"requestYm":"202608","chargeAmtQty":"1,234"}]}""" )).single()
        assertNull(bill.usage)
        assertEquals(1234.0, bill.amount!!, 0.0)
    }
}
