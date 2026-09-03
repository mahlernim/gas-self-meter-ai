package dev.mahlernim.gasselfmeter

import okhttp3.*
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.security.MessageDigest
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import kotlin.math.abs

data class Contract(val bp: String, val ca: String) { val label: String get() = "계약 ${ca.takeLast(4)}" }
data class SyncResult(val periods: List<UsagePeriod>, val meter: String, val planned: String?, val warning: String?)

/** Read-only port of the MIT-licensed ha-busan-city-gas protocol, with an explicit endpoint allowlist. */
class BusanClient(private val credentials: Credentials) : AutoCloseable {
    private val cookies = mutableListOf<Cookie>()
    private val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false)
        .cookieJar(object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, received: List<Cookie>) {
                received.forEach { cookie -> cookies.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }; cookies.add(cookie) }
            }
            override fun loadForRequest(url: HttpUrl) = cookies.filter { it.matches(url) && it.expiresAt > System.currentTimeMillis() }
        }).build()
    private val allowed = setOf("login/login.do", "login/loginProcess.do", "read/selfRead.do", "read/call_EBPP_018.do", "charge/askDetail.do")
    private fun request(path: String, data: Map<String, String>? = null): String {
        require(path in allowed)
        val builder = Request.Builder().url("https://ebpp.skens.com/busan/$path")
            .header("Referer", "https://ebpp.skens.com/busan/main/index.do")
        if (data != null) builder.post(FormBody.Builder().apply { data.forEach { (k, v) -> add(k, v) } }.build())
        return client.newCall(builder.build()).execute().use { response ->
            check(response.isSuccessful) { "도시가스 사이트에 연결하지 못했어요. 잠시 후 다시 시도해 주세요." }
            val body = response.body ?: error("조회 결과가 비어 있어요.")
            check(body.contentLength() <= 4_000_000) { "조회 결과가 예상보다 커요." }
            val bytes = body.byteStream().readBytesLimited(4_000_000)
            String(bytes, Charsets.UTF_8)
        }
    }
    fun login(): List<Contract> {
        request("login/login.do")
        val result = JSONObject(request("login/loginProcess.do", mapOf("id" to credentials.username, "pw" to credentials.password, "returnURL" to "/busan/read/selfRead.do")))
        check(result.optString("errCd") == "S") { "로그인하지 못했어요. 아이디와 비밀번호를 확인해 주세요." }
        return parseContracts(request("read/selfRead.do")).also { check(it.isNotEmpty()) { "연결된 계약이 없어요. 공급사 홈페이지에서 사용 계약을 확인해 주세요." } }
    }
    fun history(contract: Contract, progress: (String) -> Unit): SyncResult {
        val meterRows = JSONObject(request("read/call_EBPP_018.do", mapOf("CANO" to contract.ca, "BPNO" to contract.bp))).getJSONArray("list")
        check(meterRows.length() == 1) { "계량기가 여러 개이거나 없어요. 이번 버전에서는 직접 입력을 이용해 주세요." }
        val meterRow = meterRows.getJSONObject(0)
        val meterA = meterRow.optString("CERAET").takeIf { it.isNotBlank() }
        val meterB = meterRow.optString("GERAET").takeIf { it.isNotBlank() }
        check(meterA != null || meterB != null) { "계량기 정보 형식이 바뀌었어요." }
        check(meterA == null || meterB == null || meterA == meterB) { "계량기 정보가 일치하지 않아요." }
        val meter = opaque(meterA ?: meterB!!)
        val planned = meterRow.optString("ADATSOLL1").takeIf { it.isNotBlank() }?.let(::parsePortalDate)
        val params = mapOf("bpno" to contract.bp, "cano" to contract.ca, "compcd" to "C000", "GUBUN" to "02")
        val first = request("charge/askDetail.do", params + ("date" to ""))
        document(first)
        val selected = Jsoup.parse(first).selectFirst("#budat")?.attr("value")
        val months = (Regex("fnGetAskDetail\\([^)]*[\"'](20\\d{4})[\"']").findAll(first).map { it.groupValues[1] }.toSet() + listOfNotNull(selected?.takeIf { it.matches(Regex("20\\d{4}")) })).sortedDescending().take(25)
        check(months.isNotEmpty()) { "청구 이력을 찾지 못했어요. 공급사 페이지가 변경되었을 수 있어요." }
        val periods = mutableListOf<UsagePeriod>()
        var failed = 0
        months.forEachIndexed { i, month ->
            progress("청구 이력 ${i + 1}/${months.size} 가져오는 중")
            try {
                val html = if (Jsoup.parse(first).selectFirst("#budat")?.attr("value") == month) first else request("charge/askDetail.do", params + ("date" to month))
                periods += parseBill(html, month)
            } catch (_: Exception) { failed++ }
            Thread.sleep(250)
        }
        check(periods.isNotEmpty()) { "청구 이력을 읽지 못했어요. 사이트 변경 또는 일시적인 오류일 수 있어요." }
        Estimator.validatePeriods(periods)
        return SyncResult(periods.sortedBy { it.start }, meter, planned,
            if (failed > 0) "$failed 개월은 읽지 못했어요. 기존 이력은 유지했고, 다시 새로고침할 수 있어요." else null)
    }
    override fun close() { cookies.clear(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }

    companion object {
        fun parsePortalDate(value: String): String = LocalDate.parse(value.replace('.', '-').replace('/', '-'),
            if (value.matches(Regex("\\d{8}"))) java.time.format.DateTimeFormatter.BASIC_ISO_DATE else java.time.format.DateTimeFormatter.ISO_LOCAL_DATE).toString()
        fun opaque(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
        fun document(html: String): Document = Jsoup.parse(html).also {
            check(it.selectFirst("input[type=password]") == null) { "로그인이 만료됐어요. 다시 로그인해 주세요." }
            check(!it.title().contains("error", true)) { "도시가스 사이트에서 오류가 발생했어요." }
        }
        fun parseContracts(html: String): List<Contract> {
            val doc = document(html)
            val bp = Regex("BPNO\\s*:\\s*[\"'](\\d+)[\"']").find(html)?.groupValues?.get(1) ?: error("계약 정보 형식이 바뀌었어요.")
            return doc.select("input[id^=list_cano_]").map { node ->
                val ca = node.attr("value").also { check(it.matches(Regex("\\d+"))) }
                Contract(bp, ca)
            }.distinct()
        }
        private fun cells(row: Element) = row.children().filter { it.tagName() == "th" || it.tagName() == "td" }.map { it.text().trim() }
        fun parseBill(html: String, month: String): List<UsagePeriod> {
            val doc = document(html)
            check(month.matches(Regex("20\\d{4}")) && doc.selectFirst("#budat")?.attr("value") == month) { "청구월이 일치하지 않아요." }
            val tables = doc.select("table")
            check(tables.size >= 5) { "청구서 형식이 바뀌었어요." }
            val summary = tables[0].select("tr").map(::cells).filter { it.size == 2 }.associate { it[0] to it[1] }
            val amount = number(summary["합계"] ?: error("청구 금액을 찾지 못했어요."))
            var meter = ""
            var base = 0.0
            var energyCost = 0.0
            var adjusted = false
            val periods = mutableListOf<UsagePeriod>()
            for (row in tables[4].select("tr").map(::cells).filter { it.size == 11 }) {
                if (row[1].isNotBlank()) meter = opaque(row[1])
                val period = Regex("(\\d{2})\\.(\\d{2})~(\\d{2})\\.(\\d{2})").matchEntire(row[2])
                if (period != null && row[3].matches(Regex("[\\d,.]+")) && row[4].matches(Regex("[\\d,.]+"))) {
                    check(meter.isNotBlank())
                    val (sm, sd, em, ed) = period.destructured
                    val endYear = month.take(4).toInt() - if (em.toInt() > month.takeLast(2).toInt()) 1 else 0
                    val end = LocalDate.of(endYear, em.toInt(), ed.toInt())
                    val start = LocalDate.of(endYear - if (sm.toInt() > em.toInt()) 1 else 0, sm.toInt(), sd.toInt())
                    val previous = number(row[3]); val current = number(row[4]); val raw = current - previous
                    val volume = raw * number(row[5]); val heat = volume * number(row[7])
                    check(raw >= 0 && abs(volume - number(row[6])) < .0001 && abs(heat - number(row[8])) < .001) { "청구서의 사용량 계산이 맞지 않아요." }
                    periods += UsagePeriod(start.toString(), end.toString(), raw, meter, previous, current, month, amount)
                }
                if (row[0].contains("기본료") || row[0].contains("기본요금")) base += number(row[10])
                if (listOf("할인", "경감", "정산", "교체비").any { row[0].contains(it) }) adjusted = true
                if (row[8].matches(Regex("[\\d,.]+")) && row[9].matches(Regex("[\\d,.]+"))) energyCost += number(row[8]) * number(row[9])
            }
            check(periods.isNotEmpty()) { "사용 이력을 찾지 못했어요." }
            val totalUsage = periods.sumOf { it.usage }
            // Cost is a clearly labelled historical unit-cost approximation, including VAT.
            val rate = if (!adjusted && totalUsage > 0 && energyCost > 0) energyCost * 1.1 / totalUsage else null
            return periods.map { it.copy(unitCost = rate, baseCost = if (rate != null) base * 1.1 else null) }
        }
    }
}

fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        check(out.size() + count <= limit) { "파일 또는 응답이 너무 커요." }
        out.write(buffer, 0, count)
    }
    return out.toByteArray()
}
