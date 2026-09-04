package dev.mahlernim.gasselfmeter

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.YearMonth

data class SamchullySession(val accessToken: String, val userType: String)

data class SamchullyUser(
    val name: String,
    val birthDate: String,
    val phone: String,
)

data class SamchullyContract(
    val customerNo: String,
    val label: String,
    val roadAddress: String?,
    val lotAddress: String?,
    val phone: String?,
    val meterId: String?,
) {
    val key: String get() = SkensClient.opaque("samchully:$customerNo")
}

data class SamchullyBill(
    val billMonth: String,
    val start: String?,
    val end: String?,
    val previousReading: Double?,
    val currentReading: Double?,
    val reportedUsage: Double?,
    val amount: Double?,
    val meterId: String?,
)

data class SamchullySelfReadState(
    val start: String?,
    val end: String?,
    val targetId: String?,
    val previousReading: Double?,
    val submitted: Boolean?,
    val submittedReading: Double?,
    val submittedDate: String?,
)

/**
 * Experimental read-only client for the public Samchully customer-center contract.
 * Contains no write endpoint.
 */
class SamchullyReadClient internal constructor(
    private val provider: Provider,
    private val credentials: Credentials? = null,
    private val client: OkHttpClient = readOnlyHttpClient(),
) : AutoCloseable {
    init {
        require(provider.id == "samchully") { "삼천리 연결 대상이 아닌 공급사예요." }
        require(credentials == null || (credentials.username.isNotBlank() && credentials.password.isNotBlank())) { "삼천리 아이디와 비밀번호를 입력해 주세요." }
    }

    fun login(userType: String = "PER", expiresInMillis: Int = 3_600_000): SamchullySession = atStage("login") {
        val credentials = requireNotNull(credentials) { "삼천리 아이디와 비밀번호를 입력해 주세요." }
        require(userType in setOf("PER", "BIZ", "BOI")) { "삼천리 회원 유형을 확인해 주세요." }
        require(expiresInMillis in setOf(600_000, 3_600_000, 14_400_000, 25_200_000)) { "삼천리 로그인 유지 시간을 확인해 주세요." }
        val first = payload(post("scl/auth/login-pwd", JSONObject().apply {
            put("userType", userType)
            put("userId", credentials.username)
            put("userPwd", credentials.password)
            put("exp", expiresInMillis)
        }))
        val loginToken = clean(first, "loginToken") ?: error("삼천리 로그인 정보를 확인하지 못했어요.")
        val resolvedType = clean(first, "userType") ?: userType
        val second = payload(post("scl/auth/login", JSONObject().apply {
            put("loginToken", loginToken)
            put("userType", resolvedType)
            put("exp", expiresInMillis)
        }))
        val accessToken = clean(second, "accessToken") ?: error("삼천리 인증 토큰을 확인하지 못했어요.")
        SamchullySession(accessToken, resolvedType)
    }

    fun user(session: SamchullySession): SamchullyUser = atStage("user") {
        parseUser(post("scl/users/me", JSONObject(), session.accessToken))
    }

    fun contracts(session: SamchullySession, user: SamchullyUser): List<SamchullyContract> = atStage("contracts") {
        val birth = user.birthDate.filter(Char::isDigit).let { if (it.length == 8) it.drop(2) else it }
        require(birth.matches(Regex("\\d{6}"))) { "삼천리 회원 생년월일 형식을 확인하지 못했어요." }
        val response = post("scl/services/custinfo", JSONObject().apply {
            put("I_GUBUN", "1")
            put("I_NAME", user.name)
            put("I_BIRTH", birth)
            put("I_PHONE", user.phone.filter(Char::isDigit))
        }, session.accessToken)
        parseContracts(response)
    }

    fun bills(session: SamchullySession, customerNo: String, from: YearMonth, to: YearMonth): List<SamchullyBill> = atStage("bills") {
        require(from <= to && java.time.temporal.ChronoUnit.MONTHS.between(from, to) in 0..60) { "삼천리 요금 조회 기간을 확인해 주세요." }
        requireCustomerNo(customerNo)
        val response = post("scl/services/goji-list", JSONObject().apply {
            put("I_VKONT", customerNo)
            put("I_YYYYMM_FROM", from.toString().replace("-", ""))
            put("I_YYYYMM_TO", to.toString().replace("-", ""))
        }, session.accessToken)
        parseBills(response)
    }

    fun selfReadState(session: SamchullySession, contract: SamchullyContract): SamchullySelfReadState = atStage("meter") {
        requireCustomerNo(contract.customerNo)
        val common = JSONObject().apply {
            put("I_VKONT", contract.customerNo)
            put("I_FLAG", "1")
            put("I_PHONE", contract.phone.orEmpty().filter(Char::isDigit))
        }
        val period = post("scl/services/meter-check", common, session.accessToken)
        val target = post("scl/services/self-meter", common, session.accessToken)
        val recent = post("scl/services/self-meter-list", JSONObject().apply {
            put("ET_VKONT", JSONArray().put(JSONObject().put("VKONT", contract.customerNo)))
        }, session.accessToken)
        parseSelfReadState(period, target, recent)
    }

    private fun post(path: String, data: JSONObject, token: String? = null): JSONObject {
        require(path in READ_ENDPOINTS) { "허용되지 않은 삼천리 조회 요청이에요." }
        val request = Request.Builder().url(API_BASE.toHttpUrl().newBuilder().addPathSegments(path).build())
            .header("Accept", "application/json, text/plain, */*")
            .header("Accept-Language", "ko-KR,ko;q=0.9")
            .header("Origin", "https://cs.samchully.co.kr")
            .header("Referer", "https://cs.samchully.co.kr/")
            .apply { if (!token.isNullOrBlank()) header("X-User-Token", token) }
            .post(data.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return client.newCall(request).execute().use { response ->
            val stage = when (path) {
                "scl/auth/login-pwd", "scl/auth/login" -> "login"
                "scl/users/me" -> "user"
                "scl/services/custinfo" -> "contracts"
                "scl/services/goji-list" -> "bills"
                else -> "meter"
            }
            if (!response.isSuccessful) throw ProviderFailure(stage,
                if (response.code == 401 || response.code == 403) "authentication" else "http", response.code)
            val body = response.body ?: error("삼천리 조회 결과가 비어 있어요.")
            val text = String(body.byteStream().readBytesLimited(MAX_RESPONSE_BYTES), Charsets.UTF_8)
            check(text.isNotBlank()) { "삼천리 조회 결과가 비어 있어요." }
            JSONObject(text)
        }
    }

    override fun close() {
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    private inline fun <T> atStage(stage: String, action: () -> T): T = try {
        action()
    } catch (failure: ProviderFailure) {
        throw failure
    } catch (failure: Exception) {
        val category = when (failure) {
            is java.net.SocketTimeoutException -> "timeout"
            is javax.net.ssl.SSLException -> "tls"
            is java.io.IOException -> "network"
            else -> "parse"
        }
        throw ProviderFailure(stage, category, cause = failure)
    }

    companion object {
        private const val API_BASE = "https://ecpgw.samchully.co.kr/relay/"
        private const val MAX_RESPONSE_BYTES = 4_000_000
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val READ_ENDPOINTS = setOf(
            "scl/auth/login-pwd",
            "scl/auth/login",
            "scl/users/me",
            "scl/services/custinfo",
            "scl/services/goji-list",
            "scl/services/meter-check",
            "scl/services/self-meter",
            "scl/services/self-meter-list",
        )

        fun parseUser(response: JSONObject): SamchullyUser {
            val data = payload(response)
            return SamchullyUser(
                name = clean(data, "userName") ?: error("삼천리 회원 이름을 확인하지 못했어요."),
                birthDate = clean(data, "birthDate") ?: error("삼천리 회원 생년월일을 확인하지 못했어요."),
                phone = clean(data, "phoneNumber") ?: error("삼천리 회원 전화번호를 확인하지 못했어요."),
            )
        }

        fun parseContracts(response: JSONObject): List<SamchullyContract> {
            val rows = array(response, "E_TAB") ?: error("삼천리 고객번호 목록을 찾지 못했어요.")
            val contracts = (0 until rows.length()).map { index ->
                val row = rows.optJSONObject(index) ?: error("삼천리 고객번호 형식이 예상과 달라요.")
                val customerNo = clean(row, "VKONT") ?: error("삼천리 고객번호를 찾지 못했어요.")
                requireCustomerNo(customerNo)
                SamchullyContract(
                    customerNo = customerNo,
                    label = clean(row, "VKBEZ_M") ?: clean(row, "VKBEZ") ?: "고객번호 ${customerNo.takeLast(4)}",
                    roadAddress = clean(row, "ADDR_D_M"),
                    lotAddress = clean(row, "ADDR_J_M"),
                    phone = clean(row, "PHONE") ?: clean(row, "MOB_NUMBER"),
                    meterId = clean(row, "LOGIKZW") ?: clean(row, "GERNR"),
                )
            }
            check(contracts.isNotEmpty()) { "삼천리에 연결된 고객번호가 없어요." }
            check(contracts.distinctBy { it.customerNo }.size == contracts.size) { "삼천리 고객번호가 중복되어 있어요." }
            return contracts
        }

        fun parseBills(response: JSONObject): List<SamchullyBill> {
            val rows = array(response, "E_TAB") ?: error("삼천리 청구 이력 목록을 찾지 못했어요.")
            return (0 until rows.length()).map { index ->
                val row = rows.optJSONObject(index) ?: error("삼천리 청구 이력 형식이 예상과 달라요.")
                val month = clean(row, "BILLING_PERIOD") ?: error("삼천리 청구월을 찾지 못했어요.")
                require(month.matches(Regex("20\\d{2}(0[1-9]|1[0-2])"))) { "삼천리 청구월 형식이 예상과 달라요." }
                val start = portalDate(clean(row, "MR_DATE_FR"))
                val end = portalDate(clean(row, "MR_DATE_TO"))
                if (start != null && end != null) require(LocalDate.parse(start) <= LocalDate.parse(end)) { "삼천리 검침 기간을 확인하지 못했어요." }
                val previous = numberOrNull(row, "PR_ZWSTNDAB")
                val current = numberOrNull(row, "ZWSTNDAB")
                if (previous != null && current != null) require(current >= previous) { "삼천리 누적 지침이 감소했어요. 계량기 교체 여부를 확인해 주세요." }
                SamchullyBill(
                    billMonth = month,
                    start = start,
                    end = end,
                    previousReading = previous,
                    currentReading = current,
                    reportedUsage = numberOrNull(row, "CONSUMPTION"),
                    amount = numberOrNull(row, "BETRW_TOT_T") ?: numberOrNull(row, "BETRW_TOT"),
                    meterId = clean(row, "LOGIKZW") ?: clean(row, "GERNR"),
                )
            }.also { bills ->
                require(bills.distinctBy { it.billMonth }.size == bills.size) {
                    "삼천리 청구월이 중복되어 있어요. 계량기별 청구 여부를 확인해 주세요."
                }
            }.sortedBy { it.billMonth }
        }

        fun parseSelfReadState(periodResponse: JSONObject, targetResponse: JSONObject, recentResponse: JSONObject): SamchullySelfReadState {
            val periodRows = array(periodResponse, "ET_RESULT")
            require(periodRows == null || periodRows.length() <= 1) { "삼천리 검침 기간이 여러 개예요. 대상 확인이 필요해요." }
            val period = periodRows?.optJSONObject(0)
            val target = payload(targetResponse)
            val recentRows = array(recentResponse, "E_TAB") ?: array(recentResponse, "ET_RESULT")
            require(recentRows == null || recentRows.length() <= 1) { "삼천리 제출 이력이 여러 개예요. 대상 확인이 필요해요." }
            val recent = recentRows?.optJSONObject(0) ?: payload(recentResponse)
            val submitted = when (clean(recent, "E_YN")) {
                "X", "Y" -> true
                "N" -> false
                else -> null
            }
            val date = portalDate(clean(recent, "E_ERDAT"))
            return SamchullySelfReadState(
                start = portalDate(period?.let { clean(it, "KKO_MR_SDATE") }),
                end = portalDate(period?.let { clean(it, "KKO_MR_EDATE") }),
                targetId = clean(target, "E_TIDNR"),
                previousReading = numberOrNull(target, "E_PRV_M_ZWSTAND"),
                submitted = submitted,
                submittedReading = numberOrNull(recent, "E_ZWSTAND"),
                submittedDate = date,
            )
        }

        private fun payload(response: JSONObject): JSONObject = response.optJSONObject("data") ?: response

        private fun array(response: JSONObject, key: String): JSONArray? {
            val data = payload(response)
            return data.optJSONArray(key)
        }

        private fun portalDate(value: String?): String? {
            if (value == null) return null
            val digits = value.filter(Char::isDigit)
            require(digits.length == 8) { "삼천리 날짜 형식이 예상과 달라요." }
            return LocalDate.parse(digits, java.time.format.DateTimeFormatter.BASIC_ISO_DATE).toString()
        }

        private fun numberOrNull(row: JSONObject, key: String): Double? {
            val value = clean(row, key) ?: return null
            require(value.matches(Regex("(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+)(?:\\.[0-9]+)?"))) {
                "삼천리 숫자 형식이 예상과 달라요."
            }
            return value.replace(",", "").toDouble().also {
                require(it.isFinite() && it in 0.0..99_999_999.0) { "삼천리 숫자 형식이 예상과 달라요." }
            }
        }

        private fun clean(row: JSONObject, key: String): String? = row.optString(key)
            .trim().takeIf { it.isNotBlank() && !it.equals("null", true) }

        private fun requireCustomerNo(value: String) {
            require(value.length in 1..40 && value.all { it.isDigit() }) { "삼천리 고객번호 형식을 확인해 주세요." }
        }
    }
}
