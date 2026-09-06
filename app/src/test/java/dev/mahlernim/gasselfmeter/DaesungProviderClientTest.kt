package dev.mahlernim.gasselfmeter

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DaesungProviderClientTest {
    private fun response(body: String) = MockResponse().setHeader("Content-Type", "text/html; charset=utf-8").setBody(body)
    private fun login() = """<form method="post" action="/users/login"><input name="id"><input type="password" name="password"><input type="hidden" name="csrf" value="fresh"></form>"""
    private fun bills() = """<label for="cust">고객번호</label><select id="cust" name="cust"><option value="123" selected>우리집</option></select>
        <table><tr><th>청구월</th><th>청구금액</th><th>사용량</th><th>전월지침</th><th>당월지침</th></tr>
        <tr><td>2026-08</td><td>12,000원</td><td>9.8㎥</td><td>100</td><td>109.8</td></tr></table>"""
    private fun meter(path: String, submitted: String = ""): String {
        val received = if (submitted.isBlank()) "" else "<tr><th>등록지침</th><td>" + submitted + "</td></tr>"
        return """<table><tr><th>검침기간</th><td>2026-09-01 ~ 2026-09-30</td></tr><tr><th>전월지침</th><td>109.8</td></tr>
            <tr><th>계량기번호</th><td>M-1</td></tr>""" + received + """</table><form method="post" action="""" + path + """/save">
            <label for="cust">고객번호</label><select id="cust" name="cust"><option value="123" selected>우리집</option></select><input type="hidden" name="csrf" value="fresh">
            <label for="reading">당월지침</label><input id="reading" name="reading" value="">
            <button type="submit" name="mode" value="save">검침 등록</button></form>"""
    }

    @Test fun eachProviderCompletesSyntheticLoginImportSubmitAndReadback() {
        for ((id, path) in listOf("daesung" to "/consult/self", "daesungclean" to "/service/self_input")) MockWebServer().use { server ->
            server.start()
            listOf(login(), "<p>환영합니다</p>", bills(), bills(), meter(path), bills(), meter(path), "<p>등록되었습니다</p>", bills(), meter(path, "120"))
                .forEach { server.enqueue(response(it)) }
            DaesungProviderClient(id, Credentials("synthetic", "secret"), server.url("/")).use { client ->
                val contract = client.login().single()
                assertEquals("123", contract.id)
                val snapshot = client.read(contract)
                assertEquals("2026-08", snapshot.bills.single().month)
                assertEquals(9.8, snapshot.bills.single().usage)
                assertEquals("M-1", snapshot.target!!.serial)
                client.submit(contract, snapshot.target, 120.0)
            }
            val requests = List(10) { server.takeRequest() }
            assertEquals("/users/login", requests[0].path)
            assertEquals("POST", requests[1].method)
            val loginBody = requests[1].body.readUtf8()
            assertTrue(loginBody.contains("csrf=fresh"))
            assertEquals(1, Regex("(^|&)returl=").findAll(loginBody).count())
            assertEquals("/charge/month", requests[2].path)
            assertEquals("/charge/month?cust=123", requests[3].path)
            assertEquals(path + "?cust=123", requests[4].path)
            assertEquals("POST", requests[7].method)
            assertEquals(path + "/save", requests[7].path)
            val sent = requests[7].body.readUtf8()
            assertTrue(sent.contains("cust=123") && sent.contains("csrf=fresh") && sent.contains("mode=save") && sent.contains("reading=120"))
        }
    }

    @Test fun paymentMeterFormNeverSendsAReading() = MockWebServer().use { server ->
        server.start()
        listOf(login(), "<p>ok</p>", bills(), bills(), meter("/consult/self").replace("/consult/self/save", "/charge/payment"))
            .forEach { server.enqueue(response(it)) }
        DaesungProviderClient("daesung", Credentials("synthetic", "secret"), server.url("/")).use { client ->
            val contract = client.login().single()
            assertTrue(!client.read(contract).target!!.eligible)
        }
        assertEquals(5, server.requestCount)
    }

    @Test fun relativeActionAndEmptyLedgerAreSupportedWithoutBroadeningActionScope() = MockWebServer().use { server ->
        server.start()
        val empty = """<label for="cust">고객번호</label><select id="cust" name="cust"><option value="123" selected>우리집</option></select><table><tr><th>청구월</th><th>청구금액</th></tr></table>"""
        listOf(login(), "<p>ok</p>", bills(), empty,
            meter("/consult/self").replace("/consult/self/save", "self/save"),
        ).forEach { server.enqueue(response(it)) }
        DaesungProviderClient("daesung", Credentials("synthetic", "secret"), server.url("/")).use { client ->
            val contract = client.login().single()
            val snapshot = client.read(contract)
            assertTrue(snapshot.bills.isEmpty())
            assertTrue(snapshot.target!!.eligible)
        }
        assertEquals(5, server.requestCount)
    }

    @Test fun decimalReadingStopsBeforeAnySubmissionRequest() = MockWebServer().use { server ->
        server.start()
        listOf(login(), "<p>ok</p>", bills(), bills(), meter("/consult/self")).forEach { server.enqueue(response(it)) }
        DaesungProviderClient("daesung", Credentials("synthetic", "secret"), server.url("/")).use { client ->
            val contract = client.login().single()
            val target = client.read(contract).target!!
            assertTrue(runCatching { client.submit(contract, target, 120.5) }.isFailure)
        }
        assertEquals(5, server.requestCount)
    }

    @Test fun malformedOrNegativeOfficialReadingStopsBeforeSubmission() {
        for (bad in listOf("-10", "1 / 2")) MockWebServer().use { server ->
            server.start()
            listOf(login(), "<p>ok</p>", bills(), bills(), meter("/consult/self").replace("<td>109.8</td>", "<td>" + bad + "</td>"))
                .forEach { server.enqueue(response(it)) }
            DaesungProviderClient("daesung", Credentials("synthetic", "secret"), server.url("/")).use { client ->
                val contract = client.login().single()
                assertTrue(runCatching { client.read(contract) }.isFailure)
            }
            assertEquals(5, server.requestCount)
        }
    }
}
