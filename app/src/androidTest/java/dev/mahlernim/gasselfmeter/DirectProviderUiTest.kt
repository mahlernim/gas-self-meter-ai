package dev.mahlernim.gasselfmeter

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Rule
import org.junit.Test

class DirectProviderUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @After fun clearStore() { SecureStore(context).erase(); Diagnostics.clear(context) }

    @Test fun directSuppliersOpenTheNormalCredentialDialogWithoutARequest() {
        listOf(
            Triple("대구", "대성에너지", "대성에너지 연결"),
            Triple("대구", "대성청정에너지", "대성청정에너지 연결"),
            Triple("광주", "해양에너지", "해양에너지 연결"),
        ).forEach { (region, provider, title) ->
            SecureStore(context).erase()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                compose.awaitStorage(scenario)
                compose.onNodeWithText("부산").performScrollTo().performClick()
                compose.onNodeWithText(region).performScrollTo().performClick()
                compose.onNodeWithText("공급사를 선택해 주세요").performScrollTo().performClick()
                compose.onNodeWithText(provider).performClick()
                compose.onNodeWithText("${provider} 연결하기").performScrollTo().performClick()
                compose.onNodeWithText(title).assertIsDisplayed()
                compose.onNodeWithText("${provider} 홈페이지 계정으로 로그인해요.", substring = true).performScrollTo().assertIsDisplayed()
                compose.onNodeWithText("로그인하고 조회").assertIsNotEnabled()
                compose.onNodeWithText("취소").performClick()
            }
        }
    }

    @Test fun connectedDirectSuppliersShowBillHistoryAndSameDaySubmissionReview() {
        listOf("daesung" to "대성에너지", "haeyang" to "해양에너지").forEach { (providerId, name) ->
            SecureStore(context).write(connectedData(providerId, name))
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                compose.awaitStorage(scenario)
                compose.onNodeWithText("추이", useUnmergedTree = true).performClick()
                compose.onNodeWithText("2026.08 청구").performScrollTo().assertIsDisplayed()
                compose.onNodeWithText("청구 사용량 12.5 m³").performScrollTo().assertIsDisplayed()
                compose.onNodeWithText("14,300원").performScrollTo().assertIsDisplayed()
                compose.onNodeWithText("제출", useUnmergedTree = true).performClick()
                compose.onNodeWithText("자가검침 제출").assertIsDisplayed()
                compose.onNodeWithText("101 m³ 직접 제출").performScrollTo().assertIsEnabled()
                compose.onNodeWithText("이 공급사는 직접 제출만 지원해요.").performScrollTo().assertIsDisplayed()
            }
            SecureStore(context).erase()
        }
    }

    private fun connectedData(providerId: String, name: String): AppData {
        val contract = "synthetic-$providerId-contract"
        val meter = DirectIdentity.meter(providerId, contract, "synthetic-meter")
        val now = System.currentTimeMillis()
        val target = SelfReadTarget(
            cycle = "synthetic-$providerId-cycle", start = today().minusDays(1).toString(), end = today().plusDays(1).toString(),
            eligible = true, submitted = false, submittedValue = null, previousValue = 100.0,
            contract = Contract(contract, providerId, "$name 합성 계약"), serial = "synthetic-order", address = "", planned = "", vLdo = "", installation = meter,
        )
        return AppData(
            profile = Profile(providerId = providerId, meter = meter, contract = DirectIdentity.contract(providerId, contract), customerNumber = contract, syncTime = now),
            observations = listOf(Observation(now, 101.9, meter)), credentials = Credentials("synthetic-user", "synthetic-password"),
            cachedSelfRead = target, directBills = listOf(DirectBill("2026-08", usage = 12.5, amount = 14300.0)), ready = true,
        )
    }
}
