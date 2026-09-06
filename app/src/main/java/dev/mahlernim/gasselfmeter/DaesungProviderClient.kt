package dev.mahlernim.gasselfmeter

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.floor

/** Runtime labelled-form adapter. Each Daesung company retains its own origin and meter path. */
class DaesungProviderClient internal constructor(
    private val providerId: String,
    private val credentials: Credentials,
    private val baseUrl: HttpUrl = config(providerId).origin.toHttpUrl(),
    transport: OkHttpClient = OkHttpClient(),
) : DirectProviderClient {
    private data class Config(val origin: String, val meterPath: String)
    private data class ContractState(val selector: String, val value: String)
    private data class SubmitForm(val action: String, val fields: Map<String, String>, val reading: String)
    private val state = mutableMapOf<String, ContractState>()
    private val forms = mutableMapOf<String, SubmitForm>()
    private val cookies = mutableListOf<Cookie>()
    private var loggedIn = false
    private val client = transport.newBuilder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .cookieJar(object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, received: List<Cookie>) {
                if (!sameOrigin(url)) return
                synchronized(cookies) { received.forEach { c -> cookies.removeAll { it.name == c.name && it.path == c.path }; cookies += c } }
            }
            override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(cookies) {
                if (sameOrigin(url)) cookies.filter { it.expiresAt > System.currentTimeMillis() && it.matches(url) } else emptyList()
            }
        }).build()

    override fun login(): List<DirectContract> = stage("login") {
        state.clear(); forms.clear(); loggedIn = false
        val login = doc(get("/users/login", "login"), "login")
        val form = login.select("form").singleOrNull {
            it.attr("method").equals("post", true) && samePath(it.attr("action"), "/users/login")
        } ?: fail("login", "unsupported")
        if (!form.select("input[name=id][type!=hidden]").any() || !form.select("input[name=password][type=password]").any()) fail("login", "unsupported")
        val body = FormBody.Builder().apply {
            form.select("input[type=hidden][name]").filter { it.attr("name") !in setOf("id", "password", "returl") }
                .forEach { add(it.attr("name"), it.attr("value")) }
            add("id", credentials.username); add("password", credentials.password); add("returl", "/charge/month")
        }.build()
        val reply = request(Request.Builder().url(url("/users/login")).header("Origin", baseUrl.toString().trimEnd('/'))
            .header("Referer", url("/users/login").toString()).post(body.oneShot()).build(), "login")
        if (reply.code in redirects) redirect(reply.location, "login")
        authentication(reply.body, "login")
        val contracts = contracts(get("/charge/month", "contracts"))
        loggedIn = true
        contracts
    }

    override fun read(contract: DirectContract): DirectSnapshot = stage("bills") {
        check(loggedIn) { "로그인 후 계약을 선택해 주세요." }
        val selected = state[contract.id] ?: fail("contracts", "validation")
        val bills = bills(page("/charge/month", selected, "bills"))
        val meter = target(page(config(providerId).meterPath, selected, "meter"), contract, selected)
        DirectSnapshot(contract.copy(meterId = meter?.serial), bills, meter?.target)
    }

    override fun submit(contract: DirectContract, target: SelfReadTarget, value: Double) = stage("submit") {
        require(value.isFinite() && value in 0.0..99_999.0 && value == floor(value)) { "제출할 검침값을 확인해 주세요." }
        val fresh = read(contract).target ?: fail("submit", "validation")
        require(sameTarget(target, fresh) && fresh.eligible && !fresh.submitted && fresh.previousValue != null && value >= fresh.previousValue) {
            "검침 대상과 값을 다시 확인해 주세요."
        }
        require(today() in LocalDate.parse(fresh.start)..LocalDate.parse(fresh.end)) { "현재는 검침값 입력 기간이 아니에요." }
        val form = forms[fresh.cycle] ?: fail("submit", "validation")
        val body = FormBody.Builder().apply { form.fields.forEach { (k, v) -> add(k, v) }; add(form.reading, value.toLong().toString()) }.build()
        val reply = request(Request.Builder().url(url(form.action)).header("Origin", baseUrl.toString().trimEnd('/'))
            .header("Referer", url(config(providerId).meterPath).toString()).post(body.oneShot()).build(), "submit")
        if (reply.code in redirects || reply.body.contains("로그인", true)) fail("submit", "uncertain")
        val checked = read(contract).target ?: fail("submit", "uncertain")
        if (!sameTarget(fresh, checked) || !checked.submitted || checked.submittedValue?.let { abs(it - value) < .001 } != true)
            fail("submit", "uncertain")
    }

    override fun close() { synchronized(cookies) { cookies.clear() }; client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }

    private fun contracts(html: String): List<DirectContract> {
        val document = doc(html, "contracts")
        val controls = document.select("select[name],input[name]").filter { contractLabel.matches(label(document, it)) }
        if (controls.size != 1) fail("contracts", "unsupported")
        val control = controls.single()
        val choices = if (control.tagName() == "select") control.select("option") else listOf(control)
        val result = choices.mapNotNull {
            val value = it.attr("value").trim()
            if (!value.matches(Regex("[A-Za-z0-9_-]{1,40}"))) null else DirectContract(value, it.text().trim().ifBlank { "계약 " + value.takeLast(4) })
        }
        if (result.isEmpty() || result.distinctBy { it.id }.size != result.size) fail("contracts", "unsupported")
        result.forEach { state[it.id] = ContractState(control.attr("name"), it.id) }
        return result
    }

    private fun page(path: String, contract: ContractState, stage: String): String {
        val html = get(path + "?" + URLEncoder.encode(contract.selector, "UTF-8") + "=" + URLEncoder.encode(contract.value, "UTF-8"), stage)
        val document = doc(html, stage)
        val selected = document.select("[name=" + contract.selector + "]").flatMap {
            if (it.tagName() == "select") it.select("option[selected]").map { option -> option.attr("value") } else listOf(it.attr("value"))
        }.toSet()
        if (selected != setOf(contract.value)) fail(stage, "unsupported")
        return html
    }

    private fun bills(html: String): List<DirectBill> {
        val result = mutableListOf<DirectBill>()
        doc(html, "bills").select("table").forEach { table ->
            val rows = table.select("tr")
            val headings = rows.firstOrNull()?.select("th,td")?.map { normal(it.text()) }.orEmpty()
            val monthAt = headings.indexOfFirst { it.matches(Regex(".*(청구월|사용월|년월|고지월).*")) }
            if (monthAt < 0) return@forEach
            val usageAt = headings.indexOfFirst { it.contains("사용량") }
            val amountAt = headings.indexOfFirst { it.matches(Regex(".*(청구금액|고지금액|합계금액|당월요금).*")) }
            val previousAt = headings.indexOfFirst { it.matches(Regex(".*(전월지침|이전지침|전회지침).*")) }
            val currentAt = headings.indexOfFirst { it.matches(Regex(".*(당월지침|현재지침|금월지침).*")) }
            rows.drop(1).forEach { row ->
                val cells = row.children().filter { it.tagName() == "td" || it.tagName() == "th" }.map { it.text().trim() }
                if (cells.size != headings.size) return@forEach
                val month = month(cells[monthAt]) ?: return@forEach
                result += DirectBill(month, at(cells, usageAt), at(cells, amountAt), previous = at(cells, previousAt), current = at(cells, currentAt))
            }
        }
        val hasLedger = doc(html, "bills").select("table").any { table ->
            table.select("tr").firstOrNull()?.select("th,td")?.map { normal(it.text()) }?.any {
                it.matches(Regex(".*(청구월|사용월|년월|고지월).*"))
            } == true
        }
        if (!hasLedger || result.distinctBy { it.month }.size != result.size) fail("bills", "unsupported")
        return result.sortedBy { it.month }
    }

    private data class Parsed(val target: SelfReadTarget, val serial: String)
    private fun target(html: String, contract: DirectContract, state: ContractState): Parsed? {
        val document = doc(html, "meter")
        val values = values(document)
        val rawPeriod = unique(values, Regex(".*(검침기간|입력기간).*"), "meter") ?: return null
        val dates = date.findAll(rawPeriod).map { match -> match.groupValues[1] + "-" + match.groupValues[2].padStart(2, '0') + "-" + match.groupValues[3].padStart(2, '0') }.toList()
        if (dates.size != 2 || dates[0] > dates[1]) fail("meter", "unsupported")
        val previous = unique(values, Regex(".*(전월지침|이전지침|전회지침).*"), "meter")?.let { strictNumber(it, "meter") } ?: return null
        val serial = unique(values, Regex(".*(계량기번호|기물번호).*"), "meter") ?: return null
        val submitted = unique(values, Regex(".*(등록지침|제출지침|입력완료지침).*"), "meter", false)?.let { strictNumber(it, "meter") }
        val cycle = SkensClient.opaque(providerId + ":" + contract.id + ":" + serial + ":" + dates.joinToString(":"))
        val base = SelfReadTarget(cycle, dates[0], dates[1], false, submitted != null, submitted, previous,
            Contract(contract.id, providerId, contract.label), serial, "", "", "", DirectIdentity.meter(providerId, contract.id, serial))
        val form = submissionForm(document, state)
        if (form != null && submitted == null) { forms[cycle] = form; return Parsed(base.copy(eligible = true), serial) }
        return Parsed(base, serial)
    }

    private fun submissionForm(document: Document, state: ContractState): SubmitForm? {
        val found = document.select("form").mapNotNull { form ->
            if (!form.attr("method").equals("post", true)) return@mapNotNull null
            val input = form.select("input[name]").singleOrNull {
                !it.hasAttr("disabled") && !it.hasAttr("readonly") && it.attr("type") !in setOf("hidden", "submit", "button") &&
                    readingLabel.matches(label(document, it)) && it.attr("value").isBlank()
            } ?: return@mapNotNull null
            val action = meterAction(form.attr("action")) ?: return@mapNotNull null
            val selected = form.select("[name=" + state.selector + "]").flatMap {
                if (it.tagName() == "select") it.select("option[selected]").map { option -> option.attr("value") } else listOf(it.attr("value"))
            }.toSet()
            if (selected != setOf(state.value)) return@mapNotNull null
            val submit = form.select("button,input[type=submit]").singleOrNull {
                val text = it.text() + it.attr("value")
                !it.hasAttr("disabled") && text.contains("검침") && Regex(".*(등록|입력|저장).*").matches(text) && !forbidden.containsMatchIn(text)
            } ?: return@mapNotNull null
            val fields = linkedMapOf<String, String>()
            form.select("input[type=hidden][name]").forEach { fields[it.attr("name")] = it.attr("value") }
            fields[state.selector] = state.value
            submit.attr("name").takeIf { it.isNotBlank() }?.let { fields[it] = submit.attr("value") }
            SubmitForm(action.encodedPath + (action.encodedQuery?.let { "?" + it } ?: ""), fields, input.attr("name"))
        }
        return found.singleOrNull()
    }

    private fun get(path: String, stage: String): String {
        val reply = request(Request.Builder().url(url(path)).get().build(), stage)
        if (reply.code in redirects) {
            val destination = redirect(reply.location, stage)
            if (destination.encodedPath == "/users/login") fail(stage, "authentication")
            fail(stage, "unsupported")
        }
        return reply.body
    }
    private data class Reply(val code: Int, val body: String, val location: String?)
    private fun request(request: Request, stage: String): Reply = client.newCall(request).execute().use { response ->
        if (!sameOrigin(request.url)) fail(stage, "validation")
        if (response.code !in 200..299 && response.code !in redirects) throw ProviderFailure(stage, if (response.code in setOf(401, 403)) "authentication" else "http", response.code)
        val body = response.body ?: fail(stage, "unsupported")
        Reply(response.code, String(body.byteStream().readBytesLimited(maxBytes), body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8), response.header("Location"))
    }
    private fun doc(html: String, stage: String): Document = Jsoup.parse(html, baseUrl.toString()).also { authentication(html, stage) }
    private fun authentication(html: String, stage: String) {
        if (html.contains("아이디 또는 비밀번호가 일치하지 않습니다") || html.contains("로그인 되어 있지 않습니다") ||
            (stage != "login" && Jsoup.parse(html).select("input[type=password]").isNotEmpty())) fail(stage, "authentication")
    }
    private fun url(path: String): HttpUrl = baseUrl.resolve(path)?.takeIf(::sameOrigin) ?: fail("validation", "validation")
    private fun sameOrigin(value: HttpUrl) = value.scheme == baseUrl.scheme && value.host == baseUrl.host && value.port == baseUrl.port
    private fun samePath(value: String, expected: String) = runCatching { url(value).encodedPath == expected }.getOrDefault(false)
    private fun redirect(value: String?, stage: String): HttpUrl = value?.let(::url)?.takeIf { it.username.isEmpty() && it.password.isEmpty() } ?: fail(stage, "unsupported")
    private fun meterAction(value: String): HttpUrl? = runCatching {
        url(config(providerId).meterPath).resolve(value)?.takeIf { sameOrigin(it) }?.takeIf {
            it.encodedPath == config(providerId).meterPath || it.encodedPath.startsWith(config(providerId).meterPath + "/")
        }?.takeIf { !forbidden.containsMatchIn(it.encodedPath) }
    }.getOrNull()
    private fun label(document: Document, input: Element): String {
        val text = mutableListOf(input.attr("title"), input.attr("aria-label"), input.attr("placeholder"))
        input.id().takeIf { it.isNotBlank() }?.let { text += document.select("label[for=" + it + "]").map { label -> label.text() } }
        input.parents().firstOrNull { it.tagName() == "label" }?.let { text += it.text() }
        input.parents().firstOrNull { it.tagName() == "td" }?.previousElementSibling()?.takeIf { it.tagName() == "th" }?.let { text += it.text() }
        return normal(text.joinToString(" "))
    }
    private fun values(document: Document): Map<String, String> = buildMap {
        document.select("tr").forEach { row ->
            val cells = row.children().filter { it.tagName() == "th" || it.tagName() == "td" }
            cells.zipWithNext().filter { it.first.tagName() == "th" }.forEach { (heading, value) -> put(normal(heading.text()), value.selectFirst("input")?.attr("value") ?: value.text().trim()) }
        }
        document.select("input[name]").forEach { input -> label(document, input).takeIf { it.isNotBlank() }?.let { put(it, input.attr("value")) } }
    }
    private fun unique(values: Map<String, String>, label: Regex, stage: String, required: Boolean = true): String? {
        val found = values.filterKeys { label.matches(it) }.values.map(String::trim).filter(String::isNotBlank).toSet()
        if (found.size == 1) return found.single()
        if (!required && found.isEmpty()) return null
        fail(stage, "unsupported")
    }
    private fun at(cells: List<String>, index: Int): Double? = if (index >= 0) number(cells[index]) else null
    private fun number(text: String): Double? = numberPattern.matchEntire(text)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()
        ?.takeIf { it.isFinite() && it in 0.0..99_999_999.0 }
    private fun strictNumber(text: String, stage: String): Double = number(text) ?: fail(stage, "unsupported")
    private fun month(text: String): String? {
        val value = text.filter(Char::isDigit)
        return if (value.matches(Regex("20\\d{2}(0[1-9]|1[0-2])"))) value.take(4) + "-" + value.takeLast(2) else null
    }
    private fun sameTarget(a: SelfReadTarget, b: SelfReadTarget) = a.cycle == b.cycle && a.serial == b.serial && a.start == b.start && a.end == b.end &&
        a.previousValue == b.previousValue && a.installation == b.installation && a.contract.bp == b.contract.bp && a.contract.ca == b.contract.ca
    private fun normal(text: String) = text.replace(Regex("\\s+"), "")
    private inline fun <T> stage(name: String, block: () -> T): T = try { block() } catch (failure: ProviderFailure) { throw failure } catch (failure: Exception) {
        throw ProviderFailure(name, when (failure) { is java.net.SocketTimeoutException -> "timeout"; is javax.net.ssl.SSLException -> "tls"; is java.io.IOException -> "network"; else -> "parse" }, cause = failure)
    }
    private fun fail(stage: String, category: String): Nothing = throw ProviderFailure(stage, category)
    companion object {
        private const val maxBytes = 4_000_000
        private val redirects = setOf(301, 302, 303, 307, 308)
        private val contractLabel = Regex(".*(고객번호|사용계약번호|납부자번호|계약번호|수용가번호).*")
        private val readingLabel = Regex(".*(당월지침|현재지침|금월지침|검침지침|자가검침값|검침값).*")
        private val forbidden = Regex(".*(해지|취소|결제|납부|가입|신청|cancel|request|payment|enroll).*", RegexOption.IGNORE_CASE)
        private val date = Regex("(20\\d{2})[.년/-]\\s*(\\d{1,2})[.월/-]\\s*(\\d{1,2})")
        private val numberPattern = Regex("\\s*((?:0|[1-9]\\d{0,2}(?:,\\d{3})*|[1-9]\\d*)(?:\\.\\d+)?)\\s*(?:원|㎥|m³|m3)?\\s*")
        private fun config(id: String) = when (id) {
            "daesung" -> Config("https://cyber.daesungenergy.com", "/consult/self")
            "daesungclean" -> Config("https://www.daesungcleanenergy.co.kr", "/service/self_input")
            else -> throw IllegalArgumentException("대성 계열 연결 대상이 아니에요.")
        }
    }
}
