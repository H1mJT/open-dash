package com.example.opendash.dash.nav

import android.content.Context
import com.example.opendash.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches a road route from the public OSRM demo server. Called at planning time
 * (destination shared) while the phone still has internet. It downloads OSRM's
 * alternatives too, so a rider can continue on a previously planned option offline.
 */
object Router {
    private const val TAG = "Router"
    private const val BASE = "https://router.project-osrm.org/route/v1/driving"
    private const val UA = "OpenDash/1.1 (personal motorcycle nav; single user)"

    suspend fun route(context: Context, from: GeoPoint, to: GeoPoint, bearing: Float? = null): Route? = withContext(Dispatchers.IO) {
        val url = "$BASE/${from.lng},${from.lat};${to.lng},${to.lat}" +
                "?overview=full&geometries=polyline&steps=true&annotations=false&alternatives=true"
        try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                setRequestProperty("User-Agent", UA)
                connectTimeout = 10_000
                readTimeout = 10_000
            }
            val body = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            conn.disconnect()
            parseRoutes(body)?.also { routes ->
                RouteCache(context).save(routes, from, to)
            }?.firstOrNull()
        } catch (e: Exception) {
            DebugLog.w(TAG) { "route() failed: ${e.message}" }
            RouteCache(context).compatible(from, to, bearing)?.copy(isOffline = true)
        }
    }

    private fun parseRoutes(json: String): List<Route>? {
        val root = JSONObject(json)
        if (root.optString("code") != "Ok") {
            DebugLog.w(TAG) { "OSRM code=${root.optString("code")}" }
            return null
        }
        val routes = root.optJSONArray("routes") ?: return null
        if (routes.length() == 0) return null
        return List(routes.length()) { index -> parseRoute(routes.getJSONObject(index)) }
            .filterNotNull()
            .take(MAX_OFFLINE_ALTERNATIVES)
            .takeIf { it.isNotEmpty() }
    }

    private fun parseRoute(r0: JSONObject): Route? {

        val geometry = PolylineCodec.decode(r0.getString("geometry"))
        if (geometry.size < 2) return null

        // Cumulative distance at each vertex
        val cum = DoubleArray(geometry.size)
        for (i in 1 until geometry.size) {
            cum[i] = cum[i - 1] + GeoPoint.distMeters(geometry[i - 1], geometry[i])
        }

        // Maneuvers from steps
        val maneuvers = ArrayList<Maneuver>()
        val legs = r0.optJSONArray("legs")
        if (legs != null) {
            for (li in 0 until legs.length()) {
                val steps = legs.getJSONObject(li).optJSONArray("steps") ?: continue
                for (si in 0 until steps.length()) {
                    val step = steps.getJSONObject(si)
                    val man = step.optJSONObject("maneuver") ?: continue
                    val loc = man.optJSONArray("location") ?: continue
                    val p = GeoPoint(loc.getDouble(1), loc.getDouble(0))
                    val type = ManeuverType.fromOsrm(man.optString("type"), man.optString("modifier"))
                    val roadName = step.optString("name").takeIf { it.isNotBlank() }
                    val roadRef = step.optString("ref").takeIf { it.isNotBlank() }
                    val lanes = parseLanes(step)
                    maneuvers.add(
                        Maneuver(
                            type = type,
                            // Some OSRM-compatible providers include prose; do not discard it.
                            instruction = step.optString("instruction").takeIf { it.isNotBlank() }
                                ?: man.optString("instruction").takeIf { it.isNotBlank() }
                                ?: buildInstruction(type, roadName ?: roadRef),
                            roadName = roadName,
                            roadRef = roadRef,
                            lanes = lanes,
                            rawType = man.optString("type").takeIf { it.isNotBlank() },
                            modifier = man.optString("modifier").takeIf { it.isNotBlank() },
                            location = p,
                            cumulativeMeters = nearestCumulative(p, geometry, cum),
                        )
                    )
                }
            }
        }

        return Route(
            geometry = geometry,
            maneuvers = maneuvers,
            totalMeters = r0.optDouble("distance", cum.last()),
            totalSeconds = r0.optDouble("duration", 0.0),
            cumulative = cum,
        )
    }

    private const val MAX_OFFLINE_ALTERNATIVES = 3

    /** OSRM has no standard prose instruction; retain all supplied route metadata and build stable text. */
    private fun buildInstruction(type: ManeuverType, road: String?): String {
        val destination = road?.takeIf { it.isNotBlank() }
        val onto = destination?.let { " onto $it" }.orEmpty()
        return when (type) {
        ManeuverType.DEPART       -> destination?.let { "Head out on $it" } ?: "Head out"
        ManeuverType.ARRIVE       -> "Arrive at destination"
        ManeuverType.TURN_LEFT    -> "Turn left$onto"
        ManeuverType.TURN_RIGHT   -> "Turn right$onto"
        ManeuverType.SLIGHT_LEFT  -> "Slight left$onto"
        ManeuverType.SLIGHT_RIGHT -> "Slight right$onto"
        ManeuverType.SHARP_LEFT   -> "Sharp left$onto"
        ManeuverType.SHARP_RIGHT  -> "Sharp right$onto"
        ManeuverType.UTURN        -> "Make a U-turn$onto"
        ManeuverType.ROUNDABOUT   -> "At the roundabout, take$onto"
        ManeuverType.CONTINUE     -> "Continue${destination?.let { " on $it" }.orEmpty()}"
        }
    }

    private fun parseLanes(step: JSONObject): List<LaneGuidance> {
        val intersections = step.optJSONArray("intersections") ?: return emptyList()
        for (i in 0 until intersections.length()) {
            val lanes = intersections.optJSONObject(i)?.optJSONArray("lanes") ?: continue
            return List(lanes.length()) { index ->
                val lane = lanes.optJSONObject(index)
                LaneGuidance(
                    indications = lane?.optString("indications")?.split(';')?.filter { it.isNotBlank() }.orEmpty(),
                    isValid = lane?.optBoolean("valid", false) ?: false,
                )
            }
        }
        return emptyList()
    }

    /** Cumulative distance of the geometry vertex nearest to a maneuver location. */
    private fun nearestCumulative(p: GeoPoint, geom: List<GeoPoint>, cum: DoubleArray): Double {
        var best = 0.0
        var bestD = Double.MAX_VALUE
        for (i in geom.indices) {
            val d = GeoPoint.distMeters(p, geom[i])
            if (d < bestD) { bestD = d; best = cum[i] }
        }
        return best
    }
}

