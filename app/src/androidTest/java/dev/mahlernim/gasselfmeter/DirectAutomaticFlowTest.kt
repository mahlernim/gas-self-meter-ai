package dev.mahlernim.gasselfmeter

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** All provider I/O is replaced with a fake. No customer authentication or real submission occurs. */
class DirectAutomaticFlowTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val originalFactory = DirectProviderBridge.clientFactory
    private val ids = listOf("daesung", "daesungclean", "haeyang")

    @After fun clean() {
        DirectProviderBridge.clientFactory = originalFactory
        SecureStore(context).erase()
    }

    private fun setup(id: String, acknowledge: Boolean): FakeClient {
        SecureStore(context).erase()
        val time = System.currentTimeMillis()
        val contract = DirectContract("synthetic-contract", "합성 계약", "synthetic-meter")
        val meter = DirectIdentity.meter(id, contract.id, contract.meterId)
        val target = SelfReadTarget("$id-synthetic-cycle", today().minusDays(2).toString(), today().toString(), true, false, null, 90.0,
            Contract(contract.id, id, contract.label), "synthetic-order", "", "", "", meter)
        val fake = FakeClient(contract, target, acknowledge)
        DirectProviderBridge.clientFactory = { actualId, _ -> require(actualId == id); fake }
        SecureStore(context).write(AppData(
            profile = Profile(id, meter, DirectIdentity.contract(id, contract.id), customerNumber = contract.id), ready = true,
            credentials = Credentials("synthetic-user", "synthetic-password"), cachedSelfRead = target,
            observations = listOf(Observation(time - 8 * 86_400_000L, 100.0, meter), Observation(time - 86_400_000L, 107.0, meter)),
            submissionSettings = SubmissionSettings(automatic = true)))
        return fake
    }

    @Test fun allThreeProvidersAutomaticallySubmitOnceAndConfirmByReadback() {
        ids.forEach { id ->
            val fake = setup(id, true)
            val result = DirectProviderBridge.submit(context, automatic = true)
            assertEquals(id, 1, fake.sends)
            assertEquals(id, "confirmed", result.submissions.single().status)
            assertTrue(id, result.submissionSettings.automatic)
            runCatching { DirectProviderBridge.submit(context, automatic = true) }
            assertEquals(id, 1, fake.sends)
        }
    }

    @Test fun uncertainResultSurvivesReloadAndBlocksEveryAutomaticReplay() {
        ids.forEach { id ->
            val fake = setup(id, false)
            DirectProviderBridge.submit(context, automatic = true)
            assertEquals(id, "uncertain", SecureStore(context).read().submissions.single().status)
            DirectProviderBridge.checkStatus(context)
            runCatching { DirectProviderBridge.submit(context, automatic = true) }
            assertEquals(id, 1, fake.sends)
        }
    }

    @Test fun consentRevokedDuringFreshReadPreventsAnySend() {
        val fake = setup("daesung", true)
        fake.onRead = { SecureStore(context).update { it.copy(submissionSettings = it.submissionSettings.copy(automatic = false)) } }
        assertTrue(runCatching { DirectProviderBridge.submit(context, automatic = true) }.isFailure)
        assertEquals(0, fake.sends)
        assertFalse(SecureStore(context).read().submissionSettings.automatic)
    }

    @Test fun consentRevokedAfterPendingIsPersistedStillPreventsTheSend() {
        val fake = setup("haeyang", true)
        var revoked = false
        val result = DirectProviderBridge.submit(context, automatic = true, cancelled = {
            val store = SecureStore(context)
            if (!revoked && store.read().submissions.any { it.status == "pending" }) {
                revoked = true
                store.update { it.copy(submissionSettings = it.submissionSettings.copy(automatic = false)) }
            }
            false
        })
        assertTrue(revoked)
        assertEquals(0, fake.sends)
        assertEquals("rejected", result.submissions.single().status)
    }

    @Test fun replacementMeterDuringRefreshDisablesAutomaticSubmission() {
        val fake = setup("daesungclean", true)
        fake.replaceMeter = true
        assertTrue(runCatching { DirectProviderBridge.submit(context, automatic = true) }.isFailure)
        assertEquals(0, fake.sends)
        assertFalse(SecureStore(context).read().submissionSettings.automatic)
    }

    private class FakeClient(private val contract: DirectContract, private val target: SelfReadTarget, private val acknowledge: Boolean) : DirectProviderClient {
        var sends = 0
        var reading: Double? = null
        var replaceMeter = false
        var onRead: (() -> Unit)? = null
        override fun login() = listOf(contract)
        override fun read(contract: DirectContract): DirectSnapshot {
            onRead?.invoke()
            val changed = if (replaceMeter) contract.copy(meterId = "replacement") else contract
            val actual = target.copy(installation = DirectIdentity.meter(target.contract.ca, changed.id, changed.meterId),
                submitted = acknowledge && reading != null, submittedValue = if (acknowledge) reading else null)
            return DirectSnapshot(changed, emptyList(), actual)
        }
        override fun submit(contract: DirectContract, target: SelfReadTarget, value: Double) { sends++; reading = value }
        override fun close() = Unit
    }
}
