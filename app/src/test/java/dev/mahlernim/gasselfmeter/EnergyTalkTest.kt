package dev.mahlernim.gasselfmeter

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class EnergyTalkTest {
    @Test fun proxyBoundaryRejectsOriginVariants() {
        assertTrue(EnergyTalkBoundary.officialProxy("https://energytalk.ai/api/fetch"))
        listOf("http://energytalk.ai/api/fetch", "https://energytalk.ai:443/api/fetch", "https://user@energytalk.ai/api/fetch", "https://energytalk.ai.evil.test/api/fetch", "https://energytalk.ai/api/fetch?x=1", "https://energytalk.ai/api/fetch#x", "https://energytalk.ai/api/%66etch").forEach {
            assertFalse(it, EnergyTalkBoundary.officialProxy(it))
        }
    }
    @Test fun tokenBoundaryRejectsControlsWhitespaceAndOversize() {
        assertEquals("synthetic-token-12345", EnergyTalkBoundary.token("Bearer synthetic-token-12345"))
        listOf(null, "Basic synthetic-token-12345", "Bearer short", "Bearer synthetic token-12345", "Bearer synthetic-token-12345\r\n", "Bearer " + "x".repeat(8193)).forEach {
            assertNull(EnergyTalkBoundary.token(it))
        }
    }
    @Test fun navigationDoesNotLaunchArbitraryIntents() {
        assertTrue(EnergyTalkBoundary.navigationAllowed("https://accounts.kakao.com/login"))
        assertFalse(EnergyTalkBoundary.navigationAllowed("intent://anything"))
        assertFalse(EnergyTalkBoundary.navigationAllowed("https://kakao.com.evil.test/"))
        assertFalse(EnergyTalkBoundary.navigationAllowed("file:///tmp/test"))
    }
    @Test fun usageIsDisplayOnlyAndSortedWithoutConvertingUnits() {
        val rows = EnergyTalkReadClient.parseUsage(JSONObject("""{"list":[{"dateVal":"202601","amount":"12000","usageVal":"10 MJ"},{"dateVal":"202602","amount":"11000","usageVal":"8 m³"}]}"""))
        assertEquals(listOf("202602", "202601"), rows.map { it.month })
        assertEquals("10 MJ", rows.last().usage)
    }
    @Test(expected = IllegalArgumentException::class) fun invalidMonthFailsClosed() {
        EnergyTalkReadClient.parseUsage(JSONObject("""{"list":[{"dateVal":"202613","amount":"0","usageVal":"0"}]}"""))
    }
    @Test(expected = IllegalArgumentException::class) fun duplicateMonthsFailClosed() {
        EnergyTalkReadClient.parseUsage(JSONObject("""{"list":[{"dateVal":"202601","amount":"0","usageVal":"0"},{"dateVal":"202601","amount":"0","usageVal":"0"}]}"""))
    }
    @Test fun selfMeterOnlyExposesReferenceFields() {
        val meter = EnergyTalkReadClient.parseMeter(JSONObject("""{"checkYn":"Y","prevGuideline":"123.4","recentGuideLine":null,"checkMsg":"확인"}"""))
        assertTrue(meter.eligible)
        assertEquals("123.4", meter.previous)
        assertNull(meter.recent)
    }
    @Test(expected = IllegalArgumentException::class) fun unknownMeterStateFailsClosed() {
        EnergyTalkReadClient.parseMeter(JSONObject("""{"checkYn":"UNKNOWN"}"""))
    }
}
