package dev.mahlernim.gasselfmeter

import okhttp3.OkHttpClient
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.TimeUnit
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class EnergyTalkUsage(val month: String, val amount: String, val usage: String)
data class EnergyTalkMeter(
    val eligible: Boolean,
    val previous: String?,
    val recent: String?,
    val message: String?,
    val submitted: Boolean? = null,
)
data class EnergyTalkSubmissionCheck(val allowed: Boolean, val message: String?)
/** Reference-only display. These rows are not raw-volume usage segments or measured anchors. */
data class EnergyTalkSnapshot(
    val clientId: String, val address: String, val usage: List<EnergyTalkUsage>,
    val meter: EnergyTalkMeter?, val unavailable: List<String>,
)

object EnergyTalkBoundary {
    val tenants = setOf("cncity", "kne", "ktrm", "miraense", "srb", "gse", "cwjgas", "ccbgas", "cydgas", "cdhgas", "cscgas")
    private val navigationHosts = setOf("energytalk.ai", "kauth.kakao.com", "accounts.kakao.com", "auth.kakao.com")
    fun officialProxy(url: String): Boolean = try {
        val u = URI(url)
        u.scheme == "https" && u.rawAuthority == "energytalk.ai" && u.rawPath == "/api/fetch" && u.rawQuery == null && u.rawFragment == null
    } catch (_: Exception) { false }
    fun navigationAllowed(url: String): Boolean = try {
        val u = URI(url)
        u.scheme == "https" && u.rawAuthority in navigationHosts && u.userInfo == null && u.port == -1
    } catch (_: Exception) { false }
    fun token(header: String?): String? {
        if (header == null || !header.startsWith("Bearer ")) return null
        val value = header.removePrefix("Bearer ")
        return value.takeIf { it.length in 16..8192 && it.all { c -> c.code in 33..126 } }
    }
}

class EnergyTalkReadClient internal constructor(baseClient: OkHttpClient = OkHttpClient()) {
    private val client = baseClient.newBuilder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()

    suspend fun verifyAndRead(token: String, expectedClientId: String): EnergyTalkSnapshot {
        require(expectedClientId in EnergyTalkBoundary.tenants)
        require(EnergyTalkBoundary.token("Bearer $token") == token)
        val user = get("/gas/api/user/info", token)
        check(user.optString("clientId") == expectedClientId) { "선택한 공급사와 로그인한 공급사가 달라요." }
        val address = text(user, "address", 500)
        check(!address.isNullOrBlank()) { "공식 화면에서 조회할 주소를 먼저 선택해 주세요." }
        val unavailable = mutableListOf<String>()
        val rows = try { parseUsage(get("/gas/api/pay/usage", token)) } catch (e: CancellationException) { throw e } catch (e: EnergyTalkAuthException) { throw e } catch (_: Exception) {
            unavailable += "사용량 이력을 확인하지 못했어요."; emptyList()
        }
        val meter = try { parseMeter(get("/gas/api/self-meter", token)) } catch (e: CancellationException) { throw e } catch (e: EnergyTalkAuthException) { throw e } catch (_: Exception) {
            unavailable += "자가검침 상태를 확인하지 못했어요."; null
        }
        return EnergyTalkSnapshot(expectedClientId, address, rows, meter, unavailable)
    }

    /** Provider preflight only. A false answer is final for this attempt. */
    suspend fun checkReading(token: String, expectedClientId: String, value: Double): EnergyTalkSubmissionCheck {
        require(value.isFinite() && value in 0.0..99_999_999.0)
        verifyTenant(token, expectedClientId)
        val body = JSONObject().put("guideline", java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString())
        val response = postProxy("/gas/api/self-meter/check", token, body)
        val allowed = response.optString("addableYn")
        require(allowed == "Y" || allowed == "N") { "검침값 입력 가능 상태를 확인하지 못했어요." }
        return EnergyTalkSubmissionCheck(allowed == "Y", text(response, "notificationMsg", 500))
    }

    /** Sends one form-data request only. The caller must reread status before reporting completion. */
    suspend fun submitReading(token: String, expectedClientId: String, value: Double): JSONObject {
        require(value.isFinite() && value in 0.0..99_999_999.0)
        verifyTenant(token, expectedClientId)
        val request = Request.Builder().url("https://energytalk.ai/api/formdata")
            .header("Authorization", "Bearer $token").header("Origin", "https://energytalk.ai")
            .header("Referer", "https://energytalk.ai/gas").header("Accept", "application/json")
            .post(MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("method", "POST").addFormDataPart("url", "/gas/api/self-meter")
                .addFormDataPart("guideline", java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()).build().oneShot())
            .build()
        return execute(request)
    }

