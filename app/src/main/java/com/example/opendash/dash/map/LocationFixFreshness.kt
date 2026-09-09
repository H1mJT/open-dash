package com.example.opendash.dash.map

/** Monotonic age calculation for a location fix. Kept pure for deterministic tests. */
internal object LocationFixFreshness {
    fun ageMs(fixElapsedRealtimeMs: Long?, nowElapsedRealtimeMs: Long): Long {
        val fixTime = fixElapsedRealtimeMs ?: return Long.MAX_VALUE
        return (nowElapsedRealtimeMs - fixTime).coerceAtLeast(0L)
    }
}
