package dev.mahlernim.gasselfmeter

/**
 * Converts a locally uncertain SK E&S submission into confirmed only when the
 * freshly read portal state identifies the same account, meter, period and value.
 */
object SkensReconciliation {
    private const val CONFIRMED_DETAIL = "공급사에서 제출 완료를 확인했어요."

    fun reconciledRecord(data: AppData, target: SelfReadTarget?): SubmissionRecord? {
        val current = target ?: return null
        // A freshly read target alone is not enough to identify an old submission. The target
        // captured before its one-shot transmission binds the supplier's opaque cycle metadata.
        val expected = data.cachedSelfRead ?: return null
        val provider = Providers.get(data.profile.providerId)
        if (!provider.skens || data.profile.contract.isBlank() || data.profile.meter.isBlank()) return null
        if (SkensClient.contractKey(provider, current.contract) != data.profile.contract) return null
        if (current.serial.isBlank() || SkensClient.opaque(current.serial) != data.profile.meter) return null
        if (!current.submitted || current.submittedValue == null || !current.submittedValue.isFinite()) return null

        val record = data.submissions.lastOrNull { record ->
            record.status in setOf("pending", "uncertain", "confirmed") &&
                record.confirmationSource != "readback" &&
                record.cycle == expected.cycle &&
                record.periodStart == expected.start &&
                record.periodEnd == expected.end
        } ?: return null

        // Includes contract, serial, cycle, dates, planned date, and installation checks.
        if (!SkensClient.confirmsSubmission(expected, current, record.value)) return null
        // Historical records can predate integer-only input. Do not coerce either side.
        return record.takeIf { it.value == current.submittedValue }
    }

    /** Preserves every other row, including records written by concurrent background work. */
    fun apply(data: AppData, target: SelfReadTarget?): AppData {
        val record = reconciledRecord(data, target) ?: return data
        return data.copy(submissions = data.submissions.map { current ->
            if (current == record && current.status in setOf("pending", "uncertain", "confirmed")) {
                current.copy(status = "confirmed", detail = CONFIRMED_DETAIL, confirmationSource = "readback")
            } else current
        })
    }
}
