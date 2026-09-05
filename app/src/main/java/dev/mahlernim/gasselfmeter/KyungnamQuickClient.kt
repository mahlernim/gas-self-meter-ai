package dev.mahlernim.gasselfmeter

import java.io.Closeable
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.YearMonth
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.json.JSONTokener

/** Display only. Corrected volume is not a cumulative meter reading or raw meter usage. */
data class KyungnamQuickBill(
    val billMonth: YearMonth,
    val billedAmount: Double?,
    val correctedUsage: Double?,
    val energyUsageMj: Double?,
)

/** User-initiated experimental lookup. No login, submission, background access or estimator import. */
class KyungnamQuickClient private constructor(source: OkHttpClient) : Closeable {
    constructor() : this(readOnlyHttpClient())

    private val callLock = Any()
    private var activeCall: Call? = null
    @Volatile private var closed = false
    private val client = source.newBuilder()
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS).cookieJar(CookieJar.NO_COOKIES).cache(null)
        .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
        .addNetworkInterceptor { chain ->
            if (closed || chain.call().isCanceled()) throw IOException("Lookup cancelled")
            chain.proceed(chain.request())
        }.build()

    /** Null means no available bill, not proof that the customer's number is invalid. */
    @Synchronized fun lookup(customerNumber: String): KyungnamQuickBill? {
        val number = customerNumber.trim()
        if (!number.matches(Regex("[0-9]{1,9}"))) throw ProviderFailure("bills", "validation")
        return try {
            val request = Request.Builder().url(ENDPOINT)
                .header("Accept", "application/json")
                .header("Origin", "https://www.knenergy.co.kr")
                .header("Referer", "https://www.knenergy.co.kr/kcf010.do?programId=KCF010")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Cache-Control", "no-store")
                .post(FormBody.Builder().add("MSTCD", number).build()).build()
            val call = synchronized(callLock) {
                if (closed) throw ProviderFailure("bills", "validation")
                client.newCall(request).also { activeCall = it }
            }
            try {
                if (closed || call.isCanceled()) throw ProviderFailure("bills", "validation")
                call.execute().use { response ->
                    if (closed || call.isCanceled()) throw ProviderFailure("bills", "validation")
                    if (response.code != 200) throw ProviderFailure("bills",
                        if (response.code in setOf(401, 403)) "authentication" else "http", response.code)
                    val body = response.body ?: throw ProviderFailure("bills", "parse")
                    val text = body.byteStream().readBytesLimited(MAX_BYTES).toString(Charsets.UTF_8)
                    parse(text)
                }
            } finally {
                synchronized(callLock) { if (activeCall === call) activeCall = null }
            }
        } catch (failure: ProviderFailure) {
            throw failure
        } catch (failure: Exception) {
            val category = when (failure) {
                is SocketTimeoutException -> "timeout"
                is SSLException -> "tls"
                is IOException -> "network"
                else -> "parse"
            }
            // Do not retain transport/parser causes which can contain response data or identifiers.
            throw ProviderFailure("bills", category)
        }
    }

    override fun close() {
        synchronized(callLock) {
            closed = true
            activeCall?.cancel()
        }
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    companion object {
        private const val ENDPOINT = "https://www.knenergy.co.kr/kcf010_search.do"
        private const val MAX_BYTES = 65_536
        internal fun forTests(client: OkHttpClient) = KyungnamQuickClient(client)

        /**
         * Field meanings come from the official KCF010 form and common.js observed 2026-09-04.
         * This is a frontend-derived contract, not a captured successful customer response.
         * YEARMNTH is billing month, TOTAMT is billed won, M3AMT is corrected cubic metres,
         * and MEJAMT is consumed MJ. No meter serial or usage-period bounds are established.
         */
        internal fun parse(text: String): KyungnamQuickBill? = try {
            if (text.isBlank()) null else {
                val tokener = JSONTokener(text)
                val value = tokener.nextValue()
                require(tokener.nextClean() == '\u0000')
                if (value === JSONObject.NULL) null else {
                    val json = value as? JSONObject ?: error("shape")
                    if (json.length() == 0) null else {
                        require(json.has("YEARMNTH"))
                        val rawMonth = scalar(json, "YEARMNTH")
                        if (rawMonth.isNullOrBlank()) null else {
                            require(rawMonth.matches(Regex("[0-9]{4}-?[0-9]{2}")))
                            val compact = rawMonth.replace("-", "")
                            val year = compact.take(4).toInt()
                            require(year >= 1)
                            val month = YearMonth.of(year, compact.takeLast(2).toInt())
                            KyungnamQuickBill(month, numeric(json, "TOTAMT"),
                                numeric(json, "M3AMT"), numeric(json, "MEJAMT"))
                        }
                    }
                }
            }
        } catch (_: Exception) {
            throw ProviderFailure("bills", "parse")
        }

        private fun scalar(json: JSONObject, key: String): String? {
            val value = json.opt(key)
            if (value == null || value === JSONObject.NULL) return null
            require(value is String || value is Number)
            return value.toString().trim()
        }

        private fun numeric(json: JSONObject, key: String): Double? {
            val raw = json.opt(key)
            if (raw is Number) {
                val value = raw.toDouble()
                require(value.isFinite() && value >= 0.0)
                return value
            }
            val text = scalar(json, key)?.takeIf { it.isNotEmpty() } ?: return null
            // Reject units, malformed grouping, signs, exponent strings, booleans and containers.
            require(text.matches(Regex("(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+)(?:\\.[0-9]+)?")))
            val value = text.replace(",", "").toDouble()
            require(value.isFinite() && value >= 0.0)
            return value
        }
    }
}
