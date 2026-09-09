package com.example.opendash.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

enum class MapPackDownloadState { NOT_DOWNLOADED, DOWNLOADING, READY, FAILED }

data class MapBounds(val south: Double, val west: Double, val north: Double, val east: Double)
data class MapPack(
    val id: String,
    val name: String,
    val bounds: MapBounds,
    val minZoom: Int,
    val maxZoom: Int,
    val state: MapPackDownloadState = MapPackDownloadState.NOT_DOWNLOADED,
    val bytes: Long = 0,
    val lastUpdatedMs: Long = 0,
    val downloadedTiles: Int = 0,
    val totalTiles: Int = 0,
)

/** Small, dependency-free persistent catalog for regional raster packs. */
class MapPackStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("map_packs", Context.MODE_PRIVATE)
    private val _packs = MutableStateFlow(read())
    val packs = _packs.asStateFlow()
    private val _wifiOnly = MutableStateFlow(prefs.getBoolean("wifi_only", true))
    val wifiOnly = _wifiOnly.asStateFlow()

    fun setWifiOnly(value: Boolean) { prefs.edit().putBoolean("wifi_only", value).apply(); _wifiOnly.value = value }
    @Synchronized fun upsert(pack: MapPack) { _packs.value = _packs.value.filterNot { it.id == pack.id } + pack; write() }
    @Synchronized fun remove(id: String) { _packs.value = _packs.value.filterNot { it.id == id }; write() }
    fun pack(id: String): MapPack? = _packs.value.firstOrNull { it.id == id }

    private fun write() = prefs.edit().putString("catalog", JSONArray().apply {
        _packs.value.forEach { p -> put(JSONObject().apply {
            put("id", p.id); put("name", p.name); put("s", p.bounds.south); put("w", p.bounds.west); put("n", p.bounds.north); put("e", p.bounds.east)
            put("min", p.minZoom); put("max", p.maxZoom); put("state", p.state.name); put("bytes", p.bytes); put("updated", p.lastUpdatedMs); put("done", p.downloadedTiles); put("total", p.totalTiles)
        }) }
    }.toString()).apply()
    private fun read(): List<MapPack> = runCatching {
        val a = JSONArray(prefs.getString("catalog", "[]")); List(a.length()) { i -> a.getJSONObject(i).let { o -> MapPack(o.getString("id"), o.getString("name"), MapBounds(o.getDouble("s"), o.getDouble("w"), o.getDouble("n"), o.getDouble("e")), o.getInt("min"), o.getInt("max"), MapPackDownloadState.valueOf(o.optString("state", "NOT_DOWNLOADED")), o.optLong("bytes"), o.optLong("updated"), o.optInt("done"), o.optInt("total")) } }
    }.getOrDefault(emptyList())
}
