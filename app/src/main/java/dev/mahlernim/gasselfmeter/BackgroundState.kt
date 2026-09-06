package dev.mahlernim.gasselfmeter

/** Compare the account snapshot after I/O before applying results to local state. */
internal object BackgroundState {
    fun sameAccount(latest: AppData, expected: AppData): Boolean = latest.ready &&
        latest.profile.providerId == expected.profile.providerId && latest.profile.contract == expected.profile.contract &&
        latest.profile.meter == expected.profile.meter && latest.credentials == expected.credentials &&
        sameGasapp(latest.gasappConnection, expected.gasappConnection) &&
        latest.energyTalkConnection == expected.energyTalkConnection

    private fun sameGasapp(a: GasappConnection?, b: GasappConnection?): Boolean {
        if (a == null || b == null) return a == null && b == null
        return a.session.token == b.session.token && a.session.member == b.session.member &&
            a.session.deviceId == b.session.deviceId && a.account.company == b.account.company &&
            a.account.customer == b.account.customer && a.account.contract == b.account.contract
    }

    /**
     * A supplier rejecting the stored credentials is permanent until the user reconnects. Retrying
     * it replays the same password on every backoff and every later period, so these are separated
     * from transient network, timeout and server failures, which stay retryable.
     */
    fun rejectedCredentials(error: Throwable): Boolean = when (error) {
        is GasappAuthExpired -> true
        is ProviderFailure -> error.category == "authentication"
        else -> false
    }

    /** Record the rejection so no background path logs in again before the user reconnects. */
    fun holdConnection(latest: AppData, expected: AppData): AppData =
        if (!sameAccount(latest, expected)) latest
        else latest.copy(profile = latest.profile.copy(reconnectRequired = true))

    /** A connection this device may still use in the background. */
    fun connected(data: AppData): Boolean = data.ready && !data.profile.reconnectRequired &&
        (data.credentials != null || data.gasappConnection != null || data.energyTalkConnection != null)

    fun finish(latest: AppData, expected: AppData, record: SubmissionRecord): AppData {
        if (!sameAccount(latest, expected)) return latest
        val pending = latest.submissions.lastOrNull { it.cycle == record.cycle }
        if (pending?.status != "pending" || pending.attemptedAt != record.attemptedAt || pending.value != record.value) return latest
        return latest.copy(submissions = (latest.submissions.filterNot { it.cycle == record.cycle } + record).takeLast(100))
    }
}
