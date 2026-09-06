package dev.mahlernim.gasselfmeter

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.time.LocalDate
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object DataCodec {
    private fun JSONObject.optionalDouble(key: String): Double? = if (isNull(key)) null else getDouble(key)
    private fun JSONObject.optionalString(key: String): String? = if (isNull(key)) null else getString(key)
    fun encode(data: AppData, includeCredentials: Boolean = false): String = JSONObject().apply {
        put("schema", 4)
        put("ready", data.ready)
        GasappCodec.encode(this, data, includeCredentials)
        SamchullyCodec.encode(this, data.samchullyBills)
        EnergyTalkCodec.encode(this, data, includeCredentials)
        DirectBillCodec.encode(this, data.directBills)
        put("profile", JSONObject().apply {
            put("providerId", data.profile.providerId); put("meter", data.profile.meter)
            put("contract", data.profile.contract); put("plannedDate", data.profile.plannedDate)
            put("syncTime", data.profile.syncTime); put("reminder", data.profile.reminder)
            put("reminderDay", data.profile.reminderDay); put("reminderHour", data.profile.reminderHour)
            put("reminderRepeatCount", data.profile.reminderRepeatCount); put("customerNumber", data.profile.customerNumber)
        })
        put("periods", JSONArray().apply { data.periods.forEach { p -> put(JSONObject().apply {
            put("start", p.start); put("end", p.end); put("usage", p.usage); put("meter", p.meter)
            put("previous", p.previous); put("current", p.current); put("billMonth", p.billMonth)
            put("amount", p.amount); put("unitCost", p.unitCost); put("baseCost", p.baseCost)
        }) } })
        put("observations", JSONArray().apply { data.observations.forEach { o -> put(JSONObject().apply {
            put("time", o.time); put("reading", o.reading); put("meter", o.meter); put("predicted", o.predicted)
        }) } })
        put("submissionSettings", JSONObject().apply {
            put("enabled", data.submissionSettings.enabled)
            put("automatic", data.submissionSettings.automatic)
            put("requireRecentCheck", data.submissionSettings.requireRecentCheck)
            put("recentDays", data.submissionSettings.recentDays)
            put("reminder", data.submissionSettings.reminder); put("reminderHour", data.submissionSettings.reminderHour)
            put("reminderMinute", data.submissionSettings.reminderMinute)
        })
        put("submissions", JSONArray().apply { data.submissions.forEach { record -> put(JSONObject().apply {
            put("cycle", record.cycle); put("periodStart", record.periodStart); put("periodEnd", record.periodEnd)
            put("value", record.value); put("attemptedAt", record.attemptedAt)
            put("status", record.status); put("detail", record.detail)
        }) } })
        if (includeCredentials) data.cachedSelfRead?.let { t -> put("cachedSelfRead", JSONObject().apply {
            put("cycle", t.cycle); put("start", t.start); put("end", t.end)
            put("eligible", t.eligible); put("submitted", t.submitted)
            put("submittedValue", t.submittedValue); put("previousValue", t.previousValue)
            put("bp", t.contract.bp); put("ca", t.contract.ca); put("name", t.contract.name); put("serial", t.serial)
            put("address", t.address); put("planned", t.planned); put("vLdo", t.vLdo); put("installation", t.installation)
        }) }
        if (includeCredentials && data.credentials != null) put("credentials", JSONObject().apply {
            put("username", data.credentials.username); put("password", data.credentials.password)
        })
    }.toString(2)

    fun decode(raw: String, allowCredentials: Boolean = false): AppData {
        require(raw.length <= 2_000_000) { "파일이 너무 커요. 2MB 이하의 백업 파일을 선택해 주세요." }
        val json = JSONObject(raw)
        require(json.getInt("schema") in 1..4) { "지원하지 않는 백업 형식이에요." }
        val p = json.getJSONObject("profile")
        val provider = p.getString("providerId")
        require(Providers.all.any { it.id == provider }) { "지원하지 않는 공급사 정보예요." }
        val meter = p.getString("meter").also { require(it.length in 1..100) }
        val planned = p.optionalString("plannedDate")?.also { java.time.LocalDate.parse(it) }
        val profile = Profile(provider, meter, p.optString("contract").take(100), planned,
            if (p.isNull("syncTime")) null else p.getLong("syncTime"),
            if (allowCredentials) p.optBoolean("reminder") else false,
            p.optInt("reminderDay", 7).also { require(it in 1..7) }, p.optInt("reminderHour", 19).also { require(it in 0..23) },
            p.optInt("reminderRepeatCount", 3).also { require(it in 0..6) }, p.optString("customerNumber").take(100))
        val rows = json.getJSONArray("periods")
        require(rows.length() <= 600)
        val periods = (0 until rows.length()).map { i -> rows.getJSONObject(i).let { r ->
            UsagePeriod(r.getString("start"), r.getString("end"), r.getDouble("usage"), r.optString("meter", "manual"),
                r.optionalDouble("previous"), r.optionalDouble("current"), r.optString("billMonth"),
                r.optionalDouble("amount"), r.optionalDouble("unitCost"), r.optionalDouble("baseCost"))
        } }
        Estimator.validatePeriods(periods)
        val checks = json.getJSONArray("observations")
        require(checks.length() <= 10_000)
        val observations = (0 until checks.length()).map { i -> checks.getJSONObject(i).let { r ->
            Observation(r.getLong("time"), r.getDouble("reading"), r.getString("meter"), r.optionalDouble("predicted"))
        } }.sortedBy { it.time }
        observations.forEach {
            val latest = if (allowCredentials) dayStart(java.time.LocalDate.of(2100, 1, 1)) else System.currentTimeMillis()
            require(it.time in dayStart(java.time.LocalDate.of(2000, 1, 1))..latest)
            require(it.reading.isFinite() && it.reading in 0.0..99_999_999.0)
            require(it.meter.length in 1..100)
            require(it.predicted == null || (it.predicted.isFinite() && it.predicted in 0.0..99_999_999.0))
        }
        observations.groupBy { it.meter }.values.forEach { group ->
            group.zipWithNext().forEach { (a, b) -> require(b.time > a.time && b.reading >= a.reading) { "확인 기록의 순서나 지침을 확인해 주세요." } }
        }
        val credentials = if (allowCredentials && json.has("credentials")) json.getJSONObject("credentials").let {
            Credentials(it.getString("username"), it.getString("password"))
        } else null
        val settings = if (allowCredentials && json.has("submissionSettings")) json.getJSONObject("submissionSettings").let {
            SubmissionSettings(it.optBoolean("enabled"), it.optBoolean("automatic"),
                it.optBoolean("requireRecentCheck", true), it.optInt("recentDays", 7).also { days -> require(days in 1..40) },
                it.optBoolean("reminder"), it.optInt("reminderHour", 9).also { hour -> require(hour in 0..23) },
                it.optInt("reminderMinute", 0).also { minute -> require(minute in 0..59) })
        } else SubmissionSettings()
        val submissionRows = json.optJSONArray("submissions")
        require(submissionRows == null || submissionRows.length() <= 100)
        val submissions = if (submissionRows == null) emptyList() else (0 until submissionRows.length()).map { i ->
            submissionRows.getJSONObject(i).let { row ->
                SubmissionRecord(row.getString("cycle").take(80), LocalDate.parse(row.getString("periodStart")).toString(),
                    LocalDate.parse(row.getString("periodEnd")).toString(), row.getDouble("value").also { require(it.isFinite() && it in 0.0..99_999_999.0) },
                    row.getLong("attemptedAt"), row.getString("status").also { require(it in setOf("pending", "confirmed", "uncertain", "rejected")) },
                    row.optString("detail").take(300))
            }
        }
        val cached = if (allowCredentials) json.optJSONObject("cachedSelfRead")?.let { t ->
            SelfReadTarget(t.getString("cycle"), LocalDate.parse(t.getString("start")).toString(),
                LocalDate.parse(t.getString("end")).toString(), t.getBoolean("eligible"), t.getBoolean("submitted"),
                t.optionalDouble("submittedValue"), t.optionalDouble("previousValue"),
                Contract(t.getString("bp"), t.getString("ca"), t.optString("name")), t.getString("serial"), t.optString("address"),
                t.optString("planned"), t.optString("vLdo"), t.optString("installation"))
        } else null
        val decoded = AppData(profile, periods, observations, credentials, settings, submissions, json.optBoolean("ready", true), cached,
            GasappCodec.connection(json, allowCredentials), GasappCodec.target(json, allowCredentials), GasappCodec.bills(json),
            if (allowCredentials && !json.isNull("gasappMeterChangeObservedAt")) json.getLong("gasappMeterChangeObservedAt") else null,
            SamchullyCodec.decode(json), EnergyTalkCodec.connection(json, allowCredentials), EnergyTalkCodec.bills(json), DirectBillCodec.decode(json))
        return GasappCodec.withoutLegacyBillPeriods(decoded)
    }
}

