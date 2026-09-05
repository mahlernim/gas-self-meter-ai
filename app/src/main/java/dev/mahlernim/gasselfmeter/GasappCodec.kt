package dev.mahlernim.gasselfmeter

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.YearMonth

class GasappConnection(val session: GasappSession, val account: GasappAccount) {
    override fun toString() = "GasappConnection(redacted)"
}

/** Sessions and cached account details are included only in the encrypted device store. */
object GasappCodec {
    /** Remove only the exact bill-derived shape written by older versions. The bill stays visible. */
    internal fun withoutLegacyBillPeriods(data: AppData): AppData {
        if (!Providers.get(data.profile.providerId).gasapp || data.gasappBills.isEmpty()) return data
        val retained = data.periods.filterNot { period ->
            period.previous == null && period.current == null && period.billMonth.isNotBlank() &&
                period.unitCost == null && period.baseCost == null && data.gasappBills.any { bill ->
                    bill.start == period.start && bill.end == period.end && bill.usage == period.usage &&
                        bill.amount == period.amount && bill.month.replace("-", "") == period.billMonth
                }
        }
        return if (retained.size == data.periods.size) data else data.copy(periods = retained)
    }

    private fun account(a: GasappAccount) = JSONObject().put("company", a.company).put("customer", a.customer)
        .put("contract", a.contract).put("label", a.label).put("ami", a.ami)
    private fun account(j: JSONObject) = GasappAccount(j.getString("company"), j.getString("customer"),
        j.getString("contract"), j.getString("label"), j.getString("ami")).also {
        require(it.company in GasappApi.companyProviders && (it.customer.isNotBlank() || it.contract.isNotBlank()))
    }
    private fun JSONObject.number(key: String): Double? = if (isNull(key)) null else getDouble(key).also {
        require(it.isFinite() && it in 0.0..99_999_999.0)
    }
    private fun JSONObject.date(key: String): String? = if (isNull(key)) null else LocalDate.parse(getString(key)).toString()
    fun encode(json: JSONObject, data: AppData, secrets: Boolean) {
        json.put("gasappBills", JSONArray().apply { data.gasappBills.forEach { b -> put(JSONObject()
            .put("month", b.month).put("usage", b.usage).put("amount", b.amount).put("start", b.start).put("end", b.end)) } })
        if (!secrets) return
        json.put("gasappMeterChangeObservedAt", data.gasappMeterChangeObservedAt)
        data.gasappConnection?.let { c -> json.put("gasappConnection", JSONObject().put("account", account(c.account))
            .put("token", c.session.token).put("member", c.session.member).put("deviceId", c.session.deviceId)) }
        data.cachedGasappTarget?.let { t -> json.put("cachedGasappTarget", JSONObject().put("account", account(t.account))
            .put("registered", t.registered).put("eligible", t.eligible).put("start", t.start).put("end", t.end)
            .put("meter", t.meter).put("previous", t.previous).put("digits", t.digits)
            .put("needsChannelChange", t.needsChannelChange).put("meterChanged", t.meterChanged)
            .put("submitted", t.submitted).put("submittedValue", t.submittedValue).put("submissionIssue", t.submissionIssue)) }
    }
    fun connection(json: JSONObject, secrets: Boolean): GasappConnection? = if (!secrets) null else
        json.optJSONObject("gasappConnection")?.let { GasappConnection(
            GasappSession(it.getString("token"), it.getString("member"), it.getString("deviceId")), account(it.getJSONObject("account"))) }
    fun target(json: JSONObject, secrets: Boolean): GasappTarget? = if (!secrets) null else
        json.optJSONObject("cachedGasappTarget")?.let { j -> GasappTarget(account(j.getJSONObject("account")),
            j.getBoolean("registered"), j.getBoolean("eligible"), j.date("start"), j.date("end"),
            if (j.isNull("meter")) null else j.getString("meter"), j.number("previous"),
            if (j.isNull("digits")) null else j.getInt("digits").also { require(it in 1..20) },
            j.getBoolean("needsChannelChange"), j.getBoolean("meterChanged"), j.getBoolean("submitted"), j.number("submittedValue"),
            if (j.isNull("submissionIssue")) null else j.getString("submissionIssue").take(300)) }
    fun bills(json: JSONObject): List<GasappBill> {
        val rows = json.optJSONArray("gasappBills") ?: return emptyList()
        require(rows.length() <= 600)
        return (0 until rows.length()).map { rows.getJSONObject(it).let { j ->
            GasappBill(YearMonth.parse(j.getString("month")).toString(), j.number("usage"), j.number("amount"), j.date("start"), j.date("end"))
        } }
    }
}
