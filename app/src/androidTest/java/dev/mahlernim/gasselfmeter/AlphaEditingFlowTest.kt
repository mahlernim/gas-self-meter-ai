package dev.mahlernim.gasselfmeter

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.YearMonth

class AlphaEditingFlowTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @After fun clean() { SecureStore(context).erase(); Diagnostics.clear(context) }

    private fun awaitReady(scenario: ActivityScenario<MainActivity>) {
        compose.waitUntil(10_000) {
            var ready = false
            scenario.onActivity { ready = !ViewModelProvider(it)[GasViewModel::class.java].busy }
            ready
        }
    }

    @Test fun meterReplacementReturnsToReadingOnlyAfterCommit() {
        SecureStore(context).write(AppData(profile = Profile(meter = "old-meter"), ready = true))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitReady(scenario)
            compose.onNodeWithText("설정", useUnmergedTree = true).performClick()
            compose.onNodeWithText("새 계량기로 시작").performScrollTo().performClick()
            compose.onNodeWithText("새로 시작").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("새 계량기로 시작할까요?").fetchSemanticsNodes().isEmpty() }
            assertNotEquals("old-meter", SecureStore(context).read().profile.meter)
            compose.onNodeWithText("계량기 보고 보정하기").assertIsDisplayed()
        }
    }

    @Test fun overlappingHistoryRemainsOpenWithSpecificError() {
        val month = YearMonth.from(today()).minusYears(1)
        val period = UsagePeriod(month.atDay(1).toString(), month.atEndOfMonth().toString(), 10.0)
        SecureStore(context).write(AppData(periods = listOf(period), ready = true))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitReady(scenario)
            compose.onNodeWithText("추이", useUnmergedTree = true).performClick()
            compose.onNodeWithText("과거 사용량 추가").performClick()
            compose.onNodeWithText("기간 사용량").performTextInput("30")
            compose.onNodeWithText("저장", useUnmergedTree = true).performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodes(hasText("사용 기간이 겹쳐요.", substring = true) and hasAnyAncestor(isDialog())).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("기간 사용량").assertIsDisplayed()
            assertEquals(listOf(period), SecureStore(context).read().periods)
        }
    }

    @Test fun disconnectedAndUnsupportedProvidersHaveNoSubmissionControls() {
        val manual = listOf("cncity", "daesungclean", "knenergy", "seorabeol", "gse", "myungsung")
        for ((provider, title) in listOf("busan" to "공급사 연결이 필요해요") + manual.map { it to "앱에서 제출을 지원하지 않아요" }) {
            SecureStore(context).write(AppData(profile = Profile(providerId = provider), ready = true))
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                awaitReady(scenario)
                compose.onNodeWithText("제출", useUnmergedTree = true).performClick()
                compose.onNodeWithText(title).assertIsDisplayed()
                compose.onNodeWithText(if (provider == "myungsung") "공급사 안내" else "공급사 홈페이지").assertIsDisplayed()
                compose.onAllNodes(isToggleable()).assertCountEquals(0)
                compose.onNodeWithText("자가검침 자동제출").assertDoesNotExist()
            }
        }
    }
}
