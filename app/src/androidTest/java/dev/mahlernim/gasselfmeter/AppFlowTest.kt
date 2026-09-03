package dev.mahlernim.gasselfmeter

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class AppFlowTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun screenshot(name: String) {
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        android.os.SystemClock.sleep(750)
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(context.filesDir, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
    @Test fun onboardingDemoCalibrationPersistenceAndHistory() {
        SecureStore(context).erase()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            fun shot(name: String) {
                scenario.onActivity { activity -> androidx.lifecycle.ViewModelProvider(activity)[GasViewModel::class.java].message = null }
                screenshot(name)
            }
            compose.onNodeWithText("일주일에 한 번,\n우리 집 가스를 알아가요").assertIsDisplayed()
            shot("01-welcome")
            compose.onNodeWithText("예시로 둘러보기").performScrollTo().performClick()
            compose.onNodeWithText("예시 데이터 · 실제 우리 집 기록이 아니에요").assertIsDisplayed()
            shot("02-meter")
            compose.onNodeWithText("제출", useUnmergedTree = true).performClick()
            compose.onNodeWithText("검침값 입력").assertIsDisplayed()
            shot("03-submission")
            compose.onNodeWithText("검침", useUnmergedTree = true).performClick()
            compose.onNodeWithText("계량기 보고 확인하기").performClick()
            compose.onNodeWithText("계량기를 보고 확인했나요?").assertIsDisplayed()
            compose.onNodeWithText("이 숫자로 확인").performClick()
            compose.waitUntil(5000) { SecureStore(context).read().observations.size >= 2 }
            val saved = SecureStore(context).read()
            assertNotNull(saved.observations.last().predicted)
            assertEquals(1, saved.observations.count { System.currentTimeMillis() - it.time < 60_000 })
            val encrypted = File(context.filesDir, "gas-state.enc").readBytes().toString(Charsets.UTF_8)
            assertFalse(encrypted.contains("observations"))
            scenario.recreate()
            compose.onNodeWithText("기록", useUnmergedTree = true).performClick()
            compose.onNodeWithText("우리 집 사용 흐름").assertIsDisplayed()
            shot("04-history")
            assertEquals(saved, SecureStore(context).read())
            compose.onNodeWithText("설정", useUnmergedTree = true).performClick()
            compose.onNodeWithText("업데이트 확인").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Google Play에서 최신 버전을 확인해요").assertIsDisplayed()
            shot("05-settings")
        }
    }
}
