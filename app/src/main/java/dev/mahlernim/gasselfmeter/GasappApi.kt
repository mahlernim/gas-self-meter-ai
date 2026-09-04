package dev.mahlernim.gasselfmeter

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.Jsoup
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.TimeUnit

// Protocol observations are documented in docs/GASAPP.md. No external implementation is copied.
class GasappSession(val token: String, val member: String, val deviceId: String) {
    init { require(token.isNotBlank() && member.isNotBlank() && deviceId.isNotBlank()) }
    override fun toString() = "GasappSession(redacted)"
}

class GasappIdentity(val name: String, val phone: String, val birthday: String, val gender: String, val carrier: String) {
    init {
        require(name.trim().length in 2..80) { "이름을 확인해 주세요." }
        require(phone.matches(Regex("01[016789][0-9]{7,8}"))) { "휴대전화 번호를 확인해 주세요." }
        require(gender in listOf("1", "2", "3", "4")) { "주민등록번호 뒤 첫 자리 1~4를 입력해 주세요." }
        require(carrier in listOf("1", "2", "3")) { "통신사를 선택해 주세요." }
        require(birthday.matches(Regex("[0-9]{6}"))) { "생년월일 여섯 자리를 입력해 주세요." }
        require(runCatching { LocalDate.parse(birthDate, DateTimeFormatter.BASIC_ISO_DATE) <= LocalDate.now() }.getOrDefault(false)) { "생년월일을 확인해 주세요." }
    }
    val birthDate: String get() = (if (gender in listOf("1", "2")) "19" else "20") + birthday
    override fun toString() = "GasappIdentity(redacted)"
}

class GasappSms internal constructor(val requestNo: String, val responseUniqId: String) {
    override fun toString() = "GasappSms(redacted)"
}
data class GasappTerms(val category: String, val text: String)
data class GasappAccount(val company: String, val customer: String, val contract: String, val label: String, val ami: String) {
    val key: String get() = gasappHash("contract:$company:$customer:$contract")
    val params: Map<String, String> get() = mapOf("customerNum" to customer, "useContractNum" to contract)
}
data class GasappBill(val month: String, val usage: Double?, val amount: Double?, val start: String?, val end: String?)
data class GasappReading(val id: String?, val date: String, val value: Double, val meter: String?)
data class GasappTarget(
    val account: GasappAccount, val registered: Boolean, val eligible: Boolean,
    val start: String?, val end: String?, val meter: String?, val previous: Double?,
    val digits: Int?, val needsChannelChange: Boolean, val meterChanged: Boolean,
    val submitted: Boolean, val submittedValue: Double?,
) {
    val cycle: String get() = "${account.key}:$meter:$start:$end"
}
data class GasappSnapshot(val account: GasappAccount, val bills: List<GasappBill>, val readings: List<GasappReading>, val target: GasappTarget)
enum class GasappSubmitStatus { CONFIRMED, REJECTED, UNCERTAIN }
data class GasappSubmitResult(val status: GasappSubmitStatus, val target: GasappTarget?)
class GasappAuthExpired : IllegalStateException("가스앱 인증이 만료됐어요. 다시 연결해 주세요.")

fun gasappHash(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray()).take(12).joinToString("") { "%02x".format(it) }

