package dev.mahlernim.gasselfmeter

import okhttp3.Authenticator
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.time.LocalDate
import java.time.YearMonth
import java.util.concurrent.TimeUnit
import kotlin.math.round

/**
 * Haeyang mobile-web adapter. The documented WEB transport returns the supplied
 * JSON through getDecAES only for the reviewed public-script fingerprints. Native
 * encrypted payloads are deliberately rejected rather than guessed.
 */
class HaeyangProviderClient internal constructor(
    private val credentials: Credentials,
    transport: OkHttpClient = readOnlyHttpClient(),
    private val baseUrl: HttpUrl = DEFAULT_BASE.toHttpUrl(),
    private val now: () -> LocalDate = ::today,
) : DirectProviderClient {
    private val cookies = mutableListOf<Cookie>()
    private val discovered = mutableMapOf<String, DirectContract>()
    private val client = transport.newBuilder()
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS)
        .cookieJar(object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, values: List<Cookie>) {
                if (!sameOrigin(url)) return
                synchronized(cookies) { values.forEach { value ->
                    cookies.removeAll { it.name == value.name && it.domain == value.domain && it.path == value.path }
                    cookies += value
                } }
            }
            override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(cookies) {
                if (!sameOrigin(url)) emptyList() else cookies.filter { it.matches(url) && it.expiresAt > System.currentTimeMillis() }
            }
        }).build()
    private var loggedIn = false

    init {
        require((baseUrl.scheme == "https" || baseUrl.host in setOf("localhost", "127.0.0.1")) && baseUrl.encodedPath.endsWith("/bizmob/")) {
            "해양에너지 연결 주소를 확인해 주세요."
        }
    }

    override fun login(): List<DirectContract> = at("login") {
        discovered.clear()
        val legacy = envelope("LOGIN01", JSONObject().apply {
            put("userId", credentials.username)
            put("userPwdAsMD5", digest("MD5", credentials.password))
            put("userPwdAsSHA2", digest("SHA-256", credentials.password))
        })
        val body = request("LOGIN", JSONObject().apply {
            put("user_id", ""); put("password", ""); put("os_type", "mobileweb")
            put("legacy_message", legacy); put("legacy_trcode", "LOGIN01")
            put("emulator_flag", true); put("device_id", ""); put("app_key", "HYCGANP0")
            put("phone_number", ""); put("manual_phone_number", false)
        }, "login", oneShot = true)
        val login = decodeLegacy(body.opt("legacy_message"), "login")
        val payers = rows(login, "payerList", "login")
        loggedIn = true
        val contracts = payers.map { row ->
            val id = required(row, "PAYERNO", "contracts")
            val name = optional(row, "PAYERNM").orEmpty()
            val detail = rows(call("MYPAGE3", JSONObject().put("payerList", JSONArray().put(JSONObject().apply {
                put("I_CUSTCG", if (row.optBoolean("isBusinessCust")) "2" else "1")
                put("I_PAYERNM", name); put("I_PAYERNO", id); put("PAYERNM", name); put("PAYERNO", id)
            })), "contracts"), "payerList", "contracts").singleOrNull()
                ?: fail("contracts", "parse")
            check(optional(detail, "PAYERNO") in setOf(null, id) && optional(detail, "RETCODE") in setOf(null, "00"))
            DirectContract(id, optional(detail, "ADDRESS") ?: "해양에너지 계약", optional(detail, "INSTALLNO") ?: optional(row, "INSTALLNO"))
        }
        check(contracts.isNotEmpty() && contracts.distinctBy { it.id }.size == contracts.size)
        contracts.also { values -> values.forEach { discovered[it.id] = it } }
    }

    override fun read(contract: DirectContract): DirectSnapshot = at("bills") {
        account(contract)
        val bills = bills(contract)
        // MYPAGE3 identifies the current installation. Older detailed bills can belong to a
        // retired meter, so use them only when the account response has no meter identity.
        val meterId = contract.meterId ?: bills.asReversed().mapNotNull { it.meterId }.firstOrNull()
        val updated = contract.copy(meterId = meterId)
        DirectSnapshot(updated, bills, target(updated))
    }

    override fun submit(contract: DirectContract, target: SelfReadTarget, value: Double) = at("submit") {
        account(contract)
        val fresh = target(contract) ?: fail("submit", "validation")
        val installation = DirectIdentity.meter("haeyang", contract.id, contract.meterId)
        check(target.contract.bp == contract.id && target.contract.ca == "haeyang" && target.installation == installation)
        check(fresh.cycle == target.cycle && fresh.previousValue == target.previousValue && fresh.serial == target.serial)
        check(fresh.eligible && !fresh.submitted && now() in LocalDate.parse(fresh.start)..LocalDate.parse(fresh.end))
        check(value.isFinite() && value == kotlin.math.floor(value) && value in (fresh.previousValue ?: Double.POSITIVE_INFINITY)..99_999_999.0)
        val order = fresh.serial.substringAfterLast(':').takeIf { it.isNotBlank() } ?: fail("submit", "parse")
        request("SELF101", JSONObject().apply { put("CURR_INDCT", value.toLong().toString()); put("MTORDERNO", order); put("IFFLAG", "W") }, "submit", oneShot = true)
        val confirmed = target(contract) ?: fail("submit", "parse")
        check(confirmed.cycle == fresh.cycle && confirmed.submitted && confirmed.submittedValue == value) { "해양에너지 제출 결과를 다시 확인해 주세요." }
    }

    private fun bills(contract: DirectContract): List<DirectBill> {
        val date = now()
        val summary = rows(call("BILL001", JSONObject().apply {
            put("actualBillingCheck", true); put("checkPeriod", "MONTH")
            val from = YearMonth.from(date).minusMonths(23)
            put("FROMYM", "%04d%02d".format(from.year, from.monthValue)); put("TOYM", "%04d%02d".format(date.year, date.monthValue)); put("PAYERNO", contract.id)
        }, "bills"), "IT_TAB", "bills")
        val seen = HashSet<String>()
        return summary.map { row ->
            val month = month(required(row, "YEARMONTH", "bills"))
            check(seen.add(month)) { "해양에너지 청구월이 중복되어 있어요." }
            val usage = number(row, "CONSUME_QTY", "bills")
            val amount = number(row, "NOTICE_AMT", "bills")?.let { round(it * 100.0) }
            val notice = optional(row, "NOTICENO")
            if (notice == null) DirectBill(month, usage, amount) else detail(contract, month, notice, usage, amount)
        }.sortedBy { it.month }
    }

    private fun detail(contract: DirectContract, month: String, notice: String, usage: Double?, amount: Double?): DirectBill = try {
        val row = rows(call("BILL002", JSONObject().apply { put("NOTICENO", notice); put("PAYERNO", contract.id); put("YYYYMM", month.replace("-", "")) }, "bills"), "IT_TAB", "bills").singleOrNull()
            ?: fail("bills", "parse")
        val start = date(optional(row, "USE_PERIOD_FROM"), "bills")
        val end = date(optional(row, "USE_PERIOD_TO"), "bills")
        if (start != null && end != null) check(LocalDate.parse(start) <= LocalDate.parse(end))
        val previous = number(row, "PREV_INDCT", "bills")
        val current = number(row, "CURR_INDCT", "bills")
        if (previous != null && current != null) check(current >= previous)
        DirectBill(month, number(row, "CONSUME_QTY", "bills") ?: usage, amount, start, end, previous, current, optional(row, "INSTALLNO"))
    } catch (e: ProviderFailure) {
        if (e.category == "authentication") throw e
        DirectBill(month, usage, amount)
    }

    private fun target(contract: DirectContract): SelfReadTarget? {
        val meterId = contract.meterId ?: return null
        val response = call("SELF100", JSONObject().apply { put("INSTALLNO", meterId); put("PAYERNO", contract.id) }, "meter")
        val rows = rows(response, "IT_TAB", "meter")
        if (rows.size != 1) return null
        val row = rows.single()
        if (optional(row, "INSTALLNO") !in setOf(null, meterId)) fail("meter", "parse")
        val code = required(response, "RTNCD", "meter")
        if (code !in setOf("00", "01")) fail("meter", "parse")
        val anchor = date(optional(row, "METERDATE"), "meter")?.let { LocalDate.parse(it).plusMonths(1).withDayOfMonth(1) } ?: now().withDayOfMonth(1)
        val (first, last) = when (required(row, "PAYMENTDATE", "meter")) {
            "A" -> 1 to 5; "B" -> 6 to 10; "C" -> 11 to 15; "S" -> anchor.lengthOfMonth() - 1 to anchor.lengthOfMonth()
            else -> fail("meter", "parse")
        }
        val start = anchor.withDayOfMonth(first).toString()
        val end = anchor.withDayOfMonth(last).toString()
        val previous = number(row, "PREV_INDCT", "meter") ?: fail("meter", "parse")
        val acceptedDate = date(optional(row, "METERDATE_CM"), "meter")
        val accepted = number(row, "NREVB_INDCT", "meter")
        val inWindow = acceptedDate != null && acceptedDate in start..end
        val submitted = inWindow && accepted != null
        val ambiguous = inWindow && accepted == null || !inWindow && accepted != null && accepted != 0.0
        val order = required(row, "MTORDERNO", "meter")
        val installation = DirectIdentity.meter("haeyang", contract.id, meterId)
        return SelfReadTarget(
            cycle = SkensClient.opaque("haeyang:${contract.id}:$meterId:$order"), start = start, end = end,
            eligible = code == "00" && !ambiguous, submitted = submitted, submittedValue = if (submitted) accepted else null,
            previousValue = previous, contract = Contract(contract.id, "haeyang", contract.label), serial = "$meterId:$order",
            address = contract.label, planned = end, vLdo = "", installation = installation,
        )
    }

    private fun call(code: String, body: JSONObject, stage: String) = request(code, body, stage)
    private fun request(code: String, body: JSONObject, stage: String, oneShot: Boolean = false): JSONObject {
        require(code in CODES) { "허용되지 않은 해양에너지 요청이에요." }
        val payload = envelope(code, body).toString()
        val requestBody = FormBody.Builder().add("message", payload).build().let { if (oneShot) it.oneShot() else it }
        val request = Request.Builder().url(baseUrl.resolve("$code.json")!!)
            .header("Origin", "${baseUrl.scheme}://${baseUrl.host}").header("Referer", baseUrl.resolve("contents/MAI/jsp/MAI0100.jsp").toString())
            .post(requestBody).build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw ProviderFailure(stage, if (response.code in setOf(401, 403)) "authentication" else "http", response.code)
            val text = response.body?.byteStream()?.readBytesLimited(MAX_BYTES)?.toString(Charsets.UTF_8) ?: fail(stage, "parse")
            decodeEnvelope(JSONObject(text), stage)
        }
    }

    private fun account(contract: DirectContract) {
        check(loggedIn && contract.id.matches(Regex("[A-Za-z0-9_-]{1,64}")) && discovered.containsKey(contract.id)) {
            "해양에너지 계약을 다시 연결해 주세요."
        }
    }
    private fun sameOrigin(url: HttpUrl) = url.scheme == baseUrl.scheme && url.host == baseUrl.host && url.port == baseUrl.port
    override fun close() { synchronized(cookies) { cookies.clear() }; client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
    private inline fun <T> at(stage: String, block: () -> T): T = try { block() } catch (e: ProviderFailure) { throw e } catch (e: Exception) {
        throw ProviderFailure(stage, when (e) { is java.net.SocketTimeoutException -> "timeout"; is javax.net.ssl.SSLException -> "tls"; is java.io.IOException -> "network"; else -> "parse" })
    }

    private fun decodeEnvelope(value: JSONObject, stage: String): JSONObject {
        val header = value.optJSONObject("header") ?: fail(stage, "parse")
        if (header.optBoolean("result", false).not()) throw ProviderFailure(stage, if (header.optString("error_code").startsWith("LOGIN01") || header.optString("error_code") == "ERR000") "authentication" else "unsupported")
        return value.optJSONObject("body") ?: fail(stage, "parse")
    }
    private fun decodeLegacy(value: Any?, stage: String): JSONObject = when (value) {
        is JSONObject -> decodeEnvelope(value, stage)
        is String -> decodeEnvelope(JSONObject(value), stage)
        else -> fail(stage, "parse")
    }
    private fun envelope(code: String, body: JSONObject) = JSONObject().put("header", JSONObject().apply {
        put("result", true); put("error_code", ""); put("error_text", ""); put("info_text", "WEB"); put("message_version", ""); put("login_session_id", ""); put("trcode", code)
    }).put("body", body)
    private fun rows(body: JSONObject, key: String, stage: String): List<JSONObject> = body.optJSONArray(key)?.let { array -> (0 until array.length()).map { array.optJSONObject(it) ?: fail(stage, "parse") } } ?: fail(stage, "parse")
    private fun optional(row: JSONObject, key: String) = row.optString(key).trim().takeIf { it.isNotBlank() && !it.equals("null", true) }
    private fun required(row: JSONObject, key: String, stage: String) = optional(row, key) ?: fail(stage, "parse")
    private fun number(row: JSONObject, key: String, stage: String): Double? = optional(row, key)?.let { raw -> raw.replace(",", "").toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..99_999_999.0 } ?: fail(stage, "parse") }
    private fun date(value: String?, stage: String): String? = value?.filter(Char::isDigit)?.let { digits -> if (digits.length != 8) fail(stage, "parse") else LocalDate.parse(digits, java.time.format.DateTimeFormatter.BASIC_ISO_DATE).toString() }
    private fun month(value: String): String { val digits = value.filter(Char::isDigit); check(digits.matches(Regex("20\\d{2}(0[1-9]|1[0-2])"))); return YearMonth.parse(digits, java.time.format.DateTimeFormatter.ofPattern("yyyyMM")).toString() }
    private fun digest(algorithm: String, value: String) = MessageDigest.getInstance(algorithm).digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun fail(stage: String, category: String): Nothing = throw ProviderFailure(stage, category)

    private companion object {
        const val DEFAULT_BASE = "https://m.hyenergy.co.kr/bizmob/"
        const val MAX_BYTES = 4_000_000
        val CODES = setOf("LOGIN", "MYPAGE3", "BILL001", "BILL002", "SELF100", "SELF101")
    }
}