/** Android Keystore key never leaves the device. Both account secrets and usage data are encrypted. */
class SecureStore(context: Context) {
    companion object { private val monitor = Any() }
    private val file = AtomicFile(File(context.filesDir, "gas-state.enc"))
    private val alias = "gas-self-meter-ai.storage.v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun read(): AppData = synchronized(monitor) { readLocked() }
    private fun readLocked(): AppData {
        val bytes = try { file.readFully() } catch (e: java.io.FileNotFoundException) {
            if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) throw e
            return AppData()
        }
        require(bytes.size in 29..2_500_000 && bytes[0].toInt() == 1) { "저장 파일 형식을 확인할 수 없어요." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
        return DataCodec.decode(String(cipher.doFinal(bytes.copyOfRange(13, bytes.size)), Charsets.UTF_8), true)
    }
    fun write(data: AppData) = synchronized(monitor) { writeLocked(data) }
    fun update(transform: (AppData) -> AppData): AppData = synchronized(monitor) {
        transform(readLocked()).also { writeLocked(it) }
    }
    private fun writeLocked(data: AppData) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val bytes = byteArrayOf(1) + cipher.iv + cipher.doFinal(DataCodec.encode(data, true).toByteArray(Charsets.UTF_8))
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) } catch (e: Exception) { file.failWrite(stream); throw e }
    }
    fun erase() = synchronized(monitor) {
        file.delete()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
    }
}
