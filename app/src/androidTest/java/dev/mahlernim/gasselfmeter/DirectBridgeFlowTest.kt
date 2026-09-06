package dev.mahlernim.gasselfmeter

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class DirectBridgeFlowTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val originalFactory = DirectProviderBridge.clientFactory

    @After fun clean() {
        DirectProviderBridge.clientFactory = originalFactory
        SecureStore(context).erase()
    }

    @Test fun uncertainSubmissionIsDurableAndNeverReplayedOnStatusReadback() {
        val contract = DirectContract("synthetic-contract", "합성 계약", "synthetic-meter")
        val meter = DirectIdentity.meter("daesung", contract.id, contract.meterId)
        val target = SelfReadTarget(
            cycle = "synthetic-direct-cycle", start = today().minusDays(1).toString(), end = today().plusDays(1).toString(),
            eligible = true, submitted = false, submittedValue = null, previousValue = 100.0,
            contract = Contract(contract.id, "daesung", contract.label), serial = "synthetic-order", address = "", planned = "", vLdo = "", installation = meter,
        )
        val fake = FakeClient(contract, target)
        DirectProviderBridge.clientFactory = { _, _ -> fake }
        SecureStore(context).write(AppData(
            profile = Profile(providerId = "daesung", meter = meter, contract = DirectIdentity.contract("daesung", contract.id), customerNumber = contract.id),
            observations = listOf(Observation(System.currentTimeMillis(), 101.9, meter)), credentials = Credentials("synthetic-user", "synthetic-password"), ready = true,
        ))

        val afterSubmit = DirectProviderBridge.submit(context, 101.0)
        assertEquals("uncertain", afterSubmit.submissions.single().status)
        assertEquals(1, fake.submitCalls)

        val restored = SecureStore(context).read()
        assertEquals("uncertain", restored.submissions.single().status)
        assertEquals(101.0, restored.submissions.single().value, .0001)

        runCatching { DirectProviderBridge.submit(context, 101.0) }
        assertEquals(1, fake.submitCalls)
        assertTrue(SecureStore(context).read().submissions.single().status == "uncertain")
    }

    @Test fun viewModelConnectsAndSelectsTheEnrichedDirectContract() {
        val first = DirectContract("synthetic-first", "첫 번째 계약")
        val second = DirectContract("synthetic-second", "두 번째 계약")
        val fake = ConnectingFakeClient(listOf(first, second))
        DirectProviderBridge.clientFactory = { _, _ -> fake }
        val app = context.applicationContext as Application
        val owner = ViewModelStore()
        lateinit var vm: GasViewModel
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            vm = ViewModelProvider(owner, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[GasViewModel::class.java]
        }
        try {
            awaitIdle(vm)
            InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.login("daesung", "synthetic-user", "synthetic-password", true) }
            waitUntil { vm.contracts.size == 2 && !vm.busy }
            InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.selectContract(vm.contracts.single { it.ca == second.id }) }
            awaitIdle(vm)
            val expectedMeter = DirectIdentity.meter("daesung", second.id, "meter-${second.id}")
            assertEquals(DirectIdentity.contract("daesung", second.id), vm.data.profile.contract)
            assertEquals(expectedMeter, vm.data.profile.meter)
            assertEquals("2026-08", vm.data.directBills.single().month)
            assertEquals(second.id, vm.data.cachedSelfRead!!.contract.bp)
            assertEquals(expectedMeter, vm.data.cachedSelfRead!!.installation)
            val persisted = SecureStore(context).read()
            assertEquals(vm.data.profile, persisted.profile)
            assertEquals(vm.data.directBills, persisted.directBills)
            assertEquals(vm.data.cachedSelfRead, persisted.cachedSelfRead)
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { owner.clear() }
        }
    }

    private class FakeClient(private val contract: DirectContract, private val target: SelfReadTarget) : DirectProviderClient {
        var submitCalls = 0
        override fun login() = listOf(contract)
        override fun read(contract: DirectContract) = DirectSnapshot(contract, emptyList(), target)
        override fun submit(contract: DirectContract, target: SelfReadTarget, value: Double) { submitCalls++ }
        override fun close() = Unit
    }

    private class ConnectingFakeClient(private val contracts: List<DirectContract>) : DirectProviderClient {
        override fun login() = contracts
        override fun read(contract: DirectContract): DirectSnapshot {
            val enriched = contract.copy(meterId = "meter-${contract.id}")
            val meter = DirectIdentity.meter("daesung", contract.id, enriched.meterId)
            val target = SelfReadTarget("synthetic-${contract.id}-cycle", today().minusDays(1).toString(), today().plusDays(1).toString(),
                true, false, null, 100.0, Contract(contract.id, "daesung", contract.label), "order-${contract.id}", "", "", "", meter)
            return DirectSnapshot(enriched, listOf(DirectBill("2026-08", 12.5, 14300.0, "2026-07-01", "2026-07-31", 50.0, 62.5, enriched.meterId)), target)
        }
        override fun submit(contract: DirectContract, target: SelfReadTarget, value: Double) = Unit
        override fun close() = Unit
    }

    private fun awaitIdle(vm: GasViewModel) = waitUntil { !vm.busy }
    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            var satisfied = false
            InstrumentationRegistry.getInstrumentation().runOnMainSync { satisfied = condition() }
            if (satisfied) return
            Thread.sleep(20)
        }
        throw AssertionError("ViewModel operation timed out")
    }
}
