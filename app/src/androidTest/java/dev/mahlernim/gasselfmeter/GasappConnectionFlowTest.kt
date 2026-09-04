package dev.mahlernim.gasselfmeter

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test

class GasappConnectionFlowTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun supportedProviderOpensPhoneAuthenticationWithoutSendingSms() {
        SecureStore(InstrumentationRegistry.getInstrumentation().targetContext).erase()
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithText("부산").performScrollTo().performClick()
            compose.onNodeWithText("서울").performClick()
            compose.onNodeWithText("서울도시가스 연결하기").performScrollTo().performClick()
            compose.onNodeWithText("가스앱 연결").assertIsDisplayed()
            compose.onNodeWithText("휴대전화 번호").assertIsDisplayed()
            compose.onNodeWithText("인증번호 받기").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithText("취소").performScrollTo().performClick()
            compose.onNodeWithText("서울도시가스 연결하기").assertIsDisplayed()
        }
    }
}
