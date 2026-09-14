package dev.mahlernim.gasselfmeter

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.YearMonth

class CalibrationFeedbackTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun confirmedReadingAppearsBeforePeriodicClockRefresh() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val month = YearMonth.now().minusYears(1)
        SecureStore(context).write(AppData(periods = listOf(
            UsagePeriod(month.atDay(1).toString(), month.atEndOfMonth().toString(), 30.0)
        )))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.awaitStorage(scenario)
            compose.onNodeWithText("로그인 없이 직접 시작").performScrollTo().performClick()
            compose.awaitStorage(scenario)
            val started = compose.mainClock.currentTime
            for (reading in listOf("1000.0", "1000.1")) {
                compose.onNodeWithText("계량기 보고 보정하기").performClick()
                compose.onNodeWithText("실제 계량기 숫자").performTextReplacement(reading)
                compose.onNodeWithText("이 숫자로 확인").performClick()
                compose.awaitStorage(scenario)
                compose.onNodeWithText("계량기를 보고 확인했나요?").assertDoesNotExist()
                compose.onNodeWithText(decimalText(reading.toDouble()), useUnmergedTree = true).assertIsDisplayed()
                assertEquals(reading.toDouble(), SecureStore(context).read().observations.last().reading, 0.00001)
            }
            assertTrue("Feedback must not wait for the 30-second clock tick", compose.mainClock.currentTime - started < 30_000)
        }
    }
}
