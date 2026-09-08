package dev.mahlernim.gasselfmeter

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class DirectSubmissionRangeTest {
    private val date = LocalDate.of(2026, 9, 8)
    private val time = dayStart(date) + 10 * 3_600_000L
    private fun data(id: String, reading: Double): Pair<AppData, SelfReadTarget> {
        val meter = DirectIdentity.meter(id, "contract", "meter")
        val target = SelfReadTarget("cycle", date.minusDays(2).toString(), date.toString(), true, false, null, 90.0,
            Contract("contract", id), "meter", "", "", "", meter)
        return AppData(profile = Profile(id, meter, DirectIdentity.contract(id, "contract"), customerNumber = "contract"),
            ready = true, credentials = Credentials("synthetic", "synthetic"),
            submissionSettings = SubmissionSettings(automatic = true),
            observations = listOf(Observation(time - 7 * 86_400_000L, reading, meter), Observation(time, reading, meter))) to target
    }

    @Test fun daesungClientsRejectSixDigitReadingsInBothModes() {
        for (id in listOf("daesung", "daesungclean")) for (automatic in listOf(false, true)) {
            val (valid, target) = data(id, 99_999.0)
            assertTrue(id, DirectSubmissionPolicy.decide(valid, target, time, automatic).allowed)
            val (overflow, _) = data(id, 100_000.0)
            assertFalse(id, DirectSubmissionPolicy.decide(overflow, target, time, automatic).allowed)
            assertFalse(id, DirectSubmissionPolicy.decide(valid, target.copy(previousValue = 100_000.0), time, automatic).allowed)
        }
    }

    @Test fun haeyangKeepsItsExistingLargerNumericRange() {
        for (automatic in listOf(false, true)) {
            val (current, target) = data("haeyang", 100_000.0)
            assertTrue(DirectSubmissionPolicy.decide(current, target, time, automatic).allowed)
        }
    }
}
