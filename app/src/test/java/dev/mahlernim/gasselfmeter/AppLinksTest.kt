package dev.mahlernim.gasselfmeter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLinksTest {
    @Test fun updateLinkTargetsThisAppOnGooglePlay() {
        assertEquals(
            "market://details?id=dev.mahlernim.gasselfmeter",
            AppLinks.PLAY_STORE,
        )
        assertEquals(
            "https://play.google.com/apps/testing/dev.mahlernim.gasselfmeter",
            AppLinks.TESTING_PAGE,
        )
    }

    @Test fun publicLinksUseSecurePurposeSpecificDestinations() {
        val links = listOf(AppLinks.TESTER_GROUP, AppLinks.PRIVACY, AppLinks.SOURCE, AppLinks.ISSUES)
        assertTrue(links.all { it.startsWith("https://") })
        assertEquals(links.size, links.distinct().size)
        assertTrue(AppLinks.PRIVACY.endsWith("/PRIVACY.md"))
        assertTrue(AppLinks.ISSUES.endsWith("/issues"))
    }
}
