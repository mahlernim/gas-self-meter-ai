package dev.mahlernim.gasselfmeter

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class ProviderAndBackupTest {
    @Test fun blankCredentialsAreRejectedWithoutChangingValidPasswords() {
        for ((user, password) in listOf("" to "secret", "user" to "", "  " to "secret", "user" to " \t ")) {
            assertThrows(IllegalArgumentException::class.java) { Credentials(user, password) }
        }
        assertEquals(" secret ", Credentials("user", " secret ").password)
        val encoded = JSONObject(DataCodec.encode(AppData(), true))
            .put("credentials", JSONObject().put("username", " ").put("password", "secret"))
        assertThrows(IllegalArgumentException::class.java) { DataCodec.decode(encoded.toString(), true) }
    }
    private fun fixture(month: String = "202501", current: String = "120", corrected: String = "20"): String = """
        <html><input id="budat" value="$month">
        <table><tr><th>합계</th><td>18,810</td></tr></table><table></table><table></table><table></table>
        <table><tr><td>사용료</td><td>synthetic-meter</td><td>12.20~01.19</td><td>100</td><td>$current</td><td>1</td><td>$corrected</td><td>40</td><td>800</td><td>20</td><td>16000</td></tr>
        <tr><td>기본료</td><td></td><td></td><td></td><td></td><td></td><td></td><td></td><td></td><td></td><td>1100</td></tr></table></html>
    """.trimIndent()
    @Test fun billParserHandlesYearBoundaryMeterIdentityAndSeparatesUsageFromGrossAmount() {
        val bill = SkensClient.parseBill(fixture(), "202501").single()
        assertEquals("2024-12-20", bill.start)
        assertEquals("2025-01-19", bill.end)
        assertEquals(20.0, bill.usage, .00001)
        assertEquals(880.0, bill.unitCost!!, .00001)
        assertEquals(SkensClient.opaque("synthetic-meter"), bill.meter)
        assertEquals(18810.0, bill.amount!!, .00001)
    }
    @Test fun changedPortalAndWrongBillingMonthFailClosed() {
        assertEquals("2026-09-17", SkensClient.parsePortalDate("20260917"))
        assertEquals("2026-09-17", SkensClient.parsePortalDate("2026.09.17"))
        assertThrows(IllegalStateException::class.java) { SkensClient.parseBill(fixture(), "202502") }
        assertThrows(IllegalStateException::class.java) { SkensClient.parseBill(fixture(current = "121"), "202501") }
        assertThrows(IllegalStateException::class.java) { SkensClient.parseContracts("<input type='password'>") }
        val contracts = SkensClient.parseContracts("<script>var data={BPNO:'1234'}</script><input id='list_cano_1' value='5678'><input id='list_bpname_1' value='synthetic'><input id='list_cano_2' value='9012'>")
        assertEquals(2, contracts.size)
        assertEquals("synthetic", contracts.first().name)
    }
    @Test fun portableBackupNeverContainsCredentialsAndImportIgnoresInjectedCredentials() {
        val data = AppData(periods = SkensClient.parseBill(fixture(), "202501"), credentials = Credentials("synthetic-user", "synthetic-secret"), ready = true)
        val exported = DataCodec.encode(data)
        assertFalse(exported.contains("synthetic-secret"))
        assertFalse(exported.contains("credentials"))
        val restored = DataCodec.decode(DataCodec.encode(data, true))
        assertNull(restored.credentials)
        assertEquals(data.periods, restored.periods)
        assertEquals(data.credentials, DataCodec.decode(DataCodec.encode(data, true), true).credentials)
    }
    @Test fun skensProviderConfigurationIsCompleteAndKeepsBusanContractKeysCompatible() {
        val expected = mapOf("busan" to "C000", "koone" to "B000", "cheongju" to "D000", "gumi" to "E000",
            "pohang" to "F000", "jeonnam" to "G000", "gangwon" to "J000", "jeonbuk" to "K000")
        assertEquals(expected, Providers.all.filter { it.skens }.associate { it.id to it.skensCode })
        assertTrue(expected.keys.all { Providers.get(it).automatic })
        val contract = Contract("1234", "5678")
        assertEquals(SkensClient.opaque("C000:1234:5678"), SkensClient.contractKey(Providers.skens("busan"), contract))
        assertThrows(IllegalArgumentException::class.java) { Providers.skens("seoul") }
    }
    @Test fun unsupportedSchemaAndFutureObservationsCannotBeImported() {
        val data = AppData(observations = listOf(Observation(System.currentTimeMillis() + 86_400_000, 100.0, "manual")))
        assertThrows(IllegalArgumentException::class.java) { DataCodec.decode(DataCodec.encode(data)) }
        assertThrows(IllegalArgumentException::class.java) { DataCodec.decode(JSONObject(DataCodec.encode(AppData())).put("schema", 99).toString()) }
    }
    @Test fun versionOneStateMigratesWithSubmissionDisabled() {
        val legacy = JSONObject(DataCodec.encode(AppData(ready = true))).apply {
            put("schema", 1)
            remove("submissionSettings")
            remove("submissions")
        }
        val restored = DataCodec.decode(legacy.toString(), allowCredentials = true)
        assertTrue(restored.ready)
        assertFalse(restored.submissionSettings.enabled)
        assertTrue(restored.submissions.isEmpty())
    }
}
