package dev.mahlernim.gasselfmeter

import okhttp3.*
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.security.MessageDigest
import java.time.LocalDate
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs

data class Contract(val bp: String, val ca: String, val name: String = "") { val label: String get() = "계약 ${ca.takeLast(4)}" }
data class HistoryProgress(val completed: Int, val total: Int, val text: String)
data class SelfReadTarget(
    val cycle: String,
    val start: String,
    val end: String,
    val eligible: Boolean,
    val submitted: Boolean,
    val submittedValue: Double?,
    val previousValue: Double?,
    internal val contract: Contract,
    internal val serial: String,
    internal val address: String,
    internal val planned: String,
    internal val vLdo: String,
    internal val installation: String,
)
/**
 * Privacy-safe receipt telemetry for the Busan SK portal. Values from the portal are never
 * retained here except the submitted meter value and the immediately preceding value.
 */
data class BusanTraceEvent(
    val stage: String,
    val httpCode: Int? = null,
    val elapsedMillis: Long? = null,
    /** Exact non-sensitive integer literal placed in cust_readingresult, for example "41". */
    val requestWireValue: String? = null,
    val serverResultCode: String = "unknown",
    val responseKeys: Set<String> = emptySet(),
    val submittedValue: Double? = null,
    val previousValue: Double? = null,
    val eligible: Boolean? = null,
    val submitted: Boolean? = null,
    val submittedValuePresent: Boolean? = null,
    val submittedValueState: String = "unknown",
    val rowResultCategory: String = "unknown",
    val rowResultValue: Double? = null,
    val previousValueSource: String? = null,
    val selfReadYn: String = "unknown",
    val eligibilityYn: String = "unknown",
    val contractMismatch: Boolean = false,
    val meterMismatch: Boolean = false,
    val cycleMismatch: Boolean = false,
    val datesMismatch: Boolean = false,
    val plannedMismatch: Boolean = false,
    val installationMismatch: Boolean = false,
    val valueMismatch: Boolean = false,
    val formUsesIsNaN: Boolean = false,
    val formUsesParseInt: Boolean = false,
    val formValidatesReading: Boolean = false,
    val formAcceptsY: Boolean = false,
    val formRejectsN: Boolean = false,
    val formAlertMessage: String? = null,
    val formAlertHash: String? = null,
    val formMapsGeraetAddr: Boolean = false,
    val formMapsLegacyAddr: Boolean = false,
    val formReadRoutes: Set<String> = emptySet(),
    val formReadParameterKeys: Set<String> = emptySet(),
    val formReadDomIds: Set<String> = emptySet(),
    val formReadFunctionNames: Set<String> = emptySet(),
    val formScriptPaths: Set<String> = emptySet(),
)

/** Static structure only. Script text, identifiers, and form values are discarded. */
data class BusanFormEvidence(
    val usesIsNaN: Boolean,
    val usesParseInt: Boolean,
    val validatesReading: Boolean,
    val acceptsY: Boolean,
    val rejectsN: Boolean,
    val genericAlertMessage: String? = null,
    val genericAlertHash: String? = null,
    val mapsGeraetAddr: Boolean = false,
    val mapsLegacyAddr: Boolean = false,
    val readRoutes: Set<String> = emptySet(),
    val readParameterKeys: Set<String> = emptySet(),
    val readDomIds: Set<String> = emptySet(),
    val readFunctionNames: Set<String> = emptySet(),
    val scriptPaths: Set<String> = emptySet(),
)

data class SubmissionReconciliation(
    val target: SelfReadTarget?,
    val confirmed: Boolean,
    val contractMismatch: Boolean = false,
    val meterMismatch: Boolean = false,
    val cycleMismatch: Boolean = false,
    val datesMismatch: Boolean = false,
    val plannedMismatch: Boolean = false,
    val installationMismatch: Boolean = false,
    val valueMismatch: Boolean = false,
)

data class SubmissionOutcome(
    val accepted: Boolean,
    val confirmed: Boolean,
    val uncertain: Boolean = false,
    /** False means the submit request did not yield a parseable portal response. */
    val responseReceived: Boolean = true,
) {
    /** `confirmed` means a matching receipt was read back, while accepted follows the portal's Y response. */
    val status: String get() = when { confirmed || accepted -> "confirmed"; uncertain -> "uncertain"; else -> "rejected" }
    val confirmationSource: String? get() = when { confirmed -> "readback"; accepted -> "provider_response"; else -> null }
}
data class SyncResult(val periods: List<UsagePeriod>, val meter: String, val planned: String?, val warning: String?, val selfRead: SelfReadTarget?)

