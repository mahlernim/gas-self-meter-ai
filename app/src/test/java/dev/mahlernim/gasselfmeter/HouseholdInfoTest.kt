package dev.mahlernim.gasselfmeter

import org.junit.Assert.*
import org.junit.Test

class HouseholdInfoTest {
    private val contract = Contract("synthetic-bp", "0000123456")
    private val serial = "SYNTHETIC-001"
    private val target = SelfReadTarget("synthetic-cycle", "2026-09-01", "2026-09-07", false, false,
        null, 100.0, contract, serial, "예시로 123, 101동 1001호", "20260907", "", "")
    private fun data() = AppData(profile = Profile(providerId = "busan", meter = SkensClient.opaque(serial),
        contract = SkensClient.contractKey(Providers.get("busan"), contract), customerNumber = contract.ca),
        cachedSelfRead = target, ready = true)

    @Test fun replacementOrUnrelatedCacheDoesNotExposeAnOldMeterOrAddress() {
        val state = data()
        assertEquals(serial, householdInfo(state).toMap()["계량기번호"])
        val replacement = householdInfo(state.copy(profile = state.profile.copy(meter = "manual-new"))).toMap()
        assertFalse(replacement.containsKey("계량기번호"))
        assertEquals(target.address, replacement["공급 주소"])
        val otherContract = householdInfo(state.copy(cachedSelfRead = target.copy(contract = Contract("other", "other")))).toMap()
        assertEquals(mapOf("계약자번호" to contract.ca), otherContract)
        val otherProvider = householdInfo(state.copy(profile = state.profile.copy(providerId = "koone"))).toMap()
        assertFalse(otherProvider.containsKey("공급 주소"))
        assertFalse(otherProvider.containsKey("계량기번호"))
    }

    @Test fun portableBackupKeepsCustomerNumberWithoutPresentingOpaqueKeysAsSerials() {
        val restored = DataCodec.decode(DataCodec.encode(data()))
        assertEquals(listOf("계약자번호" to contract.ca), householdInfo(restored))
        assertTrue(householdInfo(AppData()).isEmpty())
        assertFalse(householdInfo(data()).any { it.second == data().profile.meter || it.second == data().profile.contract })
        assertFalse(householdInfo(data().copy(cachedSelfRead = target.copy(address = " NULL "))).toMap().containsKey("공급 주소"))
    }

    @Test fun gasappUsesTheMatchingAccountAndPreservesSeparateCustomerAndContractNumbers() {
        val account = GasappAccount("1", "00001234", "00005678", "synthetic", "N")
        val cached = GasappTarget(account, true, false, null, null, "opaque-meter", null, null, false, false, false, null)
        val state = AppData(profile = Profile(providerId = "seoul", contract = account.key, customerNumber = account.customer),
            cachedGasappTarget = cached, ready = true)
        assertEquals(listOf("고객번호" to account.customer, "사용계약번호" to account.contract), householdInfo(state))
        assertFalse(householdInfo(state.copy(profile = state.profile.copy(providerId = "yesco"))).toMap().containsKey("사용계약번호"))
        assertFalse(householdInfo(state.copy(profile = state.profile.copy(contract = "different"))).toMap().containsKey("사용계약번호"))
    }
}
