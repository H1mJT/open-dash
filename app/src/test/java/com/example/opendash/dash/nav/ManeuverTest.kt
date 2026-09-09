package com.example.opendash.dash.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ManeuverTest {
    private fun maneuver(type: ManeuverType) = Maneuver(
        type = type,
        instruction = "",
        location = GeoPoint(0.0, 0.0),
        cumulativeMeters = 0.0,
    )

    @Test
    fun `depart and continue are omitted from the meaningful maneuver preview`() {
        assertEquals(false, maneuver(ManeuverType.DEPART).isMeaningful)
        assertEquals(false, maneuver(ManeuverType.CONTINUE).isMeaningful)
        assertEquals(true, maneuver(ManeuverType.TURN_LEFT).isMeaningful)
        assertEquals(true, maneuver(ManeuverType.ARRIVE).isMeaningful)
    }

    @Test
    fun `directional maneuvers use their own dashboard glyphs`() {
        assertEquals(0x06, maneuver(ManeuverType.TURN_LEFT).dashCode)
        assertEquals(0x02, maneuver(ManeuverType.TURN_RIGHT).dashCode)
        assertEquals(0x07, maneuver(ManeuverType.SLIGHT_LEFT).dashCode)
        assertEquals(0x01, maneuver(ManeuverType.SLIGHT_RIGHT).dashCode)
        assertEquals(0x04, maneuver(ManeuverType.UTURN).dashCode)
    }

    @Test
    fun `only roundabouts use the roundabout glyph`() {
        assertEquals(0x0B, maneuver(ManeuverType.ROUNDABOUT).dashCode)
        ManeuverType.entries
            .filterNot { it == ManeuverType.ROUNDABOUT }
            .forEach { assertNotEquals("$it must not render as a roundabout", 0x0B, maneuver(it).dashCode) }
    }
}
