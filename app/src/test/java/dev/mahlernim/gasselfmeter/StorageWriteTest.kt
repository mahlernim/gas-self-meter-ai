package dev.mahlernim.gasselfmeter

import org.junit.Assert.*
import org.junit.Test

/** Internal storage and human-readable exports share an encoder but not a format. */
class StorageWriteTest {
    private val data = AppData(
        profile = Profile(contract = "contract", meter = "manual"),
        periods = listOf(UsagePeriod("2025-01-01", "2025-01-31", 31.0, billMonth = "202501", amount = 18_810.0)),
        credentials = Credentials("user", "password"), ready = true)

    @Test fun theEncryptedPayloadIsCompactAndTheBackupStaysReadable() {
        val stored = DataCodec.encode(data, includeCredentials = true, indent = 0)
        val exported = DataCodec.encode(data, includeCredentials = true)

        assertFalse(stored.contains("\n"))
        assertTrue(exported.contains("\n"))
        assertTrue(stored.length < exported.length)
    }

    @Test fun bothFormsDecodeToTheSameRecords() {
        val compact = DataCodec.decode(DataCodec.encode(data, includeCredentials = true, indent = 0), allowCredentials = true)
        val indented = DataCodec.decode(DataCodec.encode(data, includeCredentials = true), allowCredentials = true)

        assertEquals(indented, compact)
        assertEquals(data.periods, compact.periods)
        assertEquals(data.credentials, compact.credentials)
    }

    @Test fun anExportedBackupIsStillIndentedByDefault() {
        assertTrue(DataCodec.encode(data).contains("\n"))
        assertEquals(data.periods, DataCodec.decode(DataCodec.encode(data)).periods)
    }
}
