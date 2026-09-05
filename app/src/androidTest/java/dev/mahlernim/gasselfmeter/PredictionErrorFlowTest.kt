package dev.mahlernim.gasselfmeter

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.File
import org.junit.After
import org.junit.Rule
import org.junit.Test

class PredictionErrorFlowTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @After fun clean() { SecureStore(context).erase() }

    private fun checkedData(meter: String = "manual") = AppData(
        profile = Profile(providerId = "knenergy", meter = meter),
        observations = (1..3).map { offset ->
            Observation(dayStart(today().minusDays(offset.toLong())), 130.0 - offset * 10,
                "manual", predicted = 130.0 - offset * 11)
        },
        ready = true,
    )

    @Test fun savedPredictionDifferencesSurviveActivityRecreation() {
        SecureStore(context).write(checkedData())
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.awaitStorage(scenario)
            compose.onNodeWithText("평균 차이 2.0 m³ · 최대 3.0 m³").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("현재 오차 범위나 제출 안전성을 보장하지 않아요.", substring = true).performScrollTo().assertIsDisplayed()
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(
                File(context.getExternalFilesDir(null), "0.4.0-prediction-differences.png"))
            scenario.recreate()
            compose.awaitStorage(scenario)
            compose.onNodeWithText("평균 차이 2.0 m³ · 최대 3.0 m³").performScrollTo().assertIsDisplayed()
        }
    }

    @Test fun replacementMeterDoesNotReuseOldPredictionDifferences() {
        SecureStore(context).write(checkedData("replacement"))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.awaitStorage(scenario)
            compose.onNodeWithText("서로 다른 날의 실측과 확인 전 추정이 3회 이상 쌓이면 보여드려요.").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("평균 차이", substring = true).assertDoesNotExist()
        }
    }
}
