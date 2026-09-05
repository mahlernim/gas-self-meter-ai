package dev.mahlernim.gasselfmeter

data class Provider(
    val id: String,
    val name: String,
    val regions: List<String>,
    val website: String,
    val automatic: Boolean = false,
    val gasapp: Boolean = false,
    val skensCode: String? = null,
    val automaticSubmission: Boolean = false,
) {
    val skens: Boolean get() = skensCode != null
    val experimentalReadOnly: Boolean get() = id == "samchully"
    val passwordConnection: Boolean get() = skens || experimentalReadOnly
    val websiteLabel: String get() = if (website == "https://www.kogas.or.kr/site/koGas/1020408040000") "공급사 안내" else "공급사 홈페이지"
    val accountRecovery: String get() = if (skens) "https://www.skens.com/$id/login/find.do" else website
    val registration: String get() = if (skens) "https://www.skens.com/$id/join/type.do" else website
}
object Providers {
    // Public company directory checked 2026-09-04. Catalog inclusion is not API verification.
    private const val directory = "https://www.kogas.or.kr/site/koGas/1020408040000"
    val all = listOf(
        Provider("busan", "부산도시가스", listOf("부산"), "https://www.skens.com/busan/login/login.do", true, skensCode = "C000", automaticSubmission = true),
        Provider("seoul", "서울도시가스", listOf("서울", "경기"), "https://www.seoulgas.co.kr/", automatic = true, gasapp = true, automaticSubmission = true),
        Provider("yesco", "예스코", listOf("서울", "경기"), "https://www.lsyesco.com/", automatic = true, gasapp = true, automaticSubmission = true),
        Provider("samchully", "삼천리", listOf("경기", "인천"), "https://cs.samchully.co.kr/", automatic = true),
        Provider("incheon", "인천도시가스", listOf("인천", "경기"), "https://icgas.co.kr:8443/", automatic = true, gasapp = true, automaticSubmission = true),
        Provider("daeryun", "대륜E&S", listOf("서울", "경기"), "https://www.daeryunens.com/", automatic = true, gasapp = true, automaticSubmission = true),
        Provider("kiturami", "귀뚜라미에너지", listOf("서울"), "https://www.kituramienergy.co.kr/", automatic = true, gasapp = true, automaticSubmission = true),
        Provider("koone", "코원에너지서비스", listOf("서울", "경기"), "https://www.skens.com/koone/login/login.do", true, skensCode = "B000"),
        Provider("cheongju", "충청에너지서비스", listOf("충북", "세종"), "https://www.skens.com/cheongju/login/login.do", true, skensCode = "D000"),
        Provider("gumi", "영남에너지서비스 구미", listOf("경북"), "https://www.skens.com/gumi/login/login.do", true, skensCode = "E000"),
        Provider("pohang", "영남에너지서비스 포항", listOf("경북"), "https://www.skens.com/pohang/login/login.do", true, skensCode = "F000"),
        Provider("jeonnam", "전남도시가스", listOf("전남"), "https://www.skens.com/jeonnam/login/login.do", true, skensCode = "G000"),
        Provider("gangwon", "강원도시가스", listOf("강원"), "https://www.skens.com/gangwon/login/login.do", true, skensCode = "J000"),
        Provider("jeonbuk", "전북에너지서비스", listOf("전북"), "https://www.skens.com/jeonbuk/login/login.do", true, skensCode = "K000"),
        Provider("jb", "JB", listOf("충남", "세종"), "https://www.jbcorporation.com/", automatic = true, gasapp = true, automaticSubmission = true),
        Provider("jeonbukgas", "전북도시가스", listOf("전북"), "https://www.jbcitygas.co.kr/", automatic = true, gasapp = true, automaticSubmission = true),
        Provider("gunsan", "군산도시가스", listOf("전북"), "https://www.kscg.co.kr/", automatic = true, gasapp = true, automaticSubmission = true),
        Provider("jeju", "제주도시가스", listOf("제주"), "https://www.jejucitygas.com/", automatic = true, gasapp = true, automaticSubmission = true),
        Provider("kyungdong", "경동도시가스", listOf("울산", "경남"), "https://www.kdgas.co.kr/", automatic = true, gasapp = true, automaticSubmission = true),
        Provider("cncity", "CNCITY에너지", listOf("대전", "충남"), "https://www.cncityenergy.com/"),
        Provider("daesung", "대성에너지", listOf("대구", "경북"), "https://www.daesungenergy.com/"),
        Provider("daesungclean", "대성청정에너지", listOf("대구", "경북"), "https://www.daesungcleanenergy.co.kr/"),
        Provider("knenergy", "경남에너지", listOf("경남"), "https://www.knenergy.co.kr/"),
        Provider("seorabeol", "서라벌도시가스", listOf("경북"), "https://www.srbgas.co.kr/"),
        Provider("gse", "지에스이", listOf("경남"), "https://www.yesgse.com/"),
        Provider("haeyang", "해양에너지", listOf("광주", "전남"), "https://www.hyenergy.co.kr/"),
        // Four Chambit companies share this existing Gasapp ID, not a single company website.
        Provider("chambit", "참빛도시가스 계열", listOf("강원", "충북"), directory, automatic = true, gasapp = true, automaticSubmission = true),
        Provider("mcenergy", "MC에너지 (목포도시가스)", listOf("전남"), "https://www.mokpocitygas.co.kr/", automatic = true, gasapp = true, automaticSubmission = true),
        Provider("seohae", "미래엔서해에너지", listOf("충남"), "https://www.shgas.co.kr/", automatic = true, gasapp = true, automaticSubmission = true),
        Provider("daehwa", "대화도시가스", listOf("전남"), "https://www.dhgas.com/", automatic = true, gasapp = true, automaticSubmission = true),
        // Use the directory until a valid HTTPS customer portal is established.
        Provider("myungsung", "명성파워그린", listOf("강원"), directory),
        Provider("other", "다른 공급사 / 직접 입력", listOf("전국"), directory),
    )
    val regions = listOf("부산", "서울", "경기", "인천", "울산", "경남", "대구", "경북", "대전", "세종", "충남", "충북", "광주", "전남", "전북", "강원", "제주")
    fun get(id: String) = all.firstOrNull { it.id == id } ?: all.last()
    fun skens(id: String): Provider = get(id).also { require(it.skens) { "지원하지 않는 자동 연결 공급사예요." } }
}
