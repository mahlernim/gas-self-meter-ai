package dev.mahlernim.gasselfmeter

import org.junit.Assert.*
import org.junit.Test
import java.net.SocketTimeoutException
import java.time.Instant
import javax.net.ssl.SSLException

class DiagnosticsTest {
    private val time = Instant.parse("2026-09-04T00:00:00Z")
    private fun entry(error: Exception = ProviderFailure("bills", "http", 503)) =
        DiagnosticCodec.create("samchully", "connect", error, "0.3.1", time, "1234abcd")

    @Test fun typedFailurePreservesSafeStageCategoryAndHttp() {
        val entry = entry()
        assertEquals("bills", entry.stage)
        assertEquals("http", entry.category)
        assertEquals(503, entry.httpCode)
        assertEquals(entry, DiagnosticCodec.decode(DiagnosticCodec.encode(entry)))
    }
    @Test fun neverPersistsExceptionMessageOrCause() {
        val encoded = DiagnosticCodec.encode(entry(ProviderFailure("login", "authentication", 401, IllegalStateException("password=secret customer=123456789"))))
        assertFalse(encoded.contains("secret"))
        assertFalse(encoded.contains("123456789"))
        assertFalse(DiagnosticCodec.encode(entry(IllegalStateException("token=secret"))).contains("secret"))
    }
    @Test fun untrustedMetadataIsNotEchoed() {
        val entry = DiagnosticCodec.create("customer-secret", "token-secret", ProviderFailure("address-secret", "password-secret", 999), "secret", time, "secret")
        val encoded = DiagnosticCodec.encode(entry)
        assertFalse(encoded.contains("secret"))
        assertEquals("unknown", entry.provider)
        assertEquals("unknown", entry.stage)
        assertEquals("unknown", entry.category)
        assertNull(entry.httpCode)
    }
    @Test fun networkTypesUseStaticCategories() {
        assertEquals("timeout", entry(SocketTimeoutException("private host")).category)
        assertEquals("tls", entry(SSLException("private certificate")).category)
        assertEquals("network", entry(java.io.IOException("private address")).category)
        assertEquals("parse", entry(org.json.JSONException("private payload")).category)
    }
    @Test fun onlyNewestHundredEntriesRemain() {
        var text = ""
        repeat(130) { index ->
            text = DiagnosticCodec.append(text, entry().copy(time = time.plusSeconds(index.toLong()).toString()))
        }
        val entries = DiagnosticCodec.entries(text)
        assertEquals(100, entries.size)
        assertEquals(time.plusSeconds(30).toString(), entries.first().time)
        assertEquals(time.plusSeconds(129).toString(), entries.last().time)
        assertTrue(text.toByteArray().size < DiagnosticCodec.MAX_BYTES)
    }
    @Test fun corruptedRecordsNeverAppearInReportData() {
        val valid = DiagnosticCodec.encode(entry())
        assertEquals(listOf(entry()), DiagnosticCodec.entries("private raw payload\n$valid\n${valid.replace("samchully", "account-secret")}"))
        assertNull(DiagnosticCodec.decode(valid.replace("503", "999")))
        assertNull(DiagnosticCodec.decode(valid + "|secret"))
    }
    @Test fun prefixedSamchullyStagesNormalize() {
        assertEquals("contracts", entry(ProviderFailure("samchully_contracts", "parse")).stage)
        assertEquals("user", entry(ProviderFailure("samchully_user", "parse")).stage)
    }
}
