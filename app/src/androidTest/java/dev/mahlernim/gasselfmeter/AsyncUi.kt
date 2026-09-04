package dev.mahlernim.gasselfmeter

import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario

fun ComposeTestRule.awaitStorage(scenario: ActivityScenario<MainActivity>) {
    waitUntil(10_000) {
        var idle = false
        scenario.onActivity { idle = !ViewModelProvider(it)[GasViewModel::class.java].busy }
        idle
    }
    waitForIdle()
}
