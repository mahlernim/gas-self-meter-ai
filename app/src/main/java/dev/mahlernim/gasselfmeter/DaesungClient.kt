package dev.mahlernim.gasselfmeter

import okhttp3.Cookie
import okhttp3.Call
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Compatibility observations only. No customer identifiers, readings or imported bills. */
data class DaesungProbeResult(
    val sessionStructureObserved: Boolean,
    val billingPageReached: Boolean,
    val tableCount: Int,
    val recognizedBillingColumns: List<String>,
)

/** Explicit user-initiated alpha test. Credentials and cookies exist only for this probe. */
class DaesungReadProbe internal constructor(
    providerId: String,
    private val baseUrl: HttpUrl = portal(providerId),
    transport: OkHttpClient = OkHttpClient(),
) : AutoCloseable {
    private val config = configuration(providerId)
    private val cookies = mutableListOf<Cookie>()
    private val callLock = Any()
    private var activeCall: Call? = null
    private val credentialPostSent = AtomicBoolean(false)
    private val client = transport.newBuilder()
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .authenticator(okhttp3.Authenticator.NONE).proxyAuthenticator(okhttp3.Authenticator.NONE)
        .addNetworkInterceptor { chain ->
            if (closed || chain.call().isCanceled()) throw java.io.IOException("Probe cancelled")
            if (chain.request().method == "POST" && !credentialPostSent.compareAndSet(false, true)) {
                throw java.io.IOException("Credential request replay blocked")
            }
            chain.proceed(chain.request())
        }
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .cookieJar(object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, incoming: List<Cookie>) {
                if (!sameOrigin(url)) return
                synchronized(cookies) {
                    incoming.filter { it.matches(baseUrl.resolve(config.monthPath)!!) }.forEach { cookie ->
                        cookies.removeAll { it.name == cookie.name && it.path == cookie.path }
                        cookies.add(cookie)
                    }
                }
            }
            override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(cookies) {
                if (!sameOrigin(url)) emptyList() else cookies.filter { it.expiresAt > System.currentTimeMillis() && it.matches(url) }
            }
        }).build()

    @Volatile private var closed = false
    @Volatile private var used = false

    /** A probe is single use. UI must obtain consent before calling this method. */
    @Synchronized fun check(username: String, password: String): DaesungProbeResult {
        check(!closed && !used) { "이 연결 테스트는 이미 종료되었어요." }
        used = true
        try {
            if (username.isBlank() || password.isBlank()) throw ProviderFailure("login", "validation")
            atStage("login") {
                val formPage = get(config.loginPath, "login")
                val document = Jsoup.parse(formPage, baseUrl.toString())
                val form = document.select("form").singleOrNull { form ->
                    form.attr("method").equals("post", true) &&
                        baseUrl.resolve(form.attr("action")) == baseUrl.resolve(config.loginPath)
                } ?: throw ProviderFailure("login", "unsupported")
                val names = form.select("input[name]").map { it.attr("name") }.toSet()
                if (names != setOf("returl", "id", "password") || form.select("input[name=password][type=password]").size != 1) {
                    throw ProviderFailure("login", "unsupported")
                }
                val body = FormBody.Builder().add("id", username).add("password", password)
                    .add("returl", config.monthPath).build()
                val reply = execute(Request.Builder().url(baseUrl.resolve(config.loginPath)!!)
                    .header("Origin", baseUrl.toString().trimEnd('/'))
                    .header("Referer", baseUrl.resolve(config.loginPath).toString()).post(body).build(), "login")
                rejectAuthentication(reply.body, "login")
                // Do not execute scripts or repeat the credential POST. Inspect a redirect but
                // inspect session-like structure at the known read-only monthly page.
                if (reply.code in REDIRECTS) safeRedirect(reply.location, "login")
            }
            return atStage("bills") { inspectMonthlyPage(get(config.monthPath, "bills"), config) }
        } finally {
            synchronized(cookies) { cookies.clear() }
        }
    }

    fun cancel() = synchronized(callLock) {
        closed = true
        activeCall?.cancel()
    }

    override fun close() {
        cancel()
        synchronized(cookies) { cookies.clear() }
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    private fun sameOrigin(url: HttpUrl) = url.scheme == baseUrl.scheme && url.host == baseUrl.host && url.port == baseUrl.port

    private fun safeRedirect(location: String?, stage: String): HttpUrl {
        val url = location?.let { baseUrl.resolve(it) } ?: throw ProviderFailure(stage, "unsupported")
        if (!sameOrigin(url) || url.encodedPath !in setOf("/", config.loginPath, config.monthPath) ||
            url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null) {
            throw ProviderFailure(stage, "unsupported")
        }
        return url
    }

    private fun get(path: String, stage: String): String {
        var url = baseUrl.resolve(path)!!
        repeat(4) {
            val reply = execute(Request.Builder().url(url).get().build(), stage)
            if (reply.code !in REDIRECTS) return reply.body
            url = safeRedirect(reply.location, stage)
        }
        throw ProviderFailure(stage, "unsupported")
    }

    private data class Reply(val code: Int, val body: String, val location: String?)

    private fun execute(request: Request, stage: String): Reply {
        val call = synchronized(callLock) {
            if (closed || !sameOrigin(request.url)) throw ProviderFailure(stage, "validation")
            client.newCall(request).also { activeCall = it }
        }
        // If cancellation happens after registration, it cancels this exact Call before
        // execute can send it. New calls cannot be registered after cancellation.
        try {
            if (closed || call.isCanceled()) throw ProviderFailure(stage, "validation")
            return call.execute().use { response ->
                if (closed || call.isCanceled()) throw ProviderFailure(stage, "validation")
                if (response.code !in 200..299 && response.code !in REDIRECTS) {
                    throw ProviderFailure(stage, if (response.code in setOf(401, 403)) "authentication" else "http", response.code)
                }
                val body = response.body ?: throw ProviderFailure(stage, "unsupported")
                if (body.contentLength() > MAX_HTML_BYTES) throw ProviderFailure(stage, "unsupported")
                val bytes = body.byteStream().readBytesLimited(MAX_HTML_BYTES)
                val charset = body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
                Reply(response.code, String(bytes, charset), response.header("Location"))
            }
        } finally {
            synchronized(callLock) { if (activeCall === call) activeCall = null }
        }
    }

    private inline fun <T> atStage(stage: String, action: () -> T): T = try { action() }
    catch (failure: ProviderFailure) { throw failure }
    catch (failure: Exception) {
        throw ProviderFailure(stage, when (failure) {
            is java.net.SocketTimeoutException -> "timeout"
            is javax.net.ssl.SSLException -> "tls"
            is java.io.IOException -> "network"
            else -> "parse"
        })
    }

    companion object {
        private const val MAX_HTML_BYTES = 1_000_000
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)
        internal data class Configuration(val origin: String, val loginPath: String, val monthPath: String, val billHeading: String)
        private fun configuration(id: String): Configuration = when (id) {
            "daesung" -> Configuration("https://cyber.daesungenergy.com", "/users/login", "/charge/month", "월별요금")
            "daesungclean" -> Configuration("https://www.daesungcleanenergy.co.kr", "/users/login", "/charge/month", "청구요금조회")
            else -> throw IllegalArgumentException("대성 계열 연결 테스트 대상이 아니에요.")
        }
        private fun portal(id: String): HttpUrl = configuration(id).origin.toHttpUrl()

        private fun rejectAuthentication(html: String, stage: String) {
            val document = Jsoup.parse(html)
            if (document.select("input[type=password]").isNotEmpty() ||
                html.contains("로그인 되어 있지 않습니다") ||
                html.contains("아이디 또는 비밀번호가 일치하지 않습니다")) {
                throw ProviderFailure(stage, "authentication")
            }
        }

        private fun inspectMonthlyPage(html: String, config: Configuration): DaesungProbeResult {
            rejectAuthentication(html, "bills")
            val document = Jsoup.parse(html, config.origin)
            // A logout-looking link is only session-like page structure. Its endpoint has
            // not been authenticated or verified, so this must not claim confirmed login.
            val logout = document.select("a[href]").any { anchor ->
                val destination = config.origin.toHttpUrl().resolve(anchor.attr("href"))
                anchor.text().trim() == "로그아웃" && destination != null &&
                    destination.scheme == "https" && destination.host == config.origin.toHttpUrl().host &&
                    destination.port == 443 && destination.encodedPath != "/"
            }
            val heading = document.select("h1,h2,h3,h4,title").any { it.text().trim().contains(config.billHeading) }
            if (!logout || !heading) throw ProviderFailure("bills", "unsupported")
            // Labels are observations, not a billing schema. No row values are returned.
            val labels = setOf("청구월", "납기월", "사용기간", "검침기간", "사용량", "청구금액", "전월지침", "당월지침")
            val observed = document.select("table th").map { it.text().replace(Regex("\\s+"), "") }
                .filter { it in labels }.distinct().sorted()
            return DaesungProbeResult(true, true, document.select("table").size, observed)
        }
    }
}
