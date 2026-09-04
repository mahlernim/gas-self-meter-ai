package dev.mahlernim.gasselfmeter

/** Compare the account snapshot after I/O before applying results to local state. */
internal object BackgroundState {
    fun sameAccount(latest: AppData, expected: AppData): Boolean = latest.ready &&
        latest.profile.providerId == expected.profile.providerId && latest.profile.contract == expected.profile.contract &&
        latest.profile.meter == expected.profile.meter && latest.credentials == expected.credentials &&
        sameGasapp(latest.gasappConnection, expected.gasappConnection)

    private fun sameGasapp(a: GasappConnection?, b: GasappConnection?): Boolean {
        if (a == null || b == null) return a == null && b == null
        return a.session.token == b.session.token && a.session.member == b.session.member &&
            a.session.deviceId == b.session.deviceId && a.account.company == b.account.company &&
            a.account.customer == b.account.customer && a.account.contract == b.account.contract
    }

    fun finish(latest: AppData, expected: AppData, record: SubmissionRecord): AppData {
        if (!sameAccount(latest, expected)) return latest
        val pending = latest.submissions.lastOrNull { it.cycle == record.cycle }
        if (pending?.status != "pending" || pending.attemptedAt != record.attemptedAt || pending.value != record.value) return latest
        return latest.copy(submissions = (latest.submissions.filterNot { it.cycle == record.cycle } + record).takeLast(100))
    }
}

