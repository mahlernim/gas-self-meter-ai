package dev.mahlernim.gasselfmeter

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class NotificationRoutingFlowTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @After fun clearStore() { SecureStore(context).erase(); Diagnostics.clear(context) }

    @Test fun notificationDestinationIsOneShotAcrossRecreateAndWarmIntent() {
        SecureStore(context).write(AppData(
            profile = Profile(providerId = "busan", meter = "synthetic-meter", syncTime = System.currentTimeMillis()),
            ready = true,
        ))
        val cold = Intent(context, MainActivity::class.java).putExtra(AppTabs.EXTRA, AppTabs.SUBMISSION)
        ActivityScenario.launch<MainActivity>(cold).use { scenario ->
            compose.awaitStorage(scenario)
            compose.onNodeWithText("공급사 연결이 필요해요").assertIsDisplayed()

            compose.onNodeWithText("추이", useUnmergedTree = true).performClick()
            compose.onNodeWithText("사용 추이").assertIsDisplayed()
            scenario.recreate()
            compose.awaitStorage(scenario)
            compose.onNodeWithText("사용 추이").assertIsDisplayed()

            scenario.onActivity { activity ->
                activity.startActivity(Intent(activity, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra(AppTabs.EXTRA, AppTabs.SUBMISSION))
            }
            compose.onNodeWithText("공급사 연결이 필요해요").assertIsDisplayed()
            compose.onNodeWithText("설정", useUnmergedTree = true).performClick()
            compose.onNodeWithText("앱 설정").assertIsDisplayed()
        }
        assertTrue(SecureStore(context).read().submissions.isEmpty())
    }
}
