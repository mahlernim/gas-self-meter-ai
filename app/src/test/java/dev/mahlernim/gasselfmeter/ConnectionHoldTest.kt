package dev.mahlernim.gasselfmeter

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

/** A supplier rejecting stored credentials must stop background login until the user reconnects. */
class ConnectionHoldTest {
    private val connected = AppData(
        profile = Profile(contract = "contract", meter = "meter"),
        credentials = Credentials("user", "password"), ready = true)

    @Test fun onlyRejectedCredentialsAreTreatedAsPermanent() {
        assertTrue(BackgroundState.rejectedCredentials(ProviderFailure("login", "authentication")))
        assertTrue(BackgroundState.rejectedCredentials(GasappAuthExpired()))
        // Transient failures stay retryable, and an unsupported response is not a wrong password.
        assertFalse(BackgroundState.rejectedCredentials(ProviderFailure("bills", "network")))
        assertFalse(BackgroundState.rejectedCredentials(ProviderFailure("meter", "unsupported")))
        assertFalse(BackgroundState.rejectedCredentials(ProviderFailure("bills", "http", 503)))
        assertFalse(BackgroundState.rejectedCredentials(SocketTimeoutException()))
        assertFalse(BackgroundState.rejectedCredentials(IOException()))
        assertFalse(BackgroundState.rejectedCredentials(IllegalStateException("계약을 찾지 못했어요.")))
    }

    @Test fun skensRejectedPasswordIsClassifiedRatherThanLeftAsAPlainCheck() {
        // A bare check() here would land in the same bucket as a parse failure and keep retrying.
        val failure = ProviderFailure("login", "authentication")
        assertEquals("authentication", failure.category)
        assertTrue(BackgroundState.rejectedCredentials(failure))
        assertEquals("로그인 또는 인증 만료", DiagnosticCodec.label(failure.category))
    }

    @Test fun holdStopsBackgroundWorkAndSurvivesReload() {
        val held = BackgroundState.holdConnection(connected, connected)
        assertTrue(held.profile.reconnectRequired)
        assertTrue(BackgroundState.connected(connected))
        assertFalse(BackgroundState.connected(held))

        // Held state has to outlive the process, otherwise the next run logs in again.
        val reloaded = DataCodec.decode(DataCodec.encode(held, includeCredentials = true), allowCredentials = true)
        assertTrue(reloaded.profile.reconnectRequired)
    }

    @Test fun holdIsNotAppliedToAnAccountThatChangedUnderneath() {
        val other = connected.copy(profile = connected.profile.copy(contract = "other"))
        assertFalse(BackgroundState.holdConnection(other, connected).profile.reconnectRequired)
        val reconnected = connected.copy(credentials = Credentials("user", "new-password"))
        assertFalse(BackgroundState.holdConnection(reconnected, connected).profile.reconnectRequired)
    }

    @Test fun exportedBackupNeverCarriesTheHoldIntoAnotherDevice() {
        val held = connected.copy(profile = connected.profile.copy(reconnectRequired = true))
        val exported = DataCodec.encode(held)
        assertFalse(JSONObject(exported).getJSONObject("profile").has("reconnectRequired"))
        assertFalse(DataCodec.decode(exported).profile.reconnectRequired)
    }

    @Test fun olderSavedStateWithoutTheFieldStaysUsable() {
        val root = JSONObject(DataCodec.encode(connected, includeCredentials = true))
        root.getJSONObject("profile").remove("reconnectRequired")
        assertFalse(DataCodec.decode(root.toString(), allowCredentials = true).profile.reconnectRequired)
    }
}
