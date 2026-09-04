package dev.mahlernim.gasselfmeter

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ManualAndBackupFlowTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun manualReadingHistoryExportAndRestoreThroughSystemPicker() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        SecureStore(context).erase()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        if (android.os.Build.VERSION.SDK_INT >= 33) device.executeShellCommand("pm grant dev.mahlernim.gasselfmeter android.permission.POST_NOTIFICATIONS")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.awaitStorage(scenario)
            compose.onNodeWithText("로그인 없이 직접 시작").performScrollTo().performClick()
            compose.awaitStorage(scenario)
            compose.onNodeWithText("계량기 보고 보정하기").performClick()
            compose.onNodeWithText("실제 계량기 숫자").performTextInput("1000.0")
            compose.onNodeWithText("이 숫자로 확인").performClick()
            compose.awaitStorage(scenario)
            compose.onNodeWithText("추이", useUnmergedTree = true).performClick()
            compose.onNodeWithText("과거 사용량 추가").performClick()
            compose.onNodeWithText("기간 사용량").performTextInput("30")
            compose.onNodeWithText("저장", useUnmergedTree = true).performClick()
            compose.waitUntil(5000) { SecureStore(context).read().periods.size == 1 }
            assertNotNull(Estimator.estimate(SecureStore(context).read()).reading)
            scenario.onActivity { activity -> androidx.lifecycle.ViewModelProvider(activity)[GasViewModel::class.java].message = null }
            compose.onNodeWithText("설정", useUnmergedTree = true).performClick()
            compose.onNode(isToggleable()).performClick()
            compose.waitUntil(5000) { SecureStore(context).read().profile.reminder }
            compose.awaitStorage(scenario)
            val workManager = androidx.work.WorkManager.getInstance(context)
            val scheduled = workManager.getWorkInfosForUniqueWork("calibration-daily-check").get(5, java.util.concurrent.TimeUnit.SECONDS)
            assertTrue(scheduled.any { it.state == androidx.work.WorkInfo.State.ENQUEUED })
            // A fresh calibration suppresses the weekly reminder and its follow-ups.
            val reminderWork = androidx.work.OneTimeWorkRequestBuilder<ReminderWorker>().build()
            workManager.enqueue(reminderWork).result.get(5, java.util.concurrent.TimeUnit.SECONDS)
            compose.waitUntil(10_000) { workManager.getWorkInfoById(reminderWork.id).get()?.state == androidx.work.WorkInfo.State.SUCCEEDED }
            val notifications = context.getSystemService(android.app.NotificationManager::class.java)
            assertFalse(notifications.activeNotifications.any { it.id == 1 })
            compose.onNodeWithText("기록 내보내기").performScrollTo().performClick()
            compose.waitForIdle()
            device.waitForIdle()
            val filename = "gas-test-${System.currentTimeMillis()}.json"
            val filenameInput = device.wait(Until.findObject(By.res("android:id/title").clazz("android.widget.EditText")), 5000)
            assertNotNull("System document picker filename field", filenameInput)
            filenameInput.text = filename
            val save = device.wait(Until.findObject(By.res("android:id/button1")), 5000)
            device.dumpWindowHierarchy(java.io.File(context.filesDir, "picker.xml"))
            assertNotNull("System document picker Save button", save)
            save.click()
            device.wait(Until.hasObject(By.pkg("dev.mahlernim.gasselfmeter")), 5000)
            compose.awaitStorage(scenario)
            scenario.onActivity { activity -> androidx.lifecycle.ViewModelProvider(activity)[GasViewModel::class.java].message = null }
            compose.onNodeWithText("백업 가져오기").performScrollTo().performClick()
            val file = device.wait(Until.findObject(By.text(filename)), 5000)
            assertNotNull("Exported backup should appear in the system picker", file)
            file.click()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("백업 기록으로 바꿀까요?").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("백업 기록으로 바꿀까요?").assertIsDisplayed()
            compose.onNodeWithText("복원", useUnmergedTree = true).performClick()
            compose.awaitStorage(scenario)
            val restored = SecureStore(context).read()
            assertEquals(1, restored.periods.size)
            assertEquals(1000.0, restored.observations.single().reading, .00001)
            assertNull(restored.credentials)
            assertFalse(restored.profile.reminder)
            assertFalse(notifications.activeNotifications.any { it.id == 1 })
        }
    }
}
