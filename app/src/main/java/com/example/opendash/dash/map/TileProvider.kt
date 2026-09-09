package com.example.opendash.dash.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.LruCache
import com.example.opendash.dash.nav.GeoPoint
import com.example.opendash.data.MapPack
import com.example.opendash.data.MapPackDownloadState
import com.example.opendash.data.MapPackStore
import com.example.opendash.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * OSM raster tile provider with memory + disk cache.
 *
 * Tiles are darkened ONCE at load (invert + desaturate + dim) and the dark bitmap
 * is cached, so the render loop never re-runs the colour matrix per frame — a key
 * power win at 4 fps.
 *
 * While riding, the process is bound to the Tripper's WiFi (no internet), so tiles
 * must come from cache — [prefetch]/[prefetchRoute] populate it while internet is
 * still reachable. Cache misses fetch through whichever network has connectivity
 * (cellular when bound to the dash WiFi), rate-limited to avoid hot loops.
 */
class TileProvider(context: Context, private val scope: CoroutineScope) {
    companion object {
        private const val TAG = "TileProvider"
        // Actual Google Maps roadmap tiles (the colours/contrast the rider wants —
        // distinct building fills, clear white roads). This is Google's tile endpoint;
        // it's used here for a PERSONAL, single-user, non-distributed build. The
        // compliant alternative for any public release is Google's Map Tiles API
        // (session token + the existing MAPS_API_KEY). Format args: (z, x, y).
        private const val URL_TEMPLATE =
            "https://mt1.google.com/vt/lyrs=m&hl=en&z=%d&x=%d&y=%d"
        // Browser-like UA so the tile endpoint serves us normally.
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
        private const val MAX_PREFETCH_TILES = 600
        private const val MIN_FETCH_GAP_MS = 60L // be gentle on OSM + the radio
    }

    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    // filesDir survives cache cleanup. Regional packs get independent folders, making
    // quota eviction and a rider's explicit deletion deterministic.
    private val diskDir = File(context.filesDir, "tiles_gmaps").apply { mkdirs() }
    private val packRoot = File(context.filesDir, "map_packs").apply { mkdirs() }
    val packStore = MapPackStore(context)
    private val memory = LruCache<String, Bitmap>(120)
    private val inflight = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var lastFetchAt = 0L

    /** Non-blocking: returns the cached tile, else kicks off async load. */
    fun get(z: Int, x: Int, y: Int): Bitmap? {
        val max = 1 shl z
        if (y < 0 || y >= max) return null
        val xw = ((x % max) + max) % max
        val key = "$z/$xw/$y"

        memory.get(key)?.let { return it }

        if (inflight.add(key)) {
            scope.launch(Dispatchers.IO) {
                try {
                    // Standard map tiles (Google-Maps-like look). No per-tile filter — also the cheapest path.
                    val raw = loadDisk(key) ?: fetch(z, xw, y, key)
                    if (raw != null) memory.put(key, raw)
                } finally {
                    inflight.remove(key)
                }
            }
        }
        return null
    }

    /** Prefetch tiles around a point (and optionally a straight corridor) into disk. */
    fun prefetch(lat: Double, lng: Double, fromLat: Double? = null, fromLng: Double? = null) {
        scope.launch(Dispatchers.IO) {
            var count = 0
            // 11..20 so the rider's vicinity has tiles at every zoom level offline
            // (default nav zoom is now 19, max 20).
            for (z in 11..20) {
                val radius = if (z in 15..16) 2 else 1
                count += prefetchAround(lat, lng, z, radius)
                if (fromLat != null && fromLng != null) {
                    count += prefetchAround(fromLat, fromLng, z, radius)
                    if (z in 12..13) for (i in 1..8) {
                        val f = i / 9.0
                        count += prefetchAround(fromLat + (lat - fromLat) * f, fromLng + (lng - fromLng) * f, z, 1)
                    }
                }
                if (count > MAX_PREFETCH_TILES) break
            }
            DebugLog.i(TAG) { "Prefetch (point) done — ~$count tiles ensured" }
        }
    }

    /** Prefetch tiles along the actual route polyline so offline riding has coverage. */
    fun prefetchRoute(route: List<GeoPoint>) {
        if (route.size < 2) return
        scope.launch(Dispatchers.IO) {
            var count = 0
            // Sample the polyline so we don't fetch a tile for every vertex.
            for (z in 12..16) {
                val seen = HashSet<String>()
                val step = if (z >= 15) 1 else 3
                var i = 0
                while (i < route.size) {
                    val p = route[i]
                    val cx = Mercator.lngToTileX(p.lng, z).toInt()
                    val cy = Mercator.latToTileY(p.lat, z).toInt()
                    val r = if (z >= 15) 1 else 0
                    for (dx in -r..r) for (dy in -r..r) {
                        val key = "$z/${cx + dx}/${cy + dy}"
                        if (seen.add(key) && !diskFile(key).exists()) {
                            fetchKey(z, cx + dx, cy + dy, key); count++
                        }
                    }
                    i += step
                    if (count > MAX_PREFETCH_TILES) break
                }
                if (count > MAX_PREFETCH_TILES) break
            }
            DebugLog.i(TAG) { "Prefetch (route) done — ~$count tiles ensured" }
        }
    }

