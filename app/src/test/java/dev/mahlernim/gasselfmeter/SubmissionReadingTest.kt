package dev.mahlernim.gasselfmeter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SubmissionReadingTest {
    @Test fun floorsFractionalValuesAndEncodesIntegerWithoutDecimalSuffix() {
        assertEquals(123.0, SubmissionReading.floor(123.999), 0.0)
        assertEquals("123", SubmissionReading.wire(123.0))
        assertEquals("0", SubmissionReading.wire(0.0))
    }

    @Test fun wireRejectsFractionalAndOutOfRangeValues() {
        for (value in listOf(-0.001, 123.001, Double.NaN, Double.POSITIVE_INFINITY, 100_000.0)) {
            assertThrows(IllegalArgumentException::class.java) { SubmissionReading.wire(value, 99_999.0) }
        }
    }
}
