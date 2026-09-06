package dev.mahlernim.gasselfmeter

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.YearMonth

internal object DirectBillCodec {
    fun encode(root: JSONObject, bills: List<DirectBill>) {
        root.put("directBills", JSONArray().apply { bills.forEach { bill -> put(JSONObject().apply {
            put("month", bill.month); put("usage", bill.usage); put("amount", bill.amount)
            put("start", bill.start); put("end", bill.end); put("previous", bill.previous)
            put("current", bill.current); put("meterId", bill.meterId)
        }) } })
    }
    fun decode(root: JSONObject): List<DirectBill> {
        val rows = root.optJSONArray("directBills") ?: return emptyList()
        require(rows.length() <= 600)
        return (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            fun numeric(key: String): Double? = if (row.isNull(key)) null else row.getDouble(key).also {
                require(it.isFinite() && it in 0.0..99_999_999.0)
            }
            fun date(key: String): String? = if (row.isNull(key)) null else LocalDate.parse(row.getString(key)).toString()
            val month = YearMonth.parse(row.getString("month")).also { require(it.year in 2000..2100) }.toString()
            DirectBill(month, numeric("usage"), numeric("amount"), date("start"), date("end"),
                numeric("previous"), numeric("current"), if (row.isNull("meterId")) null else row.getString("meterId").also { require(it.length in 1..100) })
                .also { bill ->
                    require(bill.start == null || bill.end == null || bill.start <= bill.end)
                    require(bill.previous == null || bill.current == null || bill.current >= bill.previous)
                }
        }
    }
}