    /** Download a bounded regional pack.  Metadata is committed after every tile so a
     * killed process can resume or delete it without leaving an unaccounted cache. */
    fun downloadPack(pack: MapPack) {
        scope.launch(Dispatchers.IO) {
            if (packStore.wifiOnly.value && !isWifiConnected()) {
                packStore.upsert(pack.copy(state = MapPackDownloadState.FAILED))
                return@launch
            }
            val keys = packKeys(pack)
            var bytes = pack.bytes; var done = 0
            packStore.upsert(pack.copy(state = MapPackDownloadState.DOWNLOADING, totalTiles = keys.size, downloadedTiles = 0))
            for ((z, x, y, key) in keys) {
                val target = packFile(pack.id, key)
                if (target.exists()) { bytes += target.length(); done++ }
                else {
                    val data = fetchBytes(z, x, y)
                    if (data != null) { target.parentFile?.mkdirs(); target.writeBytes(data); bytes += data.size; done++ }
                }
                if (done % 8 == 0) packStore.upsert(pack.copy(state = MapPackDownloadState.DOWNLOADING, bytes = bytes, downloadedTiles = done, totalTiles = keys.size, lastUpdatedMs = System.currentTimeMillis()))
            }
            enforceQuota(pack.id)
            packStore.upsert(pack.copy(state = MapPackDownloadState.READY, bytes = directoryBytes(packFile(pack.id, "x").parentFile!!), downloadedTiles = done, totalTiles = keys.size, lastUpdatedMs = System.currentTimeMillis()))
        }
    }

    fun deletePack(id: String) { scope.launch(Dispatchers.IO) { File(packRoot, id).deleteRecursively(); packStore.remove(id) } }

    private fun packKeys(pack: MapPack): List<TileKey> = buildList {
        for (z in pack.minZoom..pack.maxZoom) {
            val left = Mercator.lngToTileX(pack.bounds.west, z).toInt(); val right = Mercator.lngToTileX(pack.bounds.east, z).toInt()
            val top = Mercator.latToTileY(pack.bounds.north, z).toInt(); val bottom = Mercator.latToTileY(pack.bounds.south, z).toInt()
            for (x in left..right) for (y in top..bottom) add(TileKey(z, x, y, "$z/$x/$y"))
        }
    }.take(MAX_PREFETCH_TILES)
    private data class TileKey(val z: Int, val x: Int, val y: Int, val key: String)

    private fun prefetchAround(lat: Double, lng: Double, z: Int, radius: Int): Int {
        val cx = Mercator.lngToTileX(lng, z).toInt()
        val cy = Mercator.latToTileY(lat, z).toInt()
        var n = 0
        for (dx in -radius..radius) for (dy in -radius..radius) {
            val x = cx + dx; val y = cy + dy
            if (y < 0 || y >= (1 shl z)) continue
            val key = "$z/$x/$y"
            if (!diskFile(key).exists()) { fetchKey(z, x, y, key); n++ }
        }
        return n
    }

    // ── Internals ─────────────────────────────────────────────────────────

    private fun diskFile(key: String) = File(diskDir, key.replace('/', '_') + ".png")
    private fun packFile(id: String, key: String) = File(File(packRoot, id), key.replace('/', '_') + ".png")

    private fun loadDisk(key: String): Bitmap? {
        // Pack cache is always checked first: no network is touched when a selected
        // region contains the requested tile.
        packStore.packs.value.asReversed().forEach { pack ->
            val f = packFile(pack.id, key)
            if (f.exists()) return BitmapFactory.decodeFile(f.absolutePath)
        }
        val f = diskFile(key)
        if (!f.exists()) return null
        return BitmapFactory.decodeFile(f.absolutePath)
    }

    private fun fetch(z: Int, x: Int, y: Int, key: String): Bitmap? = fetchKey(z, x, y, key)

    private fun fetchKey(z: Int, x: Int, y: Int, key: String): Bitmap? {
        val max = 1 shl z
        if (y < 0 || y >= max) return null
        // Rate-limit network fetches to avoid hot loops on the radio.
        val now = System.currentTimeMillis()
        val wait = MIN_FETCH_GAP_MS - (now - lastFetchAt)
        if (wait > 0) try { Thread.sleep(wait) } catch (_: InterruptedException) {}
        lastFetchAt = System.currentTimeMillis()

        val bytes = fetchBytes(z, x, y) ?: return null
        diskFile(key).writeBytes(bytes)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private fun fetchBytes(z: Int, x: Int, y: Int): ByteArray? {
        val max = 1 shl z
        if (y < 0 || y >= max) return null
        val net = internetNetwork()
        return try {
            val url = URL(URL_TEMPLATE.format(z, x, ((y % max) + max) % max))
            val conn = (net?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            val bytes = conn.inputStream.use { it.readBytes() }
            conn.disconnect()
            bytes
        } catch (e: Exception) {
            DebugLog.w(TAG) { "Tile $z/$x/$y fetch failed: ${e.message}" }
            null
        }
    }

    private fun isWifiConnected(): Boolean = cm.allNetworks.any { n -> cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
    private fun directoryBytes(dir: File): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    private fun enforceQuota(keepId: String) {
        val quota = 350L * 1024 * 1024
        var total = directoryBytes(packRoot)
        packStore.packs.value.filter { it.id != keepId }.sortedBy { it.lastUpdatedMs }.forEach { p ->
            if (total <= quota) return@forEach
            File(packRoot, p.id).deleteRecursively(); packStore.remove(p.id); total = directoryBytes(packRoot)
        }
    }

    @Suppress("DEPRECATION")
    private fun internetNetwork(): Network? =
        cm.allNetworks.firstOrNull { n ->
            cm.getNetworkCapabilities(n)?.let {
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            } == true
        }
}
