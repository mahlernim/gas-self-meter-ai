package dev.mahlernim.gasselfmeter

import android.app.Application
import android.os.StrictMode
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ViewModelStorageFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as Application

    @Test fun localTransactionsCommitBeforeCallbacksAndRejectOverlappingEdits() {
        SecureStore(app).write(AppData(ready = true))
        val owner = ViewModelStore()
        lateinit var vm: GasViewModel
        instrumentation.runOnMainSync {
            vm = ViewModelProvider(owner, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[GasViewModel::class.java]
        }
        try {
            awaitIdle(vm)
            // A worker committed a newer bill/profile snapshot after this screen loaded.
            SecureStore(app).update { it.copy(profile = it.profile.copy(syncTime = 1234L),
                submissions = listOf(SubmissionRecord("old", "2025-12-01", "2025-12-31", 12.0, 1L, "confirmed", "완료"))) }
            val first = CountDownLatch(1)
            var callbackError: String? = "not called"
            var rejected: String? = null
            instrumentation.runOnMainSync {
                vm.calibrate("1000") { error ->
                    callbackError = error
                    assertFalse(vm.busy)
                    assertEquals(1000.0, vm.data.observations.single().reading, 0.0)
                    first.countDown()
                }
                assertTrue(vm.busy)
                vm.resetMeter { rejected = it }
            }
            assertTrue(first.await(10, TimeUnit.SECONDS))
            assertNull(callbackError)
            assertNotNull(rejected)
            assertEquals(1000.0, SecureStore(app).read().observations.single().reading, 0.0)
            assertEquals(1234L, SecureStore(app).read().profile.syncTime)
            assertEquals("confirmed", SecureStore(app).read().submissions.single().status)

            edit { result -> vm.addPeriod("2026-01-01", "2026-01-31", "20", result) }.also { assertNull(it) }
            val before = SecureStore(app).read()
            val overlap = edit { result -> vm.addPeriod("2026-01-15", "2026-02-10", "10", result) }
            assertNotNull(overlap)
            assertEquals(before, SecureStore(app).read())
            instrumentation.runOnMainSync { assertEquals(before, vm.data) }

            edit { result -> vm.restore(before, result) }.also { assertNull(it) }
            edit { result -> vm.resetMeter(result) }.also { assertNull(it) }
            assertNotEquals(before.profile.meter, SecureStore(app).read().profile.meter)
            val otherAccount = SecureStore(app).update { it.copy(profile = it.profile.copy(contract = "other-account")) }
            assertNotNull(edit { result -> vm.calibrate("456", result) })
            assertEquals(otherAccount, SecureStore(app).read())
        } finally {
            instrumentation.runOnMainSync { owner.clear() }
            SecureStore(app).erase()
        }
    }

    @Test
    @androidx.test.filters.SdkSuppress(minSdkVersion = 28)
    fun startupForegroundSaveRestoreAndEraseDoNotReadOrWriteDiskOnMain() {
        SecureStore(app).write(AppData(profile = Profile(reminder = true), ready = true))
        // Initialize platform schedulers before measuring the ViewModel's own storage work.
        androidx.work.WorkManager.getInstance(app)
        val violations = CopyOnWriteArrayList<String>()
        val owner = ViewModelStore()
        lateinit var oldPolicy: StrictMode.ThreadPolicy
        lateinit var vm: GasViewModel
        instrumentation.runOnMainSync {
            oldPolicy = StrictMode.getThreadPolicy()
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder()
                .detectDiskReads().detectDiskWrites()
                .penaltyListener({ command -> command.run() }) { violation -> violations.add(violation.stackTraceToString()) }
                .build())
            vm = ViewModelProvider(owner, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[GasViewModel::class.java]
        }
        try {
            awaitIdle(vm)
            instrumentation.runOnMainSync { vm.onForeground() }
            awaitIdle(vm)
            assertNull(edit { result -> vm.calibrate("123", result) })
            assertNull(edit { result -> vm.restore(AppData(ready = true), result) })
            instrumentation.runOnMainSync { vm.erase() }
            awaitIdle(vm)
            // Drain StrictMode callbacks posted at the end of a main-looper iteration.
            instrumentation.waitForIdleSync()
            assertTrue("Main-thread disk violations: $violations", violations.isEmpty())
        } finally {
            instrumentation.runOnMainSync { StrictMode.setThreadPolicy(oldPolicy); owner.clear() }
            SecureStore(app).erase()
        }
    }

    private fun edit(action: ((String?) -> Unit) -> Unit): String? {
        val done = CountDownLatch(1)
        var result: String? = "callback did not run"
        instrumentation.runOnMainSync { action { result = it; done.countDown() } }
        assertTrue("Mutation callback", done.await(10, TimeUnit.SECONDS))
        return result
    }

    private fun awaitIdle(vm: GasViewModel) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        do {
            var busy = true
            instrumentation.runOnMainSync { busy = vm.busy }
            if (!busy) return
            Thread.sleep(20)
        } while (System.nanoTime() < deadline)
        fail("ViewModel operation timed out")
    }
}