class GasappApi internal constructor(
    private val base: HttpUrl,
    private val http: OkHttpClient,
    val deviceId: String,
) : AutoCloseable {
    constructor(deviceId: String = UUID.randomUUID().toString()) : this(
        "https://app.gasapp.co.kr/api/".toHttpUrl(),
        OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS).retryOnConnectionFailure(false)
            .followRedirects(false).followSslRedirects(false).build(), deviceId,
    )

    fun terms(carrier: String): List<GasappTerms> {
        val carrierName = mapOf("1" to "SKT", "2" to "KT", "3" to "LGU")[carrier] ?: error("통신사를 선택해 주세요.")
        return listOf("본인인증 약관 $carrierName", "최초 회원 가입 약관", "최초 회원 가입 약관-도시가스 사 정보 조회").map { category ->
            val payload = request("GET", "documents/search/0", params = mapOf("category" to category))
            val documents = objects(payload, "documents")
            val text = documents.filter { it.optBoolean("necessary", true) }.mapNotNull { row ->
                string(row, "content", "contents", "body")?.let { string(row, "title").orEmpty() + "\n" + Jsoup.parse(it).wholeText() }
            }.joinToString("\n\n")
            check(text.isNotBlank()) { "약관을 불러오지 못했어요. 잠시 후 다시 시도해 주세요." }
            GasappTerms(category, text)
        }
    }

    fun requestSms(identity: GasappIdentity, acceptedTerms: List<GasappTerms>): GasappSms {
        require(acceptedTerms.size == 3 && acceptedTerms.all { it.text.isNotBlank() }) { "필수 약관을 확인하고 동의해 주세요." }
        val expectedCarrier = mapOf("1" to "SKT", "2" to "KT", "3" to "LGU").getValue(identity.carrier)
        require(acceptedTerms.first().category == "본인인증 약관 $expectedCarrier") { "변경한 통신사의 약관을 다시 확인해 주세요." }
        val result = obj(request("POST", "extern/auth/nice/sms/request", body = JSONObject()
            .put("mobileCo", identity.carrier).put("mobileNo", identity.phone).put("birthday", identity.birthday)
            .put("gender", identity.gender).put("name", identity.name.trim())))
        return GasappSms(required(result, "requestNo"), required(result, "responseUniqId"))
    }

    fun confirmSms(identity: GasappIdentity, challenge: GasappSms, otp: String): GasappSession {
        require(otp.matches(Regex("[0-9]{6}"))) { "인증번호 여섯 자리를 입력해 주세요." }
        val verified = obj(request("POST", "extern/auth/nice/sms/confirm", body = JSONObject()
            .put("requestNo", challenge.requestNo).put("responseUniqId", challenge.responseUniqId).put("otp", otp)))
        val member = obj(request("POST", "members", body = JSONObject()
            .put("name", identity.name.trim()).put("birthDate", identity.birthDate).put("handphone", identity.phone)
            .put("gender", if (identity.gender in listOf("2", "4")) "F" else "M")
            .put("ci", required(verified, "ci")).put("di", required(verified, "di"))
            .put("marketingAcceptance", "N").put("nation", "N").put("mid", JSONObject.NULL).put("adid", deviceId)))
        return GasappSession(required(member, "token"), required(member, "member"), deviceId)
    }

    fun accounts(session: GasappSession): List<GasappAccount> = parseAccounts(request("GET", "contracts", session, company = "0"))

    fun snapshot(session: GasappSession, account: GasappAccount): GasappSnapshot {
        checkAccount(account)
        val home = obj(request("GET", "home", session, account.company, account.params + ("amiYn" to account.ami)))
        val bills = parseBills(request("GET", "bills/summary", session, account.company,
            account.params + mapOf("onlyUnpay" to "N", "f" to "annual")))
        val meterRows = objects(request("GET", "meters", session, account.company, account.params), "meters")
        val meterSerials = meterRows.mapNotNull { string(it, "meterIdNum") }.distinct()
        val queriedTarget = target(session, account)
        val target = if (queriedTarget.meter == null && meterSerials.size == 1)
            queriedTarget.copy(meter = gasappHash("meter:${account.company}:${meterSerials.single()}")) else queriedTarget
        val readings = if (target.registered) history(session, account) else emptyList()
        // The home card supplies a reading even when no self-input history exists.
        val card = home.optJSONObject("cards")?.optJSONObject("indication")
        val cardReadings = card?.optJSONArray("history")?.let(::parseReadings).orEmpty()
        return GasappSnapshot(account, bills, (readings + cardReadings).distinctBy { Pair(it.date, it.value) }.sortedBy { it.date }, target)
    }

    fun target(session: GasappSession, account: GasappAccount): GasappTarget {
        checkAccount(account)
        val state = request("GET", "indications", session, account.company, account.params)
        return parseTarget(state, account)
    }

    fun history(session: GasappSession, account: GasappAccount): List<GasappReading> {
        val result = mutableListOf<GasappReading>()
        val cursors = mutableSetOf<String>()
        var cursor: String? = null
        repeat(60) {
            val params = account.params + mapOf("limit" to "6") + (cursor?.let { mapOf("lastId" to it) } ?: emptyMap())
            val rows = objects(request("GET", "indications/history", session, account.company, params), "history")
            // The sixth item is the inclusive cursor for the next page in the official frontend.
            val page = if (rows.size == 6) rows.take(5) else rows
            result += page.mapNotNull(::parseReading)
            if (rows.size < 6) return result.distinctBy { it.id ?: "${it.date}:${it.value}" }.sortedBy { it.date }
            cursor = required(rows.last(), "id")
            check(cursors.add(cursor!!)) { "검침 이력 조회가 반복됐어요. 다시 갱신해 주세요." }
        }
        error("검침 이력이 너무 많아요. 공급사에서 확인해 주세요.")
    }

    fun register(session: GasappSession, account: GasappAccount, consent: Boolean): GasappTarget {
        require(consent) { "자가검침 서비스 신청 내용을 확인해 주세요." }
        request("POST", "indications/register", session, account.company, body = JSONObject(account.params))
        return target(session, account)
    }

    fun changeChannel(session: GasappSession, account: GasappAccount, consent: Boolean): GasappTarget {
        require(consent) { "가스앱 검침으로 변경하는 데 동의해 주세요." }
        request("PUT", "indications/channel", session, account.company, body = JSONObject().put("useContractNum", account.contract))
        return target(session, account)
    }

    /** Caller must persist a pending record BEFORE this method and reconcile it instead of resending. */
    fun submit(session: GasappSession, expected: GasappTarget, value: Double, date: LocalDate = LocalDate.now(Korea)): GasappSubmitResult {
        val fresh = target(session, expected.account)
        require(sameTarget(expected, fresh)) { "계약이나 계량기 정보가 변경됐어요. 다시 확인해 주세요." }
        require(fresh.registered && fresh.eligible && !fresh.needsChannelChange && !fresh.submitted) { "현재 제출 가능한 상태가 아니에요." }
        require(fresh.start != null && fresh.end != null && date >= LocalDate.parse(fresh.start) && date <= LocalDate.parse(fresh.end)) { "자가검침 입력 기간이 아니에요." }
        require(fresh.meter != null && fresh.previous != null) { "계량기와 이전 지침을 확인해 주세요." }
        require(value.isFinite() && value >= fresh.previous && value <= 99_999_999 && value == kotlin.math.floor(value)) { "가스앱에는 계량기의 정수 지침을 입력해 주세요." }
        require(fresh.digits == null || BigDecimal.valueOf(value).toBigInteger().toString().length <= fresh.digits) { "계량기 자릿수를 확인해 주세요." }
        val response = try {
            obj(request("POST", "relay/indications/input", session, fresh.account.company, body = JSONObject(fresh.account.params)
                .put("thisMonthIndicatorCustomer", BigDecimal.valueOf(value).toBigIntegerExact().toString())))
        } catch (_: Exception) { return reconcile(session, fresh, value) }
        if (string(response, "inputYn") == "N") return GasappSubmitResult(GasappSubmitStatus.REJECTED, null)
        return reconcile(session, fresh, value)
    }

    fun reconcile(session: GasappSession, expected: GasappTarget, value: Double): GasappSubmitResult {
        val current = runCatching { target(session, expected.account) }.getOrNull()
        val confirmed = current != null && sameTarget(expected, current) && current.submitted &&
            current.submittedValue?.let { it.isFinite() && kotlin.math.abs(it - value) < .0001 } == true
        return GasappSubmitResult(if (confirmed) GasappSubmitStatus.CONFIRMED else GasappSubmitStatus.UNCERTAIN, current)
    }

    private fun request(method: String, path: String, session: GasappSession? = null, company: String = "null",
        params: Map<String, String> = emptyMap(), body: JSONObject? = null): Any {
        require("$method $path" in endpoints)
        val url = base.newBuilder().addPathSegments(path).apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        val builder = Request.Builder().url(url).header("Accept", "application/json, text/plain, */*")
            .header("Accept-Language", "ko-KR,ko;q=0.9").header("Origin", "https://app.gasapp.co.kr")
            .header("Referer", "https://app.gasapp.co.kr/").header("X-VERSION", "11.5.1505")
            .header("X-WEBVERSION", "6.10.548").header("X-PLATFORM", "android")
            .header("User-Agent", "WunderFlo Appstore/11.5.1505")
            .header("X-TOKEN", session?.token.orEmpty()).header("X-MEMBER", session?.member.orEmpty())
            .header("X-COMPANY", company).header("X-ADID", session?.deviceId ?: deviceId).header("X-TID", "")
        if (method != "GET") builder.method(method, (body ?: JSONObject()).toString().toRequestBody("application/json;charset=utf-8".toMediaType()))
        return http.newCall(builder.build()).execute().use { response ->
            if (response.code == 401 || response.code == 418) throw GasappAuthExpired()
            check(response.isSuccessful) { "가스앱 요청을 처리하지 못했어요. 잠시 후 다시 시도해 주세요. (${response.code})" }
            val text = response.body?.byteStream()?.readBytesLimited(4_000_000)?.toString(Charsets.UTF_8).orEmpty()
            if (text.isBlank()) JSONObject.NULL else JSONTokener(text).nextValue()
        }
    }

    override fun close() { http.dispatcher.cancelAll(); http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown() }

    companion object {
        val companyProviders = mapOf("1" to "seoul", "2" to "incheon", "3" to "jeju", "4" to "jb", "5" to "daeryun",
            "6" to "yesco", "7" to "gunsan", "8" to "kiturami", "9" to "chambit", "10" to "chambit",
            "11" to "chambit", "12" to "chambit", "13" to "chambit", "14" to "kyungdong", "15" to "mcenergy",
            "16" to "seohae", "17" to "daehwa", "18" to "jeonbukgas")
        private val endpoints = setOf("GET documents/search/0", "POST extern/auth/nice/sms/request", "POST extern/auth/nice/sms/confirm",
            "POST members", "GET contracts", "GET home", "GET meters", "GET bills/summary", "GET indications", "GET indications/history",
            "POST indications/register", "PUT indications/channel", "POST relay/indications/input")

        fun parseAccounts(payload: Any): List<GasappAccount> = objects(payload, "contracts").map { row ->
            GasappAccount(required(row, "company"), string(row, "customerNum").orEmpty(), string(row, "useContractNum").orEmpty(),
                string(row, "alias", "label") ?: "계약 ${string(row, "useContractNum", "customerNum").orEmpty().takeLast(4)}",
                string(row, "amiYn")?.takeIf { it in listOf("Y", "N") } ?: "N").also(::checkAccount)
        }.distinctBy { it.key }

        fun parseBills(payload: Any): List<GasappBill> = objects(payload, "history").map { row ->
            val rawMonth = required(row, "requestYm")
            val month = if (rawMonth.matches(Regex("[0-9]{6}"))) "${rawMonth.take(4)}-${rawMonth.takeLast(2)}" else rawMonth.take(7)
            YearMonth.parse(month)
            GasappBill(month, decimal(row, "useQty", "usageQty"), decimal(row, "chargeAmtQty", "chargeAmt"),
                date(row, "useStartDate"), date(row, "useEndDate"))
        }.distinctBy { it.month }.sortedBy { it.month }.takeLast(24)

        fun parseReadings(payload: Any): List<GasappReading> = objects(payload, "history").mapNotNull(::parseReading)
        private fun parseReading(row: JSONObject): GasappReading? {
            val date = date(row, "gmtrJobYmd", "jobYmd", "readingDate", "inputDate") ?: return null
            val value = decimal(row, "indiCompensThisMonthVc", "thisMonthIndicator", "thisMonthIndicatorCustomer") ?: return null
            return GasappReading(string(row, "id"), date, value, string(row, "meterIdNum"))
        }

        fun parseTarget(payload: Any, account: GasappAccount): GasappTarget {
            val unwrapped = unwrap(payload)
            if (unwrapped == JSONObject.NULL) return GasappTarget(account, false, false, null, null, null, null, null, false, false, false, null)
            val row = obj(unwrapped)
            check(listOf("selfInputAvailable", "periodStart", "meterIdNum").any(row::has)) { "자가검침 상태를 확인하지 못했어요." }
            string(row, "useContractNum")?.let { check(it == account.contract) { "조회된 계약이 달라요." } }
            string(row, "customerNum")?.let { check(it == account.customer) { "조회된 고객번호가 달라요." } }
            val start = date(row, "periodStart")
            val end = date(row, "periodEnd")
            check(start == null || end == null || start <= end) { "자가검침 기간을 확인하지 못했어요." }
            val value = decimal(row, "thisMonthIndicatorCustomer", "thisMonthIndicator")
            val submitted = flag(row.opt("inputYn")) || flag(row.opt("selfInputYn")) || value != null
            return GasappTarget(account, true, flag(row.opt("selfInputAvailable")), start, end,
                string(row, "meterIdNum")?.let { gasappHash("meter:${account.company}:$it") }, decimal(row, "lastMonthIndicatorQty"),
                string(row, "mtrDigitCnt")?.toIntOrNull()?.also { require(it in 1..20) },
                flag(row.opt("needChangeRegisteredChannel")), flag(row.optJSONObject("meterChange")?.opt("changeYn")), submitted, value)
        }

        fun sameTarget(a: GasappTarget, b: GasappTarget) = a.account.key == b.account.key && a.meter != null && a.meter == b.meter &&
            a.start != null && a.end != null && a.start == b.start && a.end == b.end && a.previous == b.previous &&
            a.meterChanged == b.meterChanged

        private fun checkAccount(account: GasappAccount) {
            require(account.company in companyProviders) { "가스앱 회사 코드를 확인해 주세요." }
            require(account.customer.isNotBlank() || account.contract.isNotBlank()) { "연결된 계약을 찾지 못했어요." }
        }
        internal fun unwrap(value: Any): Any = if (value is JSONObject && value.has("data")) value.get("data") else value
        internal fun obj(value: Any): JSONObject = unwrap(value) as? JSONObject ?: error("가스앱 응답을 확인하지 못했어요.")
        internal fun objects(value: Any, key: String): List<JSONObject> {
            val root = unwrap(value)
            val rows = when (root) {
                is JSONArray -> root
                is JSONObject -> root.optJSONArray(key) ?: return listOf(root)
                JSONObject.NULL -> return emptyList()
                else -> error("가스앱 목록을 확인하지 못했어요.")
            }
            return (0 until rows.length()).map { rows.getJSONObject(it) }
        }
        internal fun string(row: JSONObject, vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
            row.opt(key)?.takeUnless { it == JSONObject.NULL }?.toString()?.trim()?.takeIf { it.isNotBlank() && it != "null" }
        }
        private fun required(row: JSONObject, key: String) = string(row, key) ?: error("가스앱 응답에 필요한 정보가 없어요. 다시 확인해 주세요.")
        private fun decimal(row: JSONObject, vararg keys: String): Double? = string(row, *keys)?.replace(",", "")?.let {
            val n = it.toDoubleOrNull()
            require(n != null && n.isFinite() && n in 0.0..99_999_999.0) { "가스앱 숫자 형식을 확인하지 못했어요." }; n
        }
        private fun date(row: JSONObject, vararg keys: String): String? = string(row, *keys)?.let {
            if (it.matches(Regex("[0-9]{8}"))) LocalDate.parse(it, DateTimeFormatter.BASIC_ISO_DATE).toString() else LocalDate.parse(it.take(10)).toString()
        }
        private fun flag(value: Any?): Boolean = value == true || value?.toString()?.uppercase() == "Y" || value?.toString()?.lowercase() == "true"
    }
}
