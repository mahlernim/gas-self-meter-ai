package dev.mahlernim.gasselfmeter

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ProviderExpansionFlowTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @After fun clearStore() { SecureStore(context).erase(); Diagnostics.clear(context) }

    @Test fun cncityWelcomeRouteUsesItsEnergyTalkTenant() {
        SecureStore(context).erase()
        assertEquals(listOf("cncity"), Providers.get("cncity").energyTalkTenants)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.awaitStorage(scenario)
            compose.onNodeWithText("부산").performScrollTo().performClick()
            compose.onNodeWithText("대전").performClick()
            compose.onNodeWithText("공급사를 선택해 주세요").performScrollTo().performClick()
            compose.onNodeWithText("CNCITY에너지").performClick()
            compose.onNodeWithText("CNCITY에너지 연결하기").performScrollTo().performClick()
            compose.onNodeWithText("에너지톡 실험적 조회").assertIsDisplayed()
            compose.onNodeWithText("공식 에너지톡·카카오 화면에서 직접 로그인", substring = true).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("취소").performScrollTo().performClick()
            compose.onNodeWithText("CNCITY에너지 연결하기").assertIsDisplayed()
        }
    }

    @Test fun firstSameDaySamchullyReadingEnablesDirectSubmission() {
        SecureStore(context).write(samchullyData())
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.awaitStorage(scenario)
            compose.onNodeWithText("제출", useUnmergedTree = true).performClick()
            compose.onNodeWithText("자가검침 제출").assertIsDisplayed()
            compose.onNodeWithText("입력 예정 111 m³").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("111 m³ 직접 제출").performScrollTo().assertIsEnabled()
            compose.onNodeWithText("이 공급사는 직접 제출만 지원해요.").performScrollTo().assertIsDisplayed()
        }
    }

    @Test fun connectedEnergyTalkBillsAppearInUsageHistory() {
        SecureStore(context).write(AppData(
            profile = Profile(providerId = "cncity", meter = "energy-meter", syncTime = System.currentTimeMillis()),
            ready = true,
            energyTalkBills = listOf(EnergyTalkBill("202608", "12 m³", "14000원", "m³")),
        ))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.awaitStorage(scenario)
            compose.onNodeWithText("추이", useUnmergedTree = true).performClick()
            compose.onNodeWithText("2026.08 청구").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("사용량 12 m³").assertIsDisplayed()
            compose.onNodeWithText("청구금액 14000원").assertIsDisplayed()
        }
    }

    private fun samchullyData(): AppData {
        val customer = "1000000"
        val meter = "synthetic-samchully-meter"
        val now = System.currentTimeMillis()
        val target = SelfReadTarget(
            cycle = "synthetic-cycle",
            start = today().toString(),
            end = today().plusDays(1).toString(),
            eligible = true,
            submitted = false,
            submittedValue = null,
            previousValue = 100.0,
            contract = Contract(customer, "samchully", "합성 계약"),
            serial = "synthetic-target",
            address = "",
            planned = "",
            vLdo = "",
            installation = meter,
        )
        return AppData(
            profile = Profile(providerId = "samchully", meter = meter,
                contract = SkensClient.opaque("samchully:$customer"), customerNumber = customer,
                syncTime = now),
            observations = listOf(Observation(now, 111.8, meter)),
            credentials = Credentials("synthetic-user", "synthetic-password"),
            submissionSettings = SubmissionSettings(automatic = false, reminder = false),
            cachedSelfRead = target,
            ready = true,
        )
    }
}
