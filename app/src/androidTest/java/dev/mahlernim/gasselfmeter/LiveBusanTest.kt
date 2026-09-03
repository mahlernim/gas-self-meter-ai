package dev.mahlernim.gasselfmeter

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Explicitly opt-in. Input is private temporary app data, deleted before the first network request. */
class LiveBusanTest {
    @Test fun authorizedReadOnlyAndroidPortalProbe() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val input = File(context.filesDir, "live-probe-input.json")
        assumeTrue("No authorized live-probe input supplied", input.isFile)
        val raw = try { input.readText() } finally { input.delete() }
        val json = JSONObject(raw)
        val credentials = Credentials(json.getString("username"), json.getString("password"))
        BusanClient(credentials).use { client ->
            val contracts = client.login()
            assertEquals("This probe expects the authorized single-contract test account", 1, contracts.size)
            val result = client.history(contracts.single()) {}
            assertTrue(result.periods.map { it.billMonth }.distinct().size >= 12)
            assertNull("Every advertised bill should parse", result.warning)
            val latest = result.periods.maxBy { it.end }
            assertEquals("Current meter must match the latest bill", result.meter, latest.meter)
            val data = AppData(Profile("busan", result.meter), result.periods, credentials = credentials, ready = true)
            assertNotNull("Dated bills must support a current seasonal estimate", Estimator.estimate(data).reading)
            val export = DataCodec.encode(data)
            assertFalse(export.contains(credentials.password))
            assertFalse(export.contains(credentials.username))
            println("Authorized Android read-only probe passed. Billing months: ${result.periods.map { it.billMonth }.distinct().size}. No readings submitted.")
        }
    }
}
