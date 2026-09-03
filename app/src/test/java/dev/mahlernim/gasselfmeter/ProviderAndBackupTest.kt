package dev.mahlernim.gasselfmeter

import org.junit.Assert.*
import org.junit.Test

class ProviderAndBackupTest {
    private fun fixture(month: String = "202501", current: String = "120", corrected: String = "20"): String = """
        <html><input id="budat" value="$month">
        <table><tr><th>합계</th><td>18,810</td></tr></table><table></table><table></table><table></table>
        <table><tr><td>사용료</td><td>synthetic-meter</td><td>12.20~01.19</td><td>100</td><td>$current</td><td>1</td><td>$corrected</td><td>40</td><td>800</td><td>20</td><td>16000</td></tr>
        <tr><td>기본료</td><td></td><td></td><td></td><td></td><td></td><td></td><td></td><td></td><td></td><td>1100</td></tr></table></html>
    """.trimIndent()
    @Test fun billParserHandlesYearBoundaryMeterIdentityAndSeparatesUsageFromGrossAmount() {
        val bill = BusanClient.parseBill(fixture(), "202501").single()
        assertEquals("2024-12-20", bill.start)
        assertEquals("2025-01-19", bill.end)
        assertEquals(20.0, bill.usage, .00001)
        assertEquals(880.0, bill.unitCost!!, .00001)
        assertEquals(BusanClient.opaque("synthetic-meter"), bill.meter)
        assertEquals(18810.0, bill.amount!!, .00001)
    }
    @Test fun changedPortalAndWrongBillingMonthFailClosed() {
        assertEquals("2026-09-17", BusanClient.parsePortalDate("20260917"))
        assertEquals("2026-09-17", BusanClient.parsePortalDate("2026.09.17"))
        assertThrows(IllegalStateException::class.java) { BusanClient.parseBill(fixture(), "202502") }
        assertThrows(IllegalStateException::class.java) { BusanClient.parseBill(fixture(current = "121"), "202501") }
        assertThrows(IllegalStateException::class.java) { BusanClient.parseContracts("<input type='password'>") }
        val contracts = BusanClient.parseContracts("<script>var data={BPNO:'1234'}</script><input id='list_cano_1' value='5678'><input id='list_cano_2' value='9012'>")
        assertEquals(2, contracts.size)
    }
    @Test fun portableBackupNeverContainsCredentialsAndImportIgnoresInjectedCredentials() {
        val data = AppData(periods = BusanClient.parseBill(fixture(), "202501"), credentials = Credentials("synthetic-user", "synthetic-secret"), ready = true)
        val exported = DataCodec.encode(data)
        assertFalse(exported.contains("synthetic-secret"))
        assertFalse(exported.contains("credentials"))
        val restored = DataCodec.decode(DataCodec.encode(data, true))
        assertNull(restored.credentials)
        assertEquals(data.periods, restored.periods)
        assertEquals(data.credentials, DataCodec.decode(DataCodec.encode(data, true), true).credentials)
    }
    @Test fun unsupportedSchemaAndFutureObservationsCannotBeImported() {
        val data = AppData(observations = listOf(Observation(System.currentTimeMillis() + 86_400_000, 100.0, "manual")))
        assertThrows(IllegalArgumentException::class.java) { DataCodec.decode(DataCodec.encode(data)) }
        assertThrows(IllegalArgumentException::class.java) { DataCodec.decode(DataCodec.encode(AppData()).replace("\"schema\": 1", "\"schema\": 9")) }
    }
}
