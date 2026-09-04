package dev.mahlernim.gasselfmeter

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class AlphaConnectionFlowTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun resetLocalState() {
        SecureStore(context).erase()
        Diagnostics.clear(context)
    }

    @After fun clearLocalState() {
        Diagnostics.clear(context)
        SecureStore(context).erase()
    }

    @Test fun samchullyOffersExperimentalLoginWithoutSendingCredentials() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.awaitStorage(scenario)
            compose.onNodeWithText("부산").performScrollTo().performClick()
            compose.onNodeWithText("경기").performClick()
            compose.onNodeWithText("공급사를 선택해 주세요").performScrollTo().performClick()
            compose.onNodeWithText("삼천리").performClick()
            compose.onNodeWithText("삼천리 연결하기").performScrollTo().performClick()

            compose.onNodeWithText("삼천리 연결").assertIsDisplayed()
            compose.onNodeWithText("실험적 조회 연결").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("아이디").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("비밀번호").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("로그인하고 조회").assertIsNotEnabled()

            // A single field must not enable a request. Never click an enabled login button.
            compose.onNodeWithText("아이디").performScrollTo().performTextInput("alpha-ui-test")
            compose.onNodeWithText("로그인하고 조회").assertIsNotEnabled()
            compose.onNodeWithText("아이디").performTextClearance()
            compose.onNodeWithText("비밀번호").performScrollTo().performTextInput("synthetic-password")
            compose.onNodeWithText("로그인하고 조회").assertIsNotEnabled()
            compose.onNodeWithText("취소").performClick()
            compose.onNodeWithText("삼천리 연결").assertDoesNotExist()
            compose.onNodeWithText("삼천리 연결하기").assertIsDisplayed()
        }
    }

    @Test fun localDiagnosticsShowFailureMetadataAndCanBeCleared() {
        val secret = "synthetic-private-response-must-not-appear"
        Diagnostics.record(context, "samchully", "bills",
            ProviderFailure("bills", "http", 503, IllegalStateException(secret)))
        val expectedReport = Diagnostics.report(context)
        assertTrue(expectedReport.contains("|samchully|bills|http|503"))
        assertFalse(expectedReport.contains(secret))

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.awaitStorage(scenario)
            compose.onNodeWithText("진단 기록").performScrollTo().performClick()
            compose.onNode(hasText("진단 기록") and hasAnyAncestor(isDialog())).assertIsDisplayed()
            compose.onNodeWithText("서버로 자동 전송하지 않습니다.").performScrollTo().assertIsDisplayed()
            compose.waitUntil(5_000) { compose.onAllNodesWithText(expectedReport).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(expectedReport).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(secret, substring = true).assertDoesNotExist()
            compose.onNodeWithText("복사").assertIsEnabled()
            compose.onNodeWithText("기록 지우기").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("저장된 진단 기록이 없어요.").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("저장된 진단 기록이 없어요.").assertIsDisplayed()
            assertFalse(Diagnostics.report(context).contains("|samchully|"))
            compose.onNodeWithText("닫기").performClick()

            // Reopening reads the persisted deletion, not merely a cleared dialog state.
            compose.onNodeWithText("진단 기록").performScrollTo().performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("저장된 진단 기록이 없어요.").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("저장된 진단 기록이 없어요.").assertIsDisplayed()
            compose.onNodeWithText("닫기").performClick()
        }
    }

    @Test fun connectedSamchullyStaysReadOnlyAndOffersReconnectAndDiagnostics() {
        val meter = SkensClient.opaque("alpha-ui-fake-meter")
        val data = AppData(
            profile = Profile(providerId = "samchully", meter = meter,
                contract = SkensClient.opaque("samchully:0000000"), customerNumber = "0000000"),
            ready = true,
            samchullyBills = listOf(SamchullyBill("202608", null, null, null, null, 9.8, 12000.0, meter)),
        )
        // No credentials or session means launch and tab navigation cannot refresh the provider.
        SecureStore(context).write(data)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.awaitStorage(scenario)
            compose.onNodeWithText("제출", useUnmergedTree = true).performClick()
            compose.onNodeWithText("삼천리 조회 전용").assertIsDisplayed()
            compose.onNodeWithText("삼천리 고객센터").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("직접 제출", substring = true).assertDoesNotExist()
            compose.onNodeWithText("자가검침 자동제출").assertDoesNotExist()
            compose.onAllNodes(isToggleable()).assertCountEquals(0)

            compose.onNodeWithText("설정", useUnmergedTree = true).performClick()
            compose.onNodeWithText("다시 연결").performScrollTo().performClick()
            compose.onNodeWithText("삼천리 연결").assertIsDisplayed()
            compose.onNodeWithText("로그인하고 조회").assertIsNotEnabled()
            compose.onNodeWithText("취소").performClick()
            compose.onNodeWithText("진단 기록").performScrollTo().performClick()
            compose.onNode(hasText("진단 기록") and hasAnyAncestor(isDialog())).assertIsDisplayed()
            compose.onNodeWithText("서버로 자동 전송하지 않습니다.").assertIsDisplayed()
            compose.onNodeWithText("닫기").performClick()
        }
    }
}
