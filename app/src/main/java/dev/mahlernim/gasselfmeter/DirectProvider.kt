package dev.mahlernim.gasselfmeter

/** Common app boundary for the independent password-based supplier adapters. */
data class DirectContract(val id: String, val label: String, val meterId: String? = null)
data class DirectBill(
    val month: String, val usage: Double? = null, val amount: Double? = null,
    val start: String? = null, val end: String? = null,
    val previous: Double? = null, val current: Double? = null, val meterId: String? = null,
)
data class DirectSnapshot(val contract: DirectContract, val bills: List<DirectBill>, val target: SelfReadTarget?)

interface DirectProviderClient : AutoCloseable {
    fun login(): List<DirectContract>
    fun read(contract: DirectContract): DirectSnapshot
    /** Recheck the exact target and send at most once. A response alone is not confirmation. */
    fun submit(contract: DirectContract, target: SelfReadTarget, value: Double)
}

object DirectIdentity {
    fun contract(providerId: String, id: String) = SkensClient.opaque("$providerId:contract:$id")
    fun meter(providerId: String, id: String, meterId: String?) = SkensClient.opaque("$providerId:$id:meter:${meterId ?: "unavailable"}")
}