/** Independent client with an explicit endpoint allowlist and one-shot mutations. */
class SkensClient(
    private val provider: Provider,
    private val credentials: Credentials,
    private val observer: ((BusanTraceEvent) -> Unit)? = null,
    private val verificationPause: (Long) -> Unit = { millis -> TimeUnit.MILLISECONDS.sleep(millis) },
) : AutoCloseable {
    init { require(provider.skens && provider.skensCode != null) { "지원하지 않는 자동 연결 공급사예요." } }
    private val cookies = mutableListOf<Cookie>()
    private val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .cookieJar(object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, received: List<Cookie>) {
                synchronized(cookies) { received.forEach { cookie -> cookies.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }; cookies.add(cookie) } }
            }
            override fun loadForRequest(url: HttpUrl) = synchronized(cookies) { cookies.filter { it.matches(url) && it.expiresAt > System.currentTimeMillis() } }
        }).build()
    private val allowed = setOf("login/login.do", "login/loginProcess.do", "read/selfRead.do", "read/call_EBPP_018.do", "read/insertSelfRead.do", "charge/askDetail.do")
    private fun trace(event: BusanTraceEvent) {
        runCatching { observer?.invoke(event) }
    }
    private fun stage(path: String) = when {
        path == "read/insertSelfRead.do" -> "submit_response"
        path == "read/call_EBPP_018.do" -> "meter"
        path.startsWith("login/") -> "login"
        path.startsWith("charge/") -> "history"
        else -> "portal"
    }
    private fun safeResponseKeys(body: String): Set<String> = runCatching {
        val objectBody = JSONObject(body)
        objectBody.keys().asSequence().filter { it.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,63}")) }.toSet()
    }.getOrDefault(emptySet())
    private fun safeResultCode(body: String): String = runCatching { JSONObject(body).optString("result").trim().uppercase() }
        .getOrNull()?.takeIf { it in setOf("Y", "N") } ?: "unknown"
    private fun request(path: String, data: Map<String, String>? = null, requestWireValue: String? = null): String {
        require(path in allowed)
        val requestStage = stage(path)
        val started = System.nanoTime()
        val builder = Request.Builder().url("https://ebpp.skens.com/${provider.id}/$path")
            .header("Referer", "https://ebpp.skens.com/${provider.id}/main/index.do")
        if (data != null) {
            val body = FormBody.Builder().apply { data.forEach { (k, v) -> add(k, v) } }.build()
            builder.post(if (path == "read/insertSelfRead.do" || path == "login/loginProcess.do") body.oneShot() else body)
        }
        return try {
            client.newCall(builder.build()).execute().use { response ->
                val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                if (!response.isSuccessful) {
                    trace(BusanTraceEvent(requestStage, response.code, elapsed, requestWireValue))
                    throw ProviderFailure(when {
                path.startsWith("login/") -> "login"
                path.startsWith("charge/") -> "bills"
                path == "read/insertSelfRead.do" -> "submit"
                else -> "meter"
                    }, if (response.code in setOf(401, 403)) "authentication" else "http", response.code)
                }
                val body = response.body ?: error("조회 결과가 비어 있어요.")
                check(body.contentLength() <= 4_000_000) { "조회 결과가 예상보다 커요." }
                val text = String(body.byteStream().readBytesLimited(4_000_000), Charsets.UTF_8)
                trace(BusanTraceEvent(requestStage, response.code, elapsed, requestWireValue,
                    safeResultCode(text), safeResponseKeys(text)))
                text
            }
        } catch (error: Throwable) {
            // HTTP failures are traced above. Transport failures intentionally have no synthetic code.
            if (error !is ProviderFailure) trace(BusanTraceEvent(requestStage,
                elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), requestWireValue = requestWireValue))
            throw error
        }
    }
    fun login(): List<Contract> {
        request("login/login.do")
        val result = JSONObject(request("login/loginProcess.do", mapOf("id" to credentials.username, "pw" to credentials.password, "returnURL" to "/${provider.id}/read/selfRead.do")))
        // Classified rather than a bare check so background work can tell a rejected password from
        // a transient failure and stop replaying the stored credentials.
        if (result.optString("errCd") != "S") throw ProviderFailure("login", "authentication")
        val page = request("read/selfRead.do")
        inspectSelfReadForm(page)
        return parseContracts(page).also { check(it.isNotEmpty()) { "연결된 계약이 없어요. 공급사 홈페이지에서 사용 계약을 확인해 주세요." } }
    }
    /**
     * Inspects already-loaded portal markup. Calling the public overload issues only the existing
     * read-only self-read page request, and is useful when a diagnostic screen needs fresh evidence.
     */
    fun inspectSubmissionForm(): BusanFormEvidence = inspectSelfReadForm(request("read/selfRead.do"))
    /** Compatibility name for callers that describe the page rather than its submission form. */
    fun inspectSelfReadForm(): BusanFormEvidence = inspectSubmissionForm()
    private fun inspectSelfReadForm(html: String): BusanFormEvidence {
        val evidence = analyzeSelfReadForm(html)
        trace(BusanTraceEvent("form_validation", formUsesIsNaN = evidence.usesIsNaN,
            formUsesParseInt = evidence.usesParseInt, formValidatesReading = evidence.validatesReading,
            formAcceptsY = evidence.acceptsY, formRejectsN = evidence.rejectsN,
            formAlertMessage = evidence.genericAlertMessage, formAlertHash = evidence.genericAlertHash,
            formMapsGeraetAddr = evidence.mapsGeraetAddr, formMapsLegacyAddr = evidence.mapsLegacyAddr,
            formReadRoutes = evidence.readRoutes, formReadParameterKeys = evidence.readParameterKeys,
            formReadDomIds = evidence.readDomIds, formReadFunctionNames = evidence.readFunctionNames,
            formScriptPaths = evidence.scriptPaths))
        return evidence
    }
    private fun meterRow(contract: Contract): JSONObject {
        val meterRows = JSONObject(request("read/call_EBPP_018.do", mapOf("CANO" to contract.ca, "BPNO" to contract.bp, "V" to System.currentTimeMillis().toString()))).getJSONArray("list")
        val row = if (meterRows.length() == 1) meterRows.getJSONObject(0) else null
        traceMeterSchema(row)
        check(meterRows.length() == 1) { "계량기가 여러 개이거나 없어요. 이번 버전에서는 직접 입력을 이용해 주세요." }
        return row!!
    }
    private fun safeYn(value: String) = value.trim().uppercase().takeIf { it in setOf("Y", "N") } ?: "unknown"
    private fun traceMeterSchema(row: JSONObject?) {
        val keys = row?.keys()?.asSequence()?.filter { it.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,63}")) }?.toSet().orEmpty()
        val previousSource = listOf("LAST_READINGRESULT", "HT_READINGRESULT").firstOrNull { key ->
            row?.has(key) == true && !row.isNull(key)
        }
        trace(BusanTraceEvent("meter_schema", responseKeys = keys,
            submittedValuePresent = row?.has("CUST_READING_RESULT") == true && !row.isNull("CUST_READING_RESULT"),
            submittedValueState = submittedValueState(row), rowResultCategory = rowResultCategory(row),
            rowResultValue = rowResultValue(row),
            previousValueSource = previousSource,
            selfReadYn = safeYn(row?.optString("SELF_READ_YN").orEmpty()),
            eligibilityYn = safeYn(row?.optString("selfReadYn").orEmpty())))
    }
    private fun submittedValueState(row: JSONObject?): String {
        if (row?.has("CUST_READING_RESULT") != true || row.isNull("CUST_READING_RESULT")) return "absent"
        val raw = row.optString("CUST_READING_RESULT").trim().replace(",", "")
        if (raw.isEmpty()) return "empty"
        val number = raw.toDoubleOrNull() ?: return "non_numeric"
        return when { number > 0 -> "positive"; number == 0.0 -> "zero"; else -> "non_numeric" }
    }
    private fun rowResultCategory(row: JSONObject?): String {
        val raw = row?.optString("RESULT")?.trim().orEmpty()
        if (raw.isEmpty()) return "empty"
        if (raw.matches(Regex("[+-]?\\d+(?:\\.\\d+)?"))) return "numeric"
        return raw.uppercase().takeIf { it in setOf("Y", "N", "S", "E") } ?: "other"
    }
    private fun rowResultValue(row: JSONObject?): Double? = row?.optString("RESULT")?.trim()?.toDoubleOrNull()
        ?.takeIf { it.isFinite() && it in 0.0..99_999_999.0 }
    private fun address(row: JSONObject): String {
        val primary = row.optString("GERAET_ADDR").takeIf { it.isNotBlank() && it != "NULL" }
        val legacy = row.optString("ADDR").takeIf { it.isNotBlank() && it != "NULL" }
        check(primary != null || legacy != null) { "계량기 주소 정보 형식이 바뀌었어요." }
        check(primary == null || legacy == null || primary == legacy) { "계량기 주소 정보가 일치하지 않아요." }
        return primary ?: legacy!!
    }
    private fun serial(row: JSONObject): String {
        val meterA = row.optString("CERAET").takeIf { it.isNotBlank() && it != "NULL" }
        val meterB = row.optString("GERAET").takeIf { it.isNotBlank() && it != "NULL" }
        check(meterA != null || meterB != null) { "계량기 정보 형식이 바뀌었어요." }
        check(meterA == null || meterB == null || meterA == meterB) { "계량기 정보가 일치하지 않아요." }
        return meterA ?: meterB!!
    }
    private fun parseSelfReadTarget(meterRow: JSONObject, contract: Contract): SelfReadTarget? {
        val startRaw = meterRow.optString("START_DATE").takeIf { it.isNotBlank() } ?: return null
        val endRaw = meterRow.optString("END_DATE").takeIf { it.isNotBlank() } ?: return null
        val serial = serial(meterRow)
        val start = parsePortalDate(startRaw)
        val end = parsePortalDate(endRaw)
        check(LocalDate.parse(start) <= LocalDate.parse(end)) { "검침 가능 기간을 확인하지 못했어요." }
        val submittedValue = meterRow.optString("CUST_READING_RESULT").replace(",", "").toDoubleOrNull()?.takeIf { it > 0 }
        val previous = listOf("LAST_READINGRESULT", "HT_READINGRESULT").firstNotNullOfOrNull { key ->
            meterRow.optString(key).replace(",", "").toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..99_999_999.0 }
        }
        return SelfReadTarget(
            cycle = "$start:$end:${opaque(serial)}", start = start, end = end,
            eligible = meterRow.optString("selfReadYn").trim().equals("Y", true) && previous != null &&
                meterRow.optString("SELF_READ_YN").trim().uppercase() in setOf("Y", "N"),
            submitted = meterRow.optString("SELF_READ_YN").equals("Y", true) || submittedValue != null,
            submittedValue = submittedValue, previousValue = previous, contract = contract, serial = serial,
            address = address(meterRow), planned = meterRow.optString("ADATSOLL1"),
            vLdo = meterRow.optString("V_LDO"), installation = meterRow.optString("ANLAGE")
        )
    }
    fun selfReadTarget(contract: Contract): SelfReadTarget {
        val row = meterRow(contract)
        val target = parseSelfReadTarget(row, contract)
            ?: error("공급사에서 검침 가능 기간을 제공하지 않았어요.")
        trace(BusanTraceEvent("meter_state", submittedValue = target.submittedValue, previousValue = target.previousValue,
            eligible = target.eligible, submitted = target.submitted,
            submittedValuePresent = row.has("CUST_READING_RESULT") && !row.isNull("CUST_READING_RESULT"),
            submittedValueState = submittedValueState(row), rowResultCategory = rowResultCategory(row),
            rowResultValue = rowResultValue(row),
            previousValueSource = listOf("LAST_READINGRESULT", "HT_READINGRESULT").firstOrNull { row.has(it) && !row.isNull(it) },
            selfReadYn = safeYn(row.optString("SELF_READ_YN")), eligibilityYn = safeYn(row.optString("selfReadYn"))))
        return target
    }
    fun history(contract: Contract, knownMonths: Set<String> = emptySet(), progress: (HistoryProgress) -> Unit): SyncResult {
        val meterRow = meterRow(contract)
        val meter = opaque(serial(meterRow))
        val planned = meterRow.optString("ADATSOLL1").takeIf { it.isNotBlank() }?.let(::parsePortalDate)
        val target = parseSelfReadTarget(meterRow, contract)
        val params = mapOf("bpno" to contract.bp, "cano" to contract.ca, "compcd" to provider.skensCode!!, "GUBUN" to "02")
        val first = request("charge/askDetail.do", params + ("date" to ""))
        // One parse. document() still performs the login-form and error-page checks.
        val selected = document(first).selectFirst("#budat")?.attr("value")
        val months = (Regex("fnGetAskDetail\\([^)]*[\"'](20\\d{4})[\"']").findAll(first).map { it.groupValues[1] }.toSet() + listOfNotNull(selected?.takeIf { it.matches(Regex("20\\d{4}")) })).sortedDescending().take(25)
        check(months.isNotEmpty()) { "청구 이력을 찾지 못했어요. 공급사 페이지가 변경되었을 수 있어요." }
        val wanted = months.filter { it !in knownMonths || it == selected }
        val executor = Executors.newFixedThreadPool(2)
        var completed = 0
        val fetched = try {
            val completion = ExecutorCompletionService<Result<List<UsagePeriod>>>(executor)
            wanted.forEach { month -> completion.submit(Callable {
                runCatching {
                    val html = if (selected == month) first else request("charge/askDetail.do", params + ("date" to month))
                    parseBill(html, month)
                }
            }) }
            List(wanted.size) {
                completion.take().get().also {
                    completed++
                    progress(HistoryProgress(completed, wanted.size, "청구 이력 $completed/${wanted.size} 가져오는 중"))
                }
            }
        } finally { executor.shutdownNow() }
        val periods = fetched.flatMap { it.getOrElse { emptyList() } }
        val failed = fetched.count { it.isFailure }
        check(periods.isNotEmpty()) { "청구 이력을 읽지 못했어요. 사이트 변경 또는 일시적인 오류일 수 있어요." }
        Estimator.validatePeriods(periods)
        return SyncResult(periods.sortedBy { it.start }, meter, planned,
            if (failed > 0) "$failed 개월은 읽지 못했어요. 기존 이력은 유지했고, 다시 새로고침할 수 있어요." else null, target)
    }
    fun reconcile(target: SelfReadTarget, value: Double): SubmissionReconciliation {
        val refreshed = runCatching { selfReadTarget(target.contract) }.getOrNull()
        val result = reconciliation(target, refreshed, value)
        trace(BusanTraceEvent("reconcile", submittedValue = refreshed?.submittedValue, previousValue = refreshed?.previousValue,
            eligible = refreshed?.eligible, submitted = refreshed?.submitted, contractMismatch = result.contractMismatch,
            meterMismatch = result.meterMismatch, cycleMismatch = result.cycleMismatch,
            datesMismatch = result.datesMismatch, plannedMismatch = result.plannedMismatch,
            installationMismatch = result.installationMismatch, valueMismatch = result.valueMismatch))
        return result
    }
    fun submitReading(target: SelfReadTarget, value: Double): SubmissionOutcome {
        val reading = SubmissionReading.wire(value)
        require(target.eligible) { "자가검침 대상 계약이 아니에요." }
        require(!target.submitted) { "이번 검침값은 이미 제출되어 있어요." }
        require(today() in LocalDate.parse(target.start)..LocalDate.parse(target.end)) { "현재는 검침값 입력 기간이 아니에요." }
        require(target.previousValue != null && target.previousValue.isFinite() && value >= target.previousValue) { "이전 검침값과 제출할 값을 확인해 주세요." }
        trace(BusanTraceEvent("submit_dispatch", requestWireValue = reading, submittedValue = value,
            previousValue = target.previousValue, eligible = target.eligible, submitted = target.submitted))
        val response = runCatching { JSONObject(request("read/insertSelfRead.do", mapOf(
            "bpno" to target.contract.bp, "name" to target.contract.name, "cano" to target.contract.ca,
            "sernr" to target.serial, "addr" to target.address,
            "cust_readingresult" to reading, "adatsoll1" to target.planned,
            "v_ldo" to target.vLdo, "anlage" to target.installation
        ), reading)) }.onFailure {
            trace(BusanTraceEvent("submit_failure", requestWireValue = reading, submittedValue = value))
        }.getOrNull()
        val result = response?.optString("result")?.trim()?.uppercase()?.takeIf { it in setOf("Y", "N") }
        if (result == "N") return SubmissionOutcome(false, false, responseReceived = true)
        // A portal Y is the same successful outcome shown by the official web page. The app's
        // ordinary path returns promptly while an attached diagnostic observer may collect receipts.
        if (result == "Y" && observer == null) return SubmissionOutcome(true, false, responseReceived = true)
        // A single mutation is followed by at most three bounded, read-only receipt checks.
        var confirmed = false
        for (pause in listOf(0L, 2_000L, 3_000L)) {
            if (pause > 0) verificationPause(pause)
            if (reconcile(target, value).confirmed) {
                confirmed = true
                break
            }
        }
        return SubmissionOutcome(result == "Y", confirmed, uncertain = !confirmed && result != "N",
            responseReceived = response != null)
    }
    override fun close() { synchronized(cookies) { cookies.clear() }; client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }

    companion object {
        internal fun analyzeSelfReadForm(html: String): BusanFormEvidence {
            // The page itself is never persisted or exposed. Restrict analysis to script structure.
            val scripts = Jsoup.parse(html).select("script").joinToString("\n") { it.data() }
            val readingContext = Regex("(?is).{0,240}cust_readingresult.{0,240}").findAll(scripts).map { it.value }.toList()
            val usesIsNaN = readingContext.any { Regex("\\bisNaN\\s*\\(").containsMatchIn(it) }
            val usesParseInt = readingContext.any { Regex("\\bparseInt\\s*\\(").containsMatchIn(it) }
            val validatesReading = usesIsNaN || usesParseInt
            val submitContext = Regex("(?is).{0,500}(?:insertSelfRead|result).{0,500}").findAll(scripts).map { it.value }.toList()
            val acceptsY = submitContext.any { Regex("(?:result|data\\.result)\\s*(?:===|==)\\s*['\"]Y['\"]").containsMatchIn(it) }
            val rejectsN = submitContext.any { Regex("(?:result|data\\.result)\\s*(?:===|==)\\s*['\"]N['\"]").containsMatchIn(it) }
            val formScripts = Jsoup.parse(html).select("script").map { it.data() }
                .filter { it.contains("cust_readingresult") || it.contains("insertSelfRead") }
                .joinToString("\n")
            val alert = Regex("alert\\s*\\(\\s*['\"]([^'\"\\r\\n]{1,120})['\"]\\s*\\)").findAll(formScripts)
                .map { it.groupValues[1].trim() }
                .firstOrNull { it in setOf("저장하였습니다.") }
            val mapsGeraetAddr = Regex("\\bGERAET_ADDR\\b").containsMatchIn(formScripts)
            val mapsLegacyAddr = Regex("\\bADDR\\b").containsMatchIn(formScripts)
            val structure = analyzeReadStructure(html)
            return BusanFormEvidence(usesIsNaN, usesParseInt, validatesReading, acceptsY, rejectsN,
                alert, alert?.let(::opaque), mapsGeraetAddr, mapsLegacyAddr,
                structure.routes, structure.parameterKeys, structure.domIds, structure.functionNames, structure.scriptPaths)
        }

        /** Structural metadata only. It never returns script text, values, identifiers, or credentials. */
        private data class ReadStructure(
            val routes: Set<String>, val parameterKeys: Set<String>, val domIds: Set<String>,
            val functionNames: Set<String>, val scriptPaths: Set<String>,
        )
        private fun analyzeReadStructure(html: String): ReadStructure {
            val doc = Jsoup.parse(html)
            val inline = doc.select("script:not([src])").joinToString("\n") { it.data() }
            val literals = Regex("['\"]([^'\"\\r\\n]{1,160})['\"]").findAll(inline).map { it.groupValues[1] }.toList()
            val routes = literals.mapNotNull(::safeReadRoute).toSortedSet().take(16).toSet()
            val routeContext = routes.flatMap { route ->
                Regex("(?is).{0,400}" + Regex.escape(route.removePrefix("/")) + ".{0,400}").findAll(inline).map { it.value }.toList()
            }.joinToString("\n")
            val parameterKeys = Regex("(?:[,{]\\s*)([A-Za-z_][A-Za-z0-9_]{0,47})\\s*:").findAll(routeContext)
                .map { it.groupValues[1] }.filter(::safeParameterKey).toSortedSet().take(24).toSet()
            val domIds = Regex("(?:getElementById\\s*\\(\\s*|\\$\\s*\\(\\s*)['\"]#?([A-Za-z][A-Za-z0-9_-]{0,63})['\"]")
                .findAll(inline).map { it.groupValues[1] }.filter { id -> id.contains(Regex("(?i)(read|result|meter|geraet)")) }
                .toSortedSet().take(16).toSet()
            val functionNames = Regex("(?:\\bfunction\\s+|\\b(?:var|let|const)?\\s*)([A-Za-z_$][A-Za-z0-9_$]{0,63})\\s*(?:=\\s*function\\b|\\()")
                .findAll(inline).map { it.groupValues[1] }.filter { name -> name.contains(Regex("(?i)(read|result|meter|call|ask|search)")) }
                .toSortedSet().take(16).toSet()
            val scriptPaths = doc.select("script[src]").mapNotNull { safeOfficialScriptPath(it.attr("src")) }.toSortedSet().take(16).toSet()
            return ReadStructure(routes, parameterKeys, domIds, functionNames, scriptPaths)
        }
        private fun safeReadRoute(value: String): String? {
            val path = when {
                value.matches(Regex("(?:call|ask|get|search)[A-Za-z0-9_-]*\\.do", RegexOption.IGNORE_CASE)) -> "/read/$value"
                value.matches(Regex("(?:[A-Za-z0-9_-]+/){1,5}[A-Za-z0-9_-]+\\.do")) -> "/$value"
                value.matches(Regex("/(?:[A-Za-z0-9_-]+/){0,5}[A-Za-z0-9_-]+\\.do")) -> value
                value.matches(Regex("https://ebpp\\.skens\\.com/(?:[A-Za-z0-9_-]+/){1,6}[A-Za-z0-9_-]+\\.do")) -> "/" + value.substringAfter("ebpp.skens.com/")
                else -> return null
            }
            return path.takeIf { it.matches(Regex("/(?:[A-Za-z0-9_-]+/)*read/[A-Za-z0-9_-]+\\.do")) &&
                !it.contains(Regex("(?i)(insert|save|delete|update|cancel)")) }
        }
        private fun safeParameterKey(value: String): Boolean = value.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,47}")) &&
            !value.contains(Regex("(?i)(password|passwd|token|cookie|session|auth|^pw$|^id$)"))
        private fun safeOfficialScriptPath(value: String): String? {
            val path = when {
                value.matches(Regex("/(?:[A-Za-z0-9._-]+/){0,6}[A-Za-z0-9._-]+\\.js")) -> value
                value.matches(Regex("https://ebpp\\.skens\\.com/(?:[A-Za-z0-9._-]+/){0,6}[A-Za-z0-9._-]+\\.js")) -> "/" + value.substringAfter("ebpp.skens.com/")
                else -> return null
            }
            return path.takeIf { it.length <= 160 }
        }
        internal fun reconciliation(target: SelfReadTarget, refreshed: SelfReadTarget?, value: Double): SubmissionReconciliation {
            if (refreshed == null) return SubmissionReconciliation(null, false, valueMismatch = true)
            val contractMismatch = refreshed.contract.bp != target.contract.bp || refreshed.contract.ca != target.contract.ca
            val meterMismatch = refreshed.serial != target.serial
            val cycleMismatch = refreshed.cycle != target.cycle
            val datesMismatch = refreshed.start != target.start || refreshed.end != target.end
            val plannedMismatch = refreshed.planned != target.planned
            val installationMismatch = refreshed.installation != target.installation
            val valueMismatch = !refreshed.submitted || refreshed.submittedValue?.let { it.isFinite() && abs(it - value) < .001 } != true
            return SubmissionReconciliation(refreshed, !(contractMismatch || meterMismatch || cycleMismatch || datesMismatch || plannedMismatch || installationMismatch || valueMismatch),
                contractMismatch, meterMismatch, cycleMismatch, datesMismatch, plannedMismatch, installationMismatch, valueMismatch)
        }
        internal fun confirmsSubmission(target: SelfReadTarget, refreshed: SelfReadTarget, value: Double): Boolean =
            reconciliation(target, refreshed, value).confirmed

        fun contractKey(provider: Provider, contract: Contract) = opaque("${provider.skensCode}:${contract.bp}:${contract.ca}")
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
                val suffix = node.id().substringAfterLast('_')
                Contract(bp, ca, doc.selectFirst("#list_bpname_$suffix")?.attr("value").orEmpty())
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
