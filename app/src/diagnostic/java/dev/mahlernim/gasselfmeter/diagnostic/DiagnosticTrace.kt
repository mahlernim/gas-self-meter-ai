package dev.mahlernim.gasselfmeter.diagnostic

import android.content.Context
import android.util.AtomicFile
import dev.mahlernim.gasselfmeter.BusanTraceEvent
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant

/** A bounded, device-only JSONL trace with only the client's approved safe event fields. */
internal object DiagnosticTrace {
    private const val fileName = "busan-diagnostic-trace.jsonl"
    private const val maxBytes = 32_768
    private const val maxEntries = 100
    private val stages = setOf("login", "contracts", "meter", "meter_schema", "meter_state", "submit", "submit_prepared", "submit_dispatch", "submit_response", "submit_failure", "history", "portal", "reconcile", "form_validation", "unknown")
    private val outcomes = setOf("started", "ok", "authentication", "http", "network", "timeout", "tls", "parse", "validation", "uncertain", "unknown")

    private fun file(context: Context) = AtomicFile(File(context.noBackupFilesDir, fileName))
    private fun safeStage(value: String) = value.takeIf { it in stages } ?: "unknown"
    private fun safeOutcome(value: String) = value.takeIf { it in outcomes } ?: "unknown"
    private fun safeCode(value: Int?) = value?.takeIf { it in 100..599 }
    private fun read(atomic: AtomicFile): List<JSONObject> = runCatching {
        if (!atomic.baseFile.exists()) emptyList() else atomic.openRead().bufferedReader().use { reader ->
            reader.lineSequence().mapNotNull { line -> runCatching { JSONObject(line) }.getOrNull() }.toList()
        }
    }.getOrDefault(emptyList())

    private fun eventJson(event: BusanTraceEvent, outcome: String): JSONObject = JSONObject().apply {
        put("time", Instant.now().toString())
        put("stage", safeStage(event.stage))
        put("outcome", safeOutcome(outcome))
        put("httpCode", safeCode(event.httpCode))
        put("elapsedMillis", event.elapsedMillis?.takeIf { it >= 0 })
        put("requestWireValue", event.requestWireValue?.takeIf { it.matches(Regex("[0-9]{1,8}")) })
        put("serverResultCode", event.serverResultCode.takeIf { it in setOf("Y", "N", "unknown") } ?: "unknown")
        put("responseKeys", JSONArray(event.responseKeys.filter { it.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,63}")) }.sorted()))
        put("submittedValue", event.submittedValue?.takeIf { it.isFinite() })
        put("previousValue", event.previousValue?.takeIf { it.isFinite() })
        put("eligible", event.eligible)
        put("submitted", event.submitted)
        put("submittedValuePresent", event.submittedValuePresent)
        put("submittedValueState", event.submittedValueState.takeIf { it in setOf("absent", "empty", "non_numeric", "zero", "positive", "unknown") } ?: "unknown")
        put("rowResultCategory", event.rowResultCategory.takeIf { it in setOf("Y", "N", "S", "E", "numeric", "empty", "other", "unknown") } ?: "unknown")
        put("rowResultValue", event.rowResultValue?.takeIf { it.isFinite() && it in 0.0..99_999_999.0 })
        put("previousValueSource", event.previousValueSource?.takeIf { it in setOf("LAST_READINGRESULT", "HT_READINGRESULT", "unknown") })
        put("selfReadYn", event.selfReadYn.takeIf { it in setOf("Y", "N", "unknown") } ?: "unknown")
        put("eligibilityYn", event.eligibilityYn.takeIf { it in setOf("Y", "N", "unknown") } ?: "unknown")
        put("contractMismatch", event.contractMismatch)
        put("meterMismatch", event.meterMismatch)
        put("cycleMismatch", event.cycleMismatch)
        put("datesMismatch", event.datesMismatch)
        put("plannedMismatch", event.plannedMismatch)
        put("installationMismatch", event.installationMismatch)
        put("valueMismatch", event.valueMismatch)
        put("formUsesIsNaN", event.formUsesIsNaN)
        put("formUsesParseInt", event.formUsesParseInt)
        put("formValidatesReading", event.formValidatesReading)
        put("formAcceptsY", event.formAcceptsY)
        put("formRejectsN", event.formRejectsN)
        put("formAlertMessage", event.formAlertMessage?.take(200))
        put("formAlertHash", event.formAlertHash?.takeIf { it.matches(Regex("[a-f0-9]{8,64}")) })
        put("formMapsGeraetAddr", event.formMapsGeraetAddr)
        put("formMapsLegacyAddr", event.formMapsLegacyAddr)
        put("formReadRoutes", JSONArray(event.formReadRoutes.filter { it.matches(Regex("/(?:[A-Za-z0-9_-]+/){0,7}[A-Za-z0-9_-]+\\.do")) }.sorted().take(16)))
        put("formReadParameterKeys", JSONArray(event.formReadParameterKeys.filter { it.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,47}")) }.sorted().take(24)))
        put("formReadDomIds", JSONArray(event.formReadDomIds.filter { it.matches(Regex("[A-Za-z][A-Za-z0-9_-]{0,63}")) }.sorted().take(16)))
        put("formReadFunctionNames", JSONArray(event.formReadFunctionNames.filter { it.matches(Regex("[A-Za-z_$][A-Za-z0-9_$]{0,63}")) }.sorted().take(16)))
        put("formScriptPaths", JSONArray(event.formScriptPaths.filter { it.matches(Regex("/(?:[A-Za-z0-9._-]+/){0,6}[A-Za-z0-9._-]+\\.js")) }.sorted().take(16)))
    }

    @Synchronized
    fun record(context: Context, event: BusanTraceEvent, outcome: String): Boolean {
        val atomic = file(context)
        val lines = (read(atomic) + eventJson(event, outcome)).takeLast(maxEntries).map(JSONObject::toString)
        var output = lines.joinToString("\n")
        while (output.toByteArray(Charsets.UTF_8).size > maxBytes && output.contains('\n')) output = output.substringAfter('\n')
        val stream = atomic.startWrite()
        try {
            stream.write(output.toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
            return true
        } catch (error: Exception) {
            atomic.failWrite(stream)
            return false
        }
    }

    @Synchronized
    fun recordFailure(context: Context, stage: String, outcome: String, httpCode: Int? = null): Boolean =
        record(context, BusanTraceEvent(stage = stage, httpCode = httpCode), outcome)

    fun recordSubmitStart(context: Context, value: Int): Boolean =
        record(context, BusanTraceEvent(stage = "submit_prepared", requestWireValue = value.toString()), "started")

    @Synchronized
    fun report(context: Context): String {
        val entries = read(file(context))
        return if (entries.isEmpty()) "저장된 진단 기록이 없습니다." else
            "기기 내 진단 기록 ${entries.size}건. 자동 전송하지 않습니다.\n" + entries.joinToString("\n", transform = JSONObject::toString)
    }
}
