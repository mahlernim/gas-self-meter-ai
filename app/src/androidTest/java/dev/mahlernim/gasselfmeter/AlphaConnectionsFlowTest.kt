package dev.mahlernim.gasselfmeter

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AlphaConnectionsFlowTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val consentText = "내 계정 또는 조회 권한이 있는 고객번호이며, 선택한 공급사로 직접 보내 조회하는 데 동의해요."
    private val runText = "추가 조회 실행"

    @After fun clean() { Diagnostics.clear(context) }

    private fun consent() = compose.onNode(hasText(consentText) and isToggleable())
    private fun customer() = compose.onNodeWithText("고객번호 (최대 9자리)")
    private fun show(runner: suspend (String, String, String) -> AlphaProbeResult) {
        compose.setContent {
            MaterialTheme {
                AlphaConnectionsDialog(initialProviderId = "knenergy", onDismiss = {}, runCheck = runner)
            }
        }
    }

    @Test fun energyTalkConsentWarningCanBeCancelledWithoutOpeningOfficialWebView() {
        val calls = AtomicInteger()
        show { _, _, _ -> calls.incrementAndGet(); error("No runner may execute before consent") }
        compose.onNodeWithText("에너지톡").performScrollTo().performClick()
        compose.onNodeWithText("공식 로그인으로 조회").performScrollTo().performClick()
        compose.onNodeWithText("에너지톡 실험적 조회").assertIsDisplayed()
        compose.onNodeWithText("이 웹 화면은 조회 전용이 아니므로", substring = true)
            .performScrollTo().assertIsDisplayed()
        // Capture the consent page only. Do not click the button that constructs the WebView.
        val screenshot = File(requireNotNull(context.getExternalFilesDir(null)), "energytalk-consent.png")
        assertTrue(UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(screenshot))
        compose.onNodeWithText("취소").performScrollTo().performClick()
        compose.onNodeWithText("공급사 추가 조회").assertIsDisplayed()
        compose.onNodeWithText("공식 로그인으로 조회").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("에너지톡 실험적 조회").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, calls.get()) }
    }

    @Test fun lookupRequiresBothValidInputAndExplicitConsent() {
        val calls = AtomicInteger()
        show { _, _, _ -> calls.incrementAndGet(); AlphaProbeResult(listOf("합성 결과"), "합성 진단") }
        compose.onNodeWithText(runText).assertIsNotEnabled()
        customer().performScrollTo().performTextInput("123456789")
        compose.onNodeWithText(runText).assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, calls.get()) }

        consent().performScrollTo().assertIsOff().performClick().assertIsOn()
        compose.onNodeWithText(runText).assertIsEnabled()
        compose.runOnIdle { assertEquals(0, calls.get()) }

        customer().performScrollTo().performTextReplacement("1234567890")
        compose.onNodeWithText(runText).assertIsNotEnabled()
        customer().performTextClearance()
        compose.onNodeWithText(runText).assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, calls.get()) }
    }

    @Test fun explicitLookupShowsSyntheticResultAndRequiresFreshConsentToRepeat() {
        val calls = AtomicInteger()
        show { provider, identity, password ->
            assertEquals("knenergy", provider)
            assertEquals("123456789", identity)
            assertEquals("", password)
            calls.incrementAndGet()
            AlphaProbeResult(listOf("합성 고지금액 12,345원", "합성 보정사용량 12.5 m³"), "합성 조회 완료")
        }
        customer().performScrollTo().performTextInput("123456789")
        consent().performScrollTo().performClick()
        compose.onNodeWithText(runText).performClick()
        compose.onNodeWithText("합성 고지금액 12,345원").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("합성 보정사용량 12.5 m³").performScrollTo().assertIsDisplayed()
        consent().performScrollTo().assertIsOff()
        compose.onNodeWithText(runText).assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, calls.get()) }
    }

    @Test fun failedLookupNeverDisplaysOrRecordsRawExceptionMessage() {
        val privateMessage = "synthetic-private-password-and-customer-response"
        val calls = AtomicInteger()
        show { _, _, _ -> calls.incrementAndGet(); throw IllegalStateException(privateMessage) }
        customer().performScrollTo().performTextInput("123456789")
        consent().performScrollTo().performClick()
        compose.onNodeWithText(runText).performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("connect · 입력 또는 데이터 검증 실패", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("connect · 입력 또는 데이터 검증 실패", substring = true)
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(privateMessage, substring = true).assertDoesNotExist()
        consent().performScrollTo().assertIsOff()
        compose.onNodeWithText(runText).assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, calls.get()) }
        assertFalse(Diagnostics.report(context).contains(privateMessage))
    }

    @Test fun runningLookupCanBeCancelledAndRetriedWithoutStaleResult() {
        val calls = AtomicInteger()
        val cancellations = AtomicInteger()
        show { _, _, _ ->
            if (calls.incrementAndGet() == 1) {
                try { kotlinx.coroutines.awaitCancellation() }
                finally { cancellations.incrementAndGet() }
            }
            AlphaProbeResult(listOf("재시도 합성 결과"), "합성 진단")
        }
        customer().performScrollTo().performTextInput("123456789")
        consent().performScrollTo().performClick()
        compose.onNodeWithText(runText).performClick()
        compose.onNodeWithText("조회 취소").assertIsEnabled().performClick()
        compose.onNodeWithText("조회를 취소했어요. 다시 실행할 수 있어요.").performScrollTo().assertIsDisplayed()
        consent().performScrollTo().assertIsOff().performClick()
        compose.onNodeWithText(runText).performClick()
        compose.onNodeWithText("재시도 합성 결과").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("피드백 초안 보내기").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(2, calls.get()); assertEquals(1, cancellations.get()) }
    }
}
