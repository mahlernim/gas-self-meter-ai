package dev.mahlernim.gasselfmeter

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Rule
import org.junit.Test

class AccessibleSettingsFlowTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @After fun clean() { SecureStore(context).erase() }

    @Test fun labelsToggleOnceAndLongMenuOpensAtSelection() {
        SecureStore(context).write(AppData(profile = Profile(reminderHour = 23), ready = true))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.awaitStorage(scenario)
            compose.onNodeWithText("설정", useUnmergedTree = true).performClick()
            compose.onNode(hasText("계량기 숫자를 확인할 시간을 알려드려요") and isToggleable()).assertIsOff()
            compose.onNodeWithText("23시").performScrollTo().performClick()
            compose.onNode(hasText("23시") and isSelected()).assertIsDisplayed()
            compose.onNode(hasText("23시") and isSelected()).performClick()
            compose.awaitStorage(scenario)
            compose.onNodeWithText("다시 연결").performScrollTo().performClick()
            val saved = compose.onNode(hasText("이 기기에 암호화해서 저장") and isToggleable())
            saved.performScrollTo().assertIsOn().performClick().assertIsOff()
            saved.performClick().assertIsOn()
            compose.onNodeWithText("취소").performClick()
        }
    }
}
