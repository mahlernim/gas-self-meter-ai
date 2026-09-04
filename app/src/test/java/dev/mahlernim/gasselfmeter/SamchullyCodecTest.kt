package dev.mahlernim.gasselfmeter

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.YearMonth

class SamchullyCodecTest {
    private val meter = "0123456789abcdef0123456789abcdef"
    private val bill = SamchullyBill("202608", "2026-08-01", "2026-08-31", 100.0, 110.0, 9.8, 12000.0, meter)
    private fun data(bills: List<SamchullyBill>) = AppData(
        profile = Profile(providerId = "samchully", meter = meter), ready = true, samchullyBills = bills,
    )

    @Test fun deviceAndPortableCodecsPreserveCompleteAndPartialBills() {
        val original = data(listOf(
            bill.copy(billMonth = "202607", start = null, end = null, previousReading = null,
                currentReading = null, reportedUsage = null, amount = null, meterId = null),
            bill,
        ))
        for (deviceStorage in listOf(false, true)) {
            val restored = DataCodec.decode(DataCodec.encode(original, deviceStorage), deviceStorage)
            assertEquals(original.samchullyBills, restored.samchullyBills)
            assertEquals(original.profile, restored.profile)
            assertTrue(restored.ready)
            assertNull(restored.credentials)
        }
    }

    @Test fun olderBackupsWithoutSamchullyBillsRemainReadable() {
        val root = JSONObject(DataCodec.encode(data(emptyList()))).apply { remove("samchullyBills") }
        assertTrue(DataCodec.decode(root.toString()).samchullyBills.isEmpty())
    }

    @Test fun missingMeterDifferenceUsesPeriodUsageNeverReportedConsumption() {
        val incomplete = bill.copy(previousReading = null, currentReading = null)
        val withoutPeriods = data(listOf(incomplete))
        val latest = YearMonth.of(2026, 9)
        val absent = HistorySummary.months(withoutPeriods, latest, 1).single()
        assertNull(absent.usage)
        assertEquals(12000.0, absent.billedAmount!!, .0001)

        val withPeriods = withoutPeriods.copy(periods = listOf(
            UsagePeriod("2026-08-01", "2026-08-31", 31.0, meter),
        ))
        val fallback = HistorySummary.months(withPeriods, latest, 1).single()
        assertEquals(31.0, fallback.usage!!, .0001)
        assertEquals(9.8, withPeriods.samchullyBills.single().reportedUsage!!, .0001)
    }

    @Test fun monthlyHistoryPrefersUncorrectedMeterDifference() {
        val month = HistorySummary.months(data(listOf(bill)), YearMonth.of(2026, 9), 1).single()
        assertEquals(10.0, month.usage!!, .0001)
        assertEquals(12000.0, month.billedAmount!!, .0001)
    }
}
