package com.example.opendash.dash.nav

import org.junit.Assert.assertEquals
import org.junit.Test

class OfflineRouteSelectorTest {
    @Test
    fun choose_prefersTheDownloadedAlternativeAlignedWithRiderHeading() {
        val north = route(GeoPoint(0.0, 0.0), GeoPoint(0.01, 0.0))
        val east = route(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.01))

        assertEquals(east, OfflineRouteSelector.choose(listOf(north, east), GeoPoint(0.0, 0.0), 90f))
    }

    @Test
    fun choose_refusesRoutesTooFarFromTheRider() {
        val route = route(GeoPoint(0.0, 0.0), GeoPoint(0.01, 0.0))

        assertEquals(null, OfflineRouteSelector.choose(listOf(route), GeoPoint(0.03, 0.03), 0f))
    }

    private fun route(vararg points: GeoPoint) = Route(
        geometry = points.toList(),
        maneuvers = emptyList(),
        totalMeters = 0.0,
        totalSeconds = 0.0,
        cumulative = DoubleArray(points.size),
    )
}
