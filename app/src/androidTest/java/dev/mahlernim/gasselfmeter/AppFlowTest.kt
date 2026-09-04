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
import java.time.YearMonth

class AppFlowTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun screenshot(name: String) {
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        android.os.SystemClock.sleep(750)
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(requireNotNull(context.getExternalFilesDir(null)), "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
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
            compose.onNodeWithText("자가검침 제출").assertIsDisplayed()
            compose.onNodeWithText("당월지침 제출").assertIsDisplayed()
            compose.onNodeWithText("공급사 제출 기능").assertDoesNotExist()
            shot("03-submission")
            compose.onAllNodes(isToggleable())[0].performScrollTo().performClick()
            compose.onNodeWithText("7일 이내").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("분").performScrollTo().assertIsDisplayed()
            shot("07-submission-alerts")
            compose.onNodeWithText("검침", useUnmergedTree = true).performClick()
            compose.onNodeWithText("계량기 보고 보정하기").performClick()
            compose.onNodeWithText("계량기를 보고 확인했나요?").assertIsDisplayed()
            compose.onNodeWithText("이 숫자로 확인").performClick()
            compose.waitUntil(5000) { SecureStore(context).read().observations.size >= 2 }
            val saved = SecureStore(context).read()
            assertNotNull(saved.observations.last().predicted)
            assertEquals(1, saved.observations.count { System.currentTimeMillis() - it.time < 60_000 })
            val encrypted = File(context.filesDir, "gas-state.enc").readBytes().toString(Charsets.UTF_8)
            assertFalse(encrypted.contains("observations"))
            scenario.recreate()
            compose.onNodeWithText("추이", useUnmergedTree = true).performClick()
            compose.onNodeWithText("사용 추이").assertIsDisplayed()
            compose.onNodeWithText("최근 24개월").assertDoesNotExist()
            val latestMonth = HistorySummary.months(saved.periods, YearMonth.from(today())).last()
            val latestDescription = buildString {
                append("${latestMonth.month.year}년 ${latestMonth.month.monthValue}월, ")
                append("사용량 ${decimalText(requireNotNull(latestMonth.usage))} 세제곱미터")
                append(", 청구월 합계 ${decimalText(requireNotNull(latestMonth.billedAmount), 0)}원")
            }
            compose.onNodeWithContentDescription(latestDescription).performClick()
            compose.onNodeWithText("${latestMonth.month.year}년 ${latestMonth.month.monthValue}월").assertIsDisplayed()
            compose.onNodeWithText("가스비 ${decimalText(latestMonth.billedAmount!!, 0)}원 · 해당 청구월의 실제 합계").assertIsDisplayed()
            shot("04-history")
            assertEquals(saved, SecureStore(context).read())
            compose.onNodeWithText("설정", useUnmergedTree = true).performClick()
            compose.onNodeWithText("앱 설정").assertIsDisplayed()
            compose.onNodeWithText("3회").performScrollTo().performClick()
            compose.onNodeWithText("6회").performClick()
            compose.waitUntil(5000) { SecureStore(context).read().profile.reminderRepeatCount == 6 }
            shot("05-settings")
            compose.onNodeWithText("업데이트 확인").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Google Play에서 최신 버전을 확인해요").assertIsDisplayed()
            shot("06-settings-links")
        }
    }
}
