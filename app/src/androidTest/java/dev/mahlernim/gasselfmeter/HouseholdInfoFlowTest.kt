package dev.mahlernim.gasselfmeter

import android.content.ClipboardManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Synthetic local state only. No login, refresh or submission request is sent. */
class HouseholdInfoFlowTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @After fun clean() { SecureStore(context).erase() }

    @Test fun infoBelowForecastCopiesExactValuesAndSurvivesRecreation() {
        val date = today()
        val contract = Contract("synthetic-bp", "0000123456")
        val serial = "DEMO-000000"
        val meter = SkensClient.opaque(serial)
        val target = SelfReadTarget("synthetic-cycle", date.toString(), date.plusDays(7).toString(),
            false, false, null, 100.0, contract, serial, "부산광역시 예시로 123, 101동 1001호",
            date.plusDays(7).toString(), "", "")
        val state = AppData(profile = Profile(providerId = "busan", meter = meter,
            contract = SkensClient.contractKey(Providers.get("busan"), contract),
            customerNumber = contract.ca, plannedDate = date.plusDays(7).toString(), syncTime = dayStart(date)),
            periods = listOf(UsagePeriod(date.minusDays(31).toString(), date.minusDays(2).toString(), 30.0,
                meter, 70.0, 100.0, date.minusMonths(1).toString().take(7), 33000.0, 1000.0, 1000.0)),
            observations = listOf(Observation(dayStart(date.minusDays(1)), 100.0, meter),
                Observation(dayStart(date), 101.0, meter)), cachedSelfRead = target, ready = true)
        SecureStore(context).write(state)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.awaitStorage(scenario)
            compose.onNodeWithText("부산도시가스").assertIsDisplayed()
            compose.onNodeWithContentDescription("계약자번호 복사").assertIsNotDisplayed()
            compose.onNodeWithText("우리 집 정보").performScrollTo()
            val forecast = compose.onNodeWithText("예상 당월 요금", substring = true).fetchSemanticsNode().positionInRoot.y
            val info = compose.onNodeWithText("우리 집 정보").fetchSemanticsNode().positionInRoot.y
            assertTrue("Household card must follow the monthly forecast", info > forecast)
            compose.onNodeWithText("공급사 정보 갱신").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("사용 추이 보기").performScrollTo()
            val suffix = InstrumentationRegistry.getArguments().getString("screenshotSuffix", "default")
            assertTrue(UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(
                File(context.getExternalFilesDir(null), "household-info-$suffix.png")))
            listOf("계약자번호" to contract.ca, "계량기번호" to serial, "공급 주소" to target.address).forEach { (label, value) ->
                compose.onNodeWithContentDescription("$label 복사").performScrollTo().performClick()
                scenario.onActivity { activity ->
                    val clip = activity.getSystemService(ClipboardManager::class.java).primaryClip!!
                    assertEquals(value, clip.getItemAt(0).text.toString())
                    assertEquals(label, clip.description.label.toString())
                    assertTrue(clip.description.extras!!.getBoolean("android.content.extra.IS_SENSITIVE"))
                }
            }
            assertEquals(state, SecureStore(context).read())
            scenario.recreate()
            compose.awaitStorage(scenario)
            compose.onNodeWithText(target.address).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("공급사 정보 갱신").performScrollTo().assertIsDisplayed()
        }
    }
}
