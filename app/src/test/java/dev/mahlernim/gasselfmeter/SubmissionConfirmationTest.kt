package dev.mahlernim.gasselfmeter

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubmissionConfirmationTest {
    private fun record(source: String? = null) = SubmissionRecord(
        "cycle", "2026-09-01", "2026-09-05", 41.0, 1L, "confirmed", "synthetic", source,
    )

    @Test fun providerResponseIsConfirmedAndBlocksRetryWithoutClaimingReadback() {
        val outcome = SubmissionOutcome(accepted = true, confirmed = false)

        assertEquals("confirmed", outcome.status)
        assertEquals("provider_response", outcome.confirmationSource)
        val restored = DataCodec.decode(DataCodec.encode(AppData(submissions = listOf(record(outcome.confirmationSource))), true), true)
        assertEquals("provider_response", restored.submissions.single().confirmationSource)
        assertEquals("confirmed", restored.submissions.single().status)
    }

    @Test fun matchingReadbackRetainsItsStrongerConfirmationSource() {
        val outcome = SubmissionOutcome(accepted = true, confirmed = true)

        assertEquals("confirmed", outcome.status)
        assertEquals("readback", outcome.confirmationSource)
    }

    @Test fun oldRecordsWithoutSourceRemainUnlabeled() {
        val encoded = JSONObject(DataCodec.encode(AppData(submissions = listOf(record("provider_response"))), true))
        encoded.getJSONArray("submissions").getJSONObject(0).remove("confirmationSource")

        assertNull(DataCodec.decode(encoded.toString(), true).submissions.single().confirmationSource)
    }
}
