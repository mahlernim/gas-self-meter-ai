package dev.mahlernim.gasselfmeter

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class EnergyTalkBackgroundAuthTest {
    private fun connected() = AppData(ready = true,
        profile = Profile(providerId = "cncity", meter = "synthetic-meter", contract = "synthetic-contract"),
        energyTalkConnection = EnergyTalkConnection("cncity", "synthetic-session-value-only"))

    @Test fun expiredEnergyTalkSessionIsPermanentUntilReconnection() {
        assertTrue(BackgroundState.rejectedCredentials(EnergyTalkAuthException()))
        assertFalse(BackgroundState.rejectedCredentials(IOException("synthetic network failure")))
        assertFalse(BackgroundState.rejectedCredentials(ProviderFailure("meter", "parse")))
        assertFalse(BackgroundState.rejectedCredentials(ProviderFailure("meter", "http", 503)))
    }

    @Test fun connectionHoldSurvivesReloadAndDoesNotDestroySessionOrRecords() {
        val initial = connected()
        assertTrue(BackgroundState.connected(initial))
        val held = BackgroundState.holdConnection(initial, initial)
        val restored = DataCodec.decode(DataCodec.encode(held, true), true)
        assertTrue(restored.profile.reconnectRequired)
        assertFalse(BackgroundState.connected(restored))
        assertEquals(initial.energyTalkConnection, restored.energyTalkConnection)
        assertEquals(initial.observations, restored.observations)
    }

    @Test fun obsoleteAuthenticationFailureCannotSuspendNewSession() {
        val old = connected()
        val current = old.copy(energyTalkConnection = EnergyTalkConnection("cncity", "synthetic-reconnected-session"))
        assertEquals(current, BackgroundState.holdConnection(current, old))
        assertTrue(BackgroundState.connected(current))
    }
}
