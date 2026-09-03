package dev.mahlernim.gasselfmeter

data class Provider(val id: String, val name: String, val regions: List<String>, val website: String, val automatic: Boolean = false, val gasapp: Boolean = false)
object Providers {
    val all = listOf(
        Provider("busan", "부산도시가스", listOf("부산"), "https://www.skens.com/busan/login/login.do", true),
        Provider("seoul", "서울도시가스", listOf("서울", "경기"), "https://www.seoulgas.co.kr/", gasapp = true),
        Provider("yesco", "예스코", listOf("서울", "경기"), "https://www.lsyesco.com/", gasapp = true),
        Provider("samchully", "삼천리", listOf("경기", "인천"), "https://www.samchully.co.kr/"),
        Provider("incheon", "인천도시가스", listOf("인천", "경기"), "https://icgas.co.kr:8443/", gasapp = true),
        Provider("daeryun", "대륜E&S", listOf("서울", "경기"), "https://www.daeryunens.com/", gasapp = true),
        Provider("kiturami", "귀뚜라미에너지", listOf("서울"), "https://www.kituramienergy.co.kr/", gasapp = true),
        Provider("koone", "코원에너지서비스", listOf("서울", "경기"), "https://www.skens.com/koone/login/login.do"),
        Provider("cheongju", "충청에너지서비스", listOf("충북"), "https://www.skens.com/cheongju/login/login.do"),
        Provider("gumi", "영남에너지서비스 구미", listOf("경북"), "https://www.skens.com/gumi/login/login.do"),
        Provider("pohang", "영남에너지서비스 포항", listOf("경북"), "https://www.skens.com/pohang/login/login.do"),
        Provider("jeonnam", "전남도시가스", listOf("전남"), "https://www.skens.com/jeonnam/login/login.do"),
        Provider("gangwon", "강원도시가스", listOf("강원"), "https://www.skens.com/gangwon/login/login.do"),
        Provider("jeonbuk", "전북에너지서비스", listOf("전북"), "https://www.skens.com/jeonbuk/login/login.do"),
        Provider("jb", "JB", listOf("충남", "세종"), "https://www.jbcorporation.com/", gasapp = true),
        Provider("jeonbukgas", "전북도시가스", listOf("전북"), "https://www.jbcitygas.co.kr/", gasapp = true),
        Provider("gunsan", "군산도시가스", listOf("전북"), "https://www.kscg.co.kr/", gasapp = true),
        Provider("jeju", "제주도시가스", listOf("제주"), "https://www.jejucitygas.com/", gasapp = true),
        Provider("kyungdong", "경동도시가스", listOf("울산", "경남"), "https://www.kdgas.co.kr/", gasapp = true),
        Provider("cncity", "CNCITY에너지", listOf("대전", "충남"), "https://www.cncityenergy.com/"),
        Provider("daesung", "대성에너지", listOf("대구", "경북"), "https://www.daesungenergy.com/"),
        Provider("haeyang", "해양에너지", listOf("광주", "전남"), "https://www.hyenergy.co.kr/"),
        Provider("other", "다른 공급사 / 직접 입력", listOf("전국"), "https://www.kogas.or.kr/site/koGas/1020408040000"),
    )
    val regions = listOf("부산", "서울", "경기", "인천", "울산", "경남", "대구", "경북", "대전", "세종", "충남", "충북", "광주", "전남", "전북", "강원", "제주")
    fun get(id: String) = all.firstOrNull { it.id == id } ?: all.last()
}
