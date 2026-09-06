package dev.mahlernim.gasselfmeter

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Uses only an in-process synthetic snapshot. No provider login or request is made. */
@RunWith(AndroidJUnit4::class)
class EnergyTalkBridgeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @After fun clearStore() {
        runCatching { SecureStore(context).erase() }
    }

    @Test fun connectPersistsOnlyEncryptedSessionAndRequiresMatchingPhysicalMeter() {
        SecureStore(context).erase()
        val connection = EnergyTalkConnection("srb", "synthetic-token-12345")
        val snapshot = EnergyTalkSnapshot(
            "srb", "합성 주소", listOf(EnergyTalkUsage("202609", "12000", "12 m³")),
            EnergyTalkMeter(true, "100", null, "합성 대상"), emptyList(),
        )
        val saved = EnergyTalkBridge.connect(context, connection, snapshot)
        assertEquals("seorabeol", saved.profile.providerId)
        assertEquals(connection, saved.energyTalkConnection)
        assertEquals(saved, SecureStore(context).read())
        assertEquals(1, saved.energyTalkBills.size)
        assertNotNull(saved.cachedSelfRead)
        assertEquals(saved.profile.meter, saved.cachedSelfRead!!.serial)

        val portable = DataCodec.encode(saved)
        assertFalse(portable.contains(connection.session))
        assertFalse(portable.contains("energyTalkConnection"))
        val restoredPortable = DataCodec.decode(portable)
        assertNull(restoredPortable.energyTalkConnection)
        assertEquals(saved.energyTalkBills, restoredPortable.energyTalkBills)

        val wrongMeter = saved.copy(profile = saved.profile.copy(meter = "manual-reset"))
        val decision = EnergyTalkSubmissionPolicy.decide(wrongMeter, saved.cachedSelfRead, automatic = false)
        assertFalse(decision.allowed)
        assertTrue(decision.reason.contains("계량기"))
    }
}
