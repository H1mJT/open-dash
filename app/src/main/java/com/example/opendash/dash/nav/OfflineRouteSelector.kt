package com.example.opendash.dash.nav

/** Chooses a downloaded route that the rider is actually travelling along. */
internal object OfflineRouteSelector {
    private const val MAX_REJOIN_DISTANCE_M = 1_000.0

    fun choose(routes: List<Route>, position: GeoPoint, bearing: Float?): Route? =
        routes.mapNotNull { route ->
            val closest = route.geometry.indices.minByOrNull { GeoPoint.distMeters(position, route.geometry[it]) }
                ?: return@mapNotNull null
            val distance = GeoPoint.distMeters(position, route.geometry[closest])
            if (distance > MAX_REJOIN_DISTANCE_M) return@mapNotNull null
            val headingPenalty = bearing
                ?.takeIf { it >= 0f && route.geometry.size > 1 }
                ?.let { headingDifference(it, routeHeading(route, closest)) * 4.0 }
                ?: 0.0
            route to (distance + headingPenalty)
        }.minByOrNull { it.second }?.first

    private fun routeHeading(route: Route, index: Int): Float {
        val (from, to) = if (index < route.geometry.lastIndex) {
            route.geometry[index] to route.geometry[index + 1]
        } else {
            route.geometry[index - 1] to route.geometry[index]
        }
        return GeoPoint.bearing(from, to).toFloat()
    }

    private fun headingDifference(a: Float, b: Float): Double =
        kotlin.math.abs(((a - b + 540f) % 360f) - 180f).toDouble()
}