/** Persists the planned route plus its alternates for offline continuation/rerouting. */
private class RouteCache(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("active_route", Context.MODE_PRIVATE)
    fun save(routes: List<Route>, origin: GeoPoint, destination: GeoPoint) {
        val o = JSONObject().put("destLat", destination.lat).put("destLng", destination.lng)
            .put("originLat", origin.lat).put("originLng", origin.lng)
        o.put("routes", org.json.JSONArray().apply { routes.forEach { put(routeJson(it)) } })
        prefs.edit().putString("route", o.toString()).apply()
    }
    fun compatible(origin: GeoPoint, destination: GeoPoint, bearing: Float?): Route? = runCatching {
        val o = JSONObject(prefs.getString("route", null) ?: return null)
        val cachedDest = GeoPoint(o.getDouble("destLat"), o.getDouble("destLng"))
        if (GeoPoint.distMeters(cachedDest, destination) > 500) return null
        val cached = o.optJSONArray("routes") ?: org.json.JSONArray().put(o) // Read the pre-alternatives cache once.
        val routes = List(cached.length()) { parseRoute(cached.getJSONObject(it)) }
        OfflineRouteSelector.choose(routes, origin, bearing)
    }.getOrNull()

    private fun routeJson(route: Route): JSONObject = JSONObject()
        .put("meters", route.totalMeters).put("seconds", route.totalSeconds)
        .put("geometry", org.json.JSONArray().apply { route.geometry.forEach { put(org.json.JSONArray().put(it.lat).put(it.lng)) } })
        .put("cumulative", org.json.JSONArray().apply { route.cumulative.forEach { put(it) } })
        .put("maneuvers", org.json.JSONArray().apply { route.maneuvers.forEach { m -> put(JSONObject().put("type", m.type.name).put("instruction", m.instruction).put("road", m.roadName).put("ref", m.roadRef).put("raw", m.rawType).put("modifier", m.modifier).put("lat", m.location.lat).put("lng", m.location.lng).put("cum", m.cumulativeMeters)) } })

    private fun parseRoute(o: JSONObject): Route {
        val g = o.getJSONArray("geometry"); val geometry = List(g.length()) { i -> g.getJSONArray(i).let { GeoPoint(it.getDouble(0), it.getDouble(1)) } }
        val c = o.getJSONArray("cumulative"); val cumulative = DoubleArray(c.length()) { c.getDouble(it) }
        val ms = o.getJSONArray("maneuvers"); val maneuvers = List(ms.length()) { i -> ms.getJSONObject(i).let { m -> Maneuver(ManeuverType.valueOf(m.getString("type")), m.getString("instruction"), m.optString("road").takeIf { it.isNotBlank() && it != "null" }, m.optString("ref").takeIf { it.isNotBlank() && it != "null" }, rawType = m.optString("raw").takeIf { it.isNotBlank() && it != "null" }, modifier = m.optString("modifier").takeIf { it.isNotBlank() && it != "null" }, location = GeoPoint(m.getDouble("lat"), m.getDouble("lng")), cumulativeMeters = m.getDouble("cum")) } }
        return Route(geometry, maneuvers, o.getDouble("meters"), o.getDouble("seconds"), cumulative)
    }
}
