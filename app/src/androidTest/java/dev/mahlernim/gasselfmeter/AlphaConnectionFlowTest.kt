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

    @Test fun samchullyOffersAccountConnectionWithoutSendingCredentials() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.awaitStorage(scenario)
            compose.onNodeWithText("부산").performScrollTo().performClick()
            compose.onNodeWithText("경기").performClick()
            compose.onNodeWithText("공급사를 선택해 주세요").performScrollTo().performClick()
            compose.onNodeWithText("삼천리").performClick()
            compose.onNodeWithText("삼천리 연결하기").performScrollTo().performClick()

            compose.onNodeWithText("삼천리 연결").assertIsDisplayed()
            compose.onNodeWithText("삼천리 홈페이지 계정으로 로그인해요.", substring = true).performScrollTo().assertIsDisplayed()
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

    @Test fun connectedSamchullyShowsManualSubmissionAndReconnect() {
        val meter = "alpha-ui-fake-meter"
        val customer = "0000000"
        val now = System.currentTimeMillis()
        val target = SelfReadTarget("synthetic-cycle", today().toString(), today().plusDays(1).toString(), true, false,
            null, 100.0, Contract(customer, "samchully", "합성 계약"), "synthetic-target", "", "", "", meter)
        val data = AppData(
            profile = Profile(providerId = "samchully", meter = meter,
                contract = SkensClient.opaque("samchully:$customer"), customerNumber = customer, syncTime = now),
            observations = listOf(Observation(now, 111.8, meter)),
            credentials = Credentials("synthetic-user", "synthetic-password"),
            submissionSettings = SubmissionSettings(automatic = false, reminder = false),
            cachedSelfRead = target,
            ready = true,
        )
        SecureStore(context).write(data)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.awaitStorage(scenario)
            compose.onNodeWithText("제출", useUnmergedTree = true).performClick()
            compose.onNodeWithText("자가검침 제출").assertIsDisplayed()
            compose.onNodeWithText("111 m³ 직접 제출").performScrollTo().assertIsEnabled()
            compose.onNodeWithText("이 공급사는 직접 제출만 지원해요.").performScrollTo().assertIsDisplayed()

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
