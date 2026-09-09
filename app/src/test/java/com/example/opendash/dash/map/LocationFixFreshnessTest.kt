package com.example.opendash.dash.map

import org.junit.Assert.assertEquals
import org.junit.Test

class LocationFixFreshnessTest {
    @Test
    fun ageMs_returnsMaximumWhenNoFixHasBeenAccepted() {
        assertEquals(Long.MAX_VALUE, LocationFixFreshness.ageMs(null, nowElapsedRealtimeMs = 10_000L))
    }

    @Test
    fun ageMs_usesMonotonicElapsedTime() {
        assertEquals(2_500L, LocationFixFreshness.ageMs(fixElapsedRealtimeMs = 7_500L, nowElapsedRealtimeMs = 10_000L))
    }

    @Test
    fun ageMs_neverReportsNegativeAgeWhenClockValuesAreOutOfOrder() {
        assertEquals(0L, LocationFixFreshness.ageMs(fixElapsedRealtimeMs = 12_000L, nowElapsedRealtimeMs = 10_000L))
    }
}
