package dev.mahlernim.gasselfmeter

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

internal object SamchullyCodec {
    fun encode(root: JSONObject, bills: List<SamchullyBill>) {
        root.put("samchullyBills", JSONArray().apply { bills.forEach { bill ->
            put(JSONObject().apply {
                put("month", bill.billMonth); put("start", bill.start); put("end", bill.end)
                put("previous", bill.previousReading); put("current", bill.currentReading)
                put("usage", bill.reportedUsage); put("amount", bill.amount); put("meter", bill.meterId)
            })
        } })
    }
    fun decode(root: JSONObject): List<SamchullyBill> {
        val rows = root.optJSONArray("samchullyBills") ?: return emptyList()
        require(rows.length() <= 120)
        fun number(row: JSONObject, key: String): Double? = if (row.isNull(key)) null else row.getDouble(key).also {
            require(it.isFinite() && it in 0.0..99_999_999.0)
        }
        fun date(row: JSONObject, key: String): String? = if (row.isNull(key)) null else LocalDate.parse(row.getString(key)).toString()
        return (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            val month = row.getString("month").also { require(it.matches(Regex("20[0-9]{2}(0[1-9]|1[0-2])"))) }
            val meter = if (row.isNull("meter")) null else row.getString("meter").also { require(it.matches(Regex("[0-9a-f]{16,64}"))) }
            val start = date(row, "start")
            val end = date(row, "end")
            require(start == null || end == null || start <= end)
            SamchullyBill(month, start, end, number(row, "previous"), number(row, "current"), number(row, "usage"), number(row, "amount"), meter)
        }.also { require(it.distinctBy { bill -> bill.billMonth }.size == it.size) }.sortedBy { it.billMonth }
    }
}
