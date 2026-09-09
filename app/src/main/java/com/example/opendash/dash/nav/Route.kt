package com.example.opendash.dash.nav

/** Maneuver glyphs understood by the dash navigation widget. */
enum class ManeuverType { CONTINUE, TURN_LEFT, TURN_RIGHT, SLIGHT_LEFT, SLIGHT_RIGHT,
    SHARP_LEFT, SHARP_RIGHT, UTURN, ROUNDABOUT, DEPART, ARRIVE;

    companion object {
        /** Map an OSRM step maneuver (type + modifier) to our enum. */
        fun fromOsrm(type: String?, modifier: String?): ManeuverType = when (type) {
            "depart"   -> DEPART
            "arrive"   -> ARRIVE
            "roundabout", "rotary" -> ROUNDABOUT
            "fork", "end of road", "turn", "new name", "continue", "merge", "on ramp", "off ramp" ->
                when (modifier) {
                    "left"         -> TURN_LEFT
                    "right"        -> TURN_RIGHT
                    "slight left"  -> SLIGHT_LEFT
                    "slight right" -> SLIGHT_RIGHT
                    "sharp left"   -> SHARP_LEFT
                    "sharp right"  -> SHARP_RIGHT
                    "uturn"        -> UTURN
                    else           -> CONTINUE
                }
            else -> CONTINUE
        }
    }
}

/** One routing instruction located at a point along the geometry. */
data class Maneuver(
    val type: ManeuverType,
    val instruction: String,
    val location: GeoPoint,
    /** Cumulative distance (m) from the route start to this maneuver's location. */
    val cumulativeMeters: Double,
) {
    /**
     * Dash maneuver glyph byte. 0x0B is the roundabout glyph, so it must never be
     * used as the generic fallback: doing so makes every upcoming instruction look
     * like a roundabout exit. The other codes are the directional glyph family used
     * by the dash's navigation widget.
     */
    val dashCode: Int get() = when (type) {
        ManeuverType.CONTINUE, ManeuverType.DEPART, ManeuverType.ARRIVE -> 0x00
        ManeuverType.SLIGHT_RIGHT -> 0x01
        ManeuverType.TURN_RIGHT   -> 0x02
        ManeuverType.SHARP_RIGHT  -> 0x03
        ManeuverType.UTURN        -> 0x04
        ManeuverType.SHARP_LEFT   -> 0x05
        ManeuverType.TURN_LEFT    -> 0x06
        ManeuverType.SLIGHT_LEFT  -> 0x07
        ManeuverType.ROUNDABOUT   -> 0x0B
    }
}

/** A computed road route from origin to destination. */
data class Route(
    val geometry: List<GeoPoint>,
    val maneuvers: List<Maneuver>,
    val totalMeters: Double,
    val totalSeconds: Double,
    /** Cumulative distance (m) at each geometry vertex — same length as [geometry]. */
    val cumulative: DoubleArray,
) {
    val destination: GeoPoint? get() = geometry.lastOrNull()
}
