package dev.mahlernim.gasselfmeter

import org.junit.Assert.*
import org.junit.Test

class ProviderCatalogTest {
    // Company names and mapping checked against the public KOGAS directory on 2026-09-04.
    // https://www.kogas.or.kr/site/koGas/1020408040000
    private val officialCompanies = mapOf(
        "코원에너지서비스" to "koone", "예스코" to "yesco", "서울도시가스" to "seoul",
        "귀뚜라미에너지" to "kiturami", "대륜이엔에스" to "daeryun", "삼천리" to "samchully",
        "인천도시가스" to "incheon", "강원도시가스" to "gangwon",
        "참빛원주도시가스" to "chambit", "참빛영동도시가스" to "chambit",
        "참빛도시가스" to "chambit", "참빛충북도시가스" to "chambit",
        "명성파워그린" to "myungsung", "CNCITY에너지" to "cncity", "충청에너지서비스" to "cheongju",
        "JB" to "jb", "미래엔서해에너지" to "seohae", "대성에너지" to "daesung",
        "대성청정에너지" to "daesungclean", "영남에너지서비스 구미" to "gumi",
        "영남에너지서비스 포항" to "pohang", "서라벌도시가스" to "seorabeol",
        "부산도시가스" to "busan", "경동도시가스" to "kyungdong", "경남에너지" to "knenergy",
        "지에스이" to "gse", "전북도시가스" to "jeonbukgas", "군산도시가스" to "gunsan",
        "전북에너지서비스" to "jeonbuk", "해양에너지" to "haeyang", "MC에너지" to "mcenergy",
        "전남도시가스" to "jeonnam", "대화도시가스" to "daehwa", "제주도시가스" to "jeju",
    )

    @Test fun catalogCoversEveryOfficialCompanyWithoutLosingStableIdsOrFallback() {
        val ids = Providers.all.map { it.id }
        assertEquals(34, officialCompanies.size)
        assertEquals(31, ids.count { it != "other" })
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(officialCompanies.values.toSet(), ids.toSet() - "other")
        assertEquals(4, officialCompanies.values.count { it == "chambit" })
        assertEquals("other", Providers.all.last().id)
        assertEquals("other", Providers.get("unknown-provider").id)
    }

    @Test fun catalogEntriesKeepTheirRegionsAndExplicitConnectionCapabilities() {
        val expected = mapOf(
            "daesungclean" to listOf("대구", "경북"), "knenergy" to listOf("경남"),
            "seorabeol" to listOf("경북"), "gse" to listOf("경남"), "myungsung" to listOf("강원"),
        )
        expected.forEach { (id, regions) ->
            val provider = Providers.get(id)
            assertEquals(id, provider.id)
            assertEquals(regions, provider.regions)
            assertEquals(id == "daesungclean", provider.automatic)
            assertFalse(provider.automaticSubmission)
            assertFalse(provider.gasapp)
            assertEquals(id == "daesungclean", provider.passwordConnection)
            assertNull(provider.skensCode)
        }
        assertEquals(listOf("충북", "세종"), Providers.get("cheongju").regions)
    }

    @Test fun linksIdentifySuppliersRatherThanTheSharedAppAndKeepSafeDirectoryFallbacks() {
        val expected = mapOf(
            "daesungclean" to "https://www.daesungcleanenergy.co.kr/",
            "knenergy" to "https://www.knenergy.co.kr/",
            "seorabeol" to "https://www.srbgas.co.kr/",
            "gse" to "https://www.yesgse.com/",
            "mcenergy" to "https://www.mokpocitygas.co.kr/",
            "seohae" to "https://www.shgas.co.kr/",
            "daehwa" to "https://www.dhgas.com/",
            "chambit" to "https://www.kogas.or.kr/site/koGas/1020408040000",
            "myungsung" to "https://www.kogas.or.kr/site/koGas/1020408040000",
        )
        expected.forEach { (id, website) -> assertEquals(website, Providers.get(id).website) }
        assertTrue(Providers.get("mcenergy").name.contains("MC에너지"))
        assertTrue(Providers.get("mcenergy").name.contains("목포도시가스"))
        assertEquals(listOf("강원", "충북"), Providers.get("chambit").regions)
        assertEquals("공급사 안내", Providers.get("chambit").websiteLabel)
        assertEquals("공급사 안내", Providers.get("myungsung").websiteLabel)
        assertEquals("공급사 홈페이지", Providers.get("mcenergy").websiteLabel)
    }

    @Test fun catalogExpansionDoesNotChangeExistingConnectionOrSubmissionCapabilities() {
        val gasapp = setOf("seoul", "yesco", "incheon", "daeryun", "kiturami", "jb", "jeonbukgas",
            "gunsan", "jeju", "kyungdong", "chambit", "mcenergy", "seohae", "daehwa")
        val skens = setOf("busan", "koone", "cheongju", "gumi", "pohang", "jeonnam", "gangwon", "jeonbuk")
        assertEquals(gasapp, Providers.all.filter { it.gasapp }.map { it.id }.toSet())
        assertEquals(skens + gasapp + setOf("samchully", "daesung", "daesungclean", "haeyang"), Providers.all.filter { it.automatic }.map { it.id }.toSet())
        assertEquals(gasapp + "busan", Providers.all.filter { it.automaticSubmission }.map { it.id }.toSet())
        assertEquals(skens + setOf("samchully", "daesung", "daesungclean", "haeyang"), Providers.all.filter { it.passwordConnection }.map { it.id }.toSet())
    }
}
