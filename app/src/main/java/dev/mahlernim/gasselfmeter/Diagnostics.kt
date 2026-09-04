package dev.mahlernim.gasselfmeter

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.Instant
import java.util.UUID
import javax.net.ssl.SSLException

/** Only allowlisted metadata is persisted. Never log this exception or its cause directly. */
class ProviderFailure(
    val stage: String,
    val category: String,
    val httpCode: Int? = null,
    cause: Throwable? = null,
) : Exception("공급사 연결 처리 실패", cause)

internal data class DiagnosticEntry(
    val time: String,
    val id: String,
    val version: String,
    val provider: String,
    val stage: String,
    val category: String,
    val httpCode: Int?,
)

internal object DiagnosticCodec {
    const val MAX_ENTRIES = 100
    const val MAX_BYTES = 32_768
    private val stages = setOf("login", "user", "contracts", "bills", "meter", "submit", "sync", "connect", "history", "refresh", "backup", "settings", "background", "unknown")
    private val labels = mapOf(
        "authentication" to "로그인 또는 인증 만료",
        "http" to "서버 HTTP 오류",
        "network" to "네트워크 연결 오류",
        "timeout" to "응답 시간 초과",
        "tls" to "보안 연결 오류",
        "parse" to "응답 형식 해석 실패",
        "provider_mismatch" to "공급사 정보 불일치",
        "unsupported" to "지원하지 않는 응답 또는 기능",
        "validation" to "입력 또는 데이터 검증 실패",
        "storage" to "기기 저장 오류",
        "unknown" to "처리 중 오류",
    )
    fun stage(value: String): String = value.removePrefix("samchully_").takeIf { it in stages } ?: "unknown"
    fun category(value: String): String = value.takeIf { it in labels } ?: "unknown"
    fun label(value: String): String = labels.getValue(category(value))
    private fun provider(value: String): String = value.takeIf { candidate -> Providers.all.any { it.id == candidate } } ?: "unknown"
    fun create(provider: String, stage: String, error: Exception, version: String, time: Instant = Instant.now(), id: String = UUID.randomUUID().toString().take(8)): DiagnosticEntry {
        val failure = error as? ProviderFailure
        val category = failure?.category ?: when (error) {
            is GasappAuthExpired -> "authentication"
            is SocketTimeoutException -> "timeout"
            is SSLException -> "tls"
            is IOException -> "network"
            is org.json.JSONException -> "parse"
            is IllegalArgumentException, is IllegalStateException -> "validation"
            else -> "unknown"
        }
        return DiagnosticEntry(time.toString(), id.takeIf { it.matches(Regex("[a-f0-9]{8}")) } ?: "00000000",
            version.takeIf { it.matches(Regex("[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}")) } ?: "unknown",
            this.provider(provider), this.stage(failure?.stage ?: stage), this.category(category), failure?.httpCode?.takeIf { it in 100..599 })
    }
    fun encode(entry: DiagnosticEntry): String = listOf(entry.time, entry.id, entry.version, entry.provider, entry.stage, entry.category, entry.httpCode?.toString() ?: "-").joinToString("|")
    fun decode(line: String): DiagnosticEntry? {
        val parts = line.split('|')
        if (parts.size != 7) return null
        val time = runCatching { Instant.parse(parts[0]) }.getOrNull() ?: return null
        val http = if (parts[6] == "-") null else parts[6].toIntOrNull()?.takeIf { it in 100..599 } ?: return null
        val entry = create(parts[3], parts[4], ProviderFailure(parts[4], parts[5], http), parts[2], time, parts[1])
        // Reject corrupt/foreign fields instead of echoing arbitrary disk contents into a report.
        return entry.takeIf { encode(it) == line }
    }
    fun entries(text: String): List<DiagnosticEntry> = text.lineSequence().mapNotNull(::decode).toList().takeLast(MAX_ENTRIES)
    fun append(text: String, entry: DiagnosticEntry): String = (entries(text) + entry).takeLast(MAX_ENTRIES).joinToString("\n", transform = ::encode)
}

/** Device-local, bounded, excluded from Android backup. Report sharing is always user initiated. */
object Diagnostics {
    private fun file(context: Context) = AtomicFile(File(context.noBackupFilesDir, "alpha-diagnostics.log"))
    private fun read(context: Context): String {
        val storage = file(context)
        if (!storage.baseFile.exists()) return ""
        return storage.openRead().use { input ->
            val bytes = ByteArray(DiagnosticCodec.MAX_BYTES + 1)
            var count = 0
            while (count < bytes.size) {
                val read = input.read(bytes, count, bytes.size - count)
                if (read < 0) break
                count += read
            }
            if (count > DiagnosticCodec.MAX_BYTES) "" else String(bytes, 0, count, Charsets.UTF_8)
        }
    }
    @Synchronized
    fun record(context: Context, provider: String, stage: String, error: Exception): String {
        val entry = DiagnosticCodec.create(provider, stage, error, BuildConfig.VERSION_NAME)
        val saved = runCatching {
            val contents = DiagnosticCodec.append(read(context), entry).toByteArray(Charsets.UTF_8)
            val storage = file(context)
            val stream = storage.startWrite()
            try {
                stream.write(contents)
                storage.finishWrite(stream)
            } catch (failure: Exception) {
                storage.failWrite(stream)
                throw failure
            }
        }.isSuccess
        val http = entry.httpCode?.let { " · HTTP $it" }.orEmpty()
        return "${entry.stage} · ${DiagnosticCodec.label(entry.category)}$http · 진단 ${entry.id}" + if (saved) "" else " · 기록 저장 실패"
    }
    @Synchronized
    fun report(context: Context): String = runCatching {
        val entries = DiagnosticCodec.entries(read(context))
        if (entries.isEmpty()) "저장된 진단 기록이 없어요."
        else "기기 내 진단 기록 · 자동 전송 없음 · 최근 ${entries.size}건\n시간(UTC) | 진단 ID | 앱 버전 | 공급사 | 단계 | 오류 분류 | HTTP\n" + entries.joinToString("\n", transform = DiagnosticCodec::encode)
    }.getOrDefault("진단 기록을 읽지 못했어요.")

    @Synchronized
    fun clear(context: Context) {
        val storage = file(context)
        storage.delete()
        check(!storage.baseFile.exists()) { "진단 기록을 지우지 못했어요." }
    }
}
