package dev.mahlernim.gasselfmeter

import org.json.JSONArray
import org.json.JSONObject
import java.time.YearMonth

/** A bearer session is private device state and must never appear in a portable backup. */
data class EnergyTalkConnection(val tenant: String, val session: String) {
    init {
        require(tenant in EnergyTalkBoundary.tenants) { "EnergyTalk 공급사를 확인해 주세요." }
        require(EnergyTalkBoundary.token("Bearer $session") == session) { "EnergyTalk 로그인 세션을 확인해 주세요." }
    }

    override fun toString() = "EnergyTalkConnection(redacted)"
}

/** Monthly billing evidence. A missing unit or amount remains missing and is never inferred. */
data class EnergyTalkBill(val month: String, val usage: String, val amount: String, val unit: String?) {
    init {
        require(month.matches(Regex("20\\d{2}(0[1-9]|1[0-2])"))) { "EnergyTalk 청구 월을 확인해 주세요." }
        require(usage.length in 1..200 && amount.length in 1..200) { "EnergyTalk 청구 정보를 확인해 주세요." }
        require(unit == null || unit.length in 1..50) { "EnergyTalk 사용량 단위를 확인해 주세요." }
    }
}

object EnergyTalkCodec {
    fun encode(json: JSONObject, data: AppData, includeCredentials: Boolean) {
        json.put("energyTalkBills", JSONArray().apply {
            data.energyTalkBills.forEach { bill -> put(JSONObject()
                .put("month", bill.month).put("usage", bill.usage)
                .put("amount", bill.amount).put("unit", bill.unit)) }
        })
        if (includeCredentials) data.energyTalkConnection?.let { connection ->
            json.put("energyTalkConnection", JSONObject()
                .put("tenant", connection.tenant).put("session", connection.session))
        }
    }

    fun connection(json: JSONObject, allowCredentials: Boolean): EnergyTalkConnection? =
        if (!allowCredentials) null else json.optJSONObject("energyTalkConnection")?.let { connection ->
            EnergyTalkConnection(connection.getString("tenant"), connection.getString("session"))
        }

    fun bills(json: JSONObject): List<EnergyTalkBill> {
        val rows = json.optJSONArray("energyTalkBills") ?: return emptyList()
        require(rows.length() <= 120) { "EnergyTalk 청구 이력이 너무 많아요." }
        return (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            val month = row.getString("month")
            YearMonth.parse(month.substring(0, 4) + "-" + month.substring(4, 6))
            EnergyTalkBill(month, row.getString("usage"), row.getString("amount"),
                if (row.isNull("unit")) null else row.getString("unit"))
        }.also { bills ->
            require(bills.map(EnergyTalkBill::month).distinct().size == bills.size) { "EnergyTalk 청구 월이 중복됐어요." }
        }.sortedBy { it.month }
    }
}