    private suspend fun verifyTenant(token: String, expectedClientId: String) {
        require(expectedClientId in EnergyTalkBoundary.tenants)
        require(EnergyTalkBoundary.token("Bearer $token") == token)
        check(get("/gas/api/user/info", token).optString("clientId") == expectedClientId) { "선택한 공급사와 로그인한 공급사가 달라요." }
    }

    private suspend fun postProxy(path: String, token: String, body: JSONObject): JSONObject {
        require(path == "/gas/api/self-meter/check")
        val payload = JSONObject().put("method", "POST").put("url", path).put("body", body)
        val request = Request.Builder().url("https://energytalk.ai/api/fetch")
            .header("Authorization", "Bearer $token").header("Origin", "https://energytalk.ai")
            .header("Referer", "https://energytalk.ai/gas").header("Accept", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()).oneShot()).build()
        return execute(request)
    }

    private suspend fun get(path: String, token: String): JSONObject {
        check(path in setOf("/gas/api/user/info", "/gas/api/pay/usage", "/gas/api/self-meter"))
        val payload = JSONObject().put("method", "GET").put("url", path).put("body", JSONObject())
        val request = Request.Builder().url("https://energytalk.ai/api/fetch")
            .header("Authorization", "Bearer $token").header("Origin", "https://energytalk.ai")
            .header("Referer", "https://energytalk.ai/gas").header("Accept", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType())).build()
        return execute(request)
    }

    private suspend fun execute(request: Request): JSONObject = suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    if (!continuation.isActive) { response.close(); return }
                    try {
                        val value = response.use { parseResponse(it) }
                        if (continuation.isActive) continuation.resume(value)
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            })
        }

    private fun parseResponse(response: Response): JSONObject {
        if (response.code == 401 || response.code == 403) throw EnergyTalkAuthException()
        check(response.isSuccessful) { "에너지톡 조회 응답을 확인하지 못했어요." }
        val body = checkNotNull(response.body)
        val source = body.source()
        source.request(262145)
        check(source.buffer.size <= 262144) { "조회 응답이 너무 커요." }
        val bytes = source.readByteArray()
        val json = JSONObject(bytes.toString(Charsets.UTF_8))
        return when (json.optString("responseCode")) {
            "ok" -> json
            "no-token", "expired-token", "invalid-token" -> throw EnergyTalkAuthException()
            else -> error("에너지톡이 조회를 완료하지 못했어요.")
        }
    }

    companion object {
        private fun text(json: JSONObject, key: String, limit: Int = 160): String? {
            if (json.isNull(key)) return null
            val value = json.get(key)
            require(value is String || value is Number)
            return value.toString().takeIf { it.length <= limit && it.none { c -> c.code < 32 } }
                ?: error("응답 형식이 바뀌었어요.")
        }
        fun parseUsage(json: JSONObject): List<EnergyTalkUsage> {
            val list = json.getJSONArray("list")
            require(list.length() <= 120)
            return (0 until list.length()).map { index ->
                val row = list.getJSONObject(index)
                val month = requireNotNull(text(row, "dateVal"))
                require(month.matches(Regex("[0-9]{4}(0[1-9]|1[0-2])")))
                EnergyTalkUsage(month, requireNotNull(text(row, "amount")), requireNotNull(text(row, "usageVal")))
            }.also { require(it.map(EnergyTalkUsage::month).distinct().size == it.size) }.sortedByDescending { it.month }
        }
        fun parseMeter(json: JSONObject): EnergyTalkMeter {
            val state = json.getString("checkYn")
            require(state == "Y" || state == "N")
            fun reading(key: String): String? = text(json, key)?.takeIf { it.isNotBlank() }?.also {
                require(it.matches(Regex("[0-9]+(?:\\.[0-9]+)?")) && it.toDoubleOrNull()?.isFinite() == true)
            }
            val submitted = listOf("submittedYn", "selfReadYn", "inputYn").firstNotNullOfOrNull { key ->
                text(json, key)?.let { value ->
                    require(value == "Y" || value == "N") { "제출 상태 형식을 확인하지 못했어요." }
                    value == "Y"
                }
            }
            return EnergyTalkMeter(state == "Y", reading("prevGuideline"), reading("recentGuideLine"),
                text(json, "checkMsg", 500), submitted)
        }
    }
}

class EnergyTalkAuthException : IllegalStateException("로그인 상태를 확인하지 못했어요. 공식 화면에서 다시 로그인해 주세요.")
