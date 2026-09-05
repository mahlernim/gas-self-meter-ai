package dev.mahlernim.gasselfmeter

/** Display values are distinct from the opaque keys used to match accounts and meters. */
internal fun householdInfo(data: AppData): List<Pair<String, String>> {
    if (data.profile.meter == "demo") return listOf(
        "계약자번호" to "0000000000",
        "계량기번호" to "DEMO-000000",
        "공급 주소" to "부산광역시 예시로 123, 101동 1001호",
    )
    val provider = Providers.get(data.profile.providerId)
    val target = data.cachedSelfRead?.takeIf {
        provider.skens && SkensClient.contractKey(provider, it.contract) == data.profile.contract
    }
    val account = (data.gasappConnection?.account ?: data.cachedGasappTarget?.account)?.takeIf {
        provider.gasapp && GasappApi.companyProviders[it.company] == provider.id && it.key == data.profile.contract
    }
    return buildList {
        fun addValue(label: String, value: String?) {
            value?.trim()?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
                ?.let { add(label to it) }
        }
        if (account != null) {
            addValue("고객번호", account.customer)
            if (account.contract != account.customer) addValue("사용계약번호", account.contract)
        } else {
            addValue(if (provider.skens) "계약자번호" else "고객번호", target?.contract?.ca ?: data.profile.customerNumber)
        }
        // A cached target can outlive a local meter replacement. Never show the old serial as current.
        if (target != null && target.serial.isNotBlank() && SkensClient.opaque(target.serial) == data.profile.meter) {
            addValue("계량기번호", target.serial)
        }
        addValue("공급 주소", target?.address)
    }
}
