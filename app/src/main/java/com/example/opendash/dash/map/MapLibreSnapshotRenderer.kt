package com.example.opendash.dash.map

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.example.opendash.dash.nav.GeoPoint
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.Style
import org.maplibre.android.snapshotter.MapSnapshot
import org.maplibre.android.snapshotter.MapSnapshotter
import kotlin.math.max

/**
 * Service-owned, off-screen MapLibre vector renderer. It does not depend on a Compose
 * MapView or Activity window, so snapshots continue while the app UI is backgrounded.
 *
 * MapSnapshotter renders the Liberty vector style (including its 3D building layers) with
 * a real pitched/bearing camera. Route and rider annotations are projected into that same
 * snapshot; the ViewModel adds its dash HUD before encoding.
 */
internal class MapLibreSnapshotRenderer(
    context: Context,
    private val onFrame: (android.graphics.Bitmap) -> Unit,
    private val onError: (String) -> Unit,
) {
    companion object {
        private const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
        private const val WIDTH = 526
        private const val HEIGHT = 300
        private const val MIN_FRAME_INTERVAL_MS = 180L
    }

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val routeCasing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 10f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(66, 133, 244)
        style = Paint.Style.STROKE
        strokeWidth = 5.5f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val markerPath = Path()

    private var snapshotter: MapSnapshotter? = null
    private var latestFrame: MapRenderer.Frame? = null
    private var rendering = false
    private var released = false
    private var lastStartedAt = 0L

    fun submit(frame: MapRenderer.Frame) {
        main.post {
            if (released) return@post
            latestFrame = frame
            renderLatest()
        }
    }

    private fun renderLatest() {
        if (released || rendering) return
        val frame = latestFrame ?: return
        val now = SystemClock.elapsedRealtime()
        val wait = MIN_FRAME_INTERVAL_MS - (now - lastStartedAt)
        if (wait > 0L) {
            main.removeCallbacks(renderRunnable)
            main.postDelayed(renderRunnable, wait)
            return
        }

        val camera = CameraPosition.Builder()
            .target(LatLng(frame.centerLat, frame.centerLng))
            .zoom(frame.zoom.toDouble())
            .bearing(if (frame.headingUp) frame.heading.toDouble() else 0.0)
            .tilt(if (frame.tilt3d) 55.0 else 0.0)
            .build()

        val engine = snapshotter ?: runCatching {
            MapLibre.getInstance(appContext)
            MapSnapshotter(
                appContext,
                MapSnapshotter.Options(WIDTH, HEIGHT)
                    .withStyleBuilder(Style.Builder().fromUri(STYLE_URL))
                    .withCameraPosition(camera)
                    .withLogo(false)
            ).also { snapshotter = it }
        }.getOrElse {
            onError("MapLibre renderer init failed: ${it.message}")
            return
        }

        engine.setCameraPosition(camera)
        rendering = true
        lastStartedAt = now
        runCatching {
            engine.start({ snapshot ->
                if (released) return@start
                runCatching { drawAnnotations(snapshot, frame) }
                    .onFailure { onError("Map annotation render failed: ${it.message}") }
                onFrame(snapshot.bitmap)
                rendering = false
                // If a newer frame arrived during rendering, immediately render that state.
                if (latestFrame !== frame) renderLatest()
            }, { error ->
                rendering = false
                onError("MapLibre snapshot failed: $error")
                if (latestFrame !== frame) renderLatest()
            })
        }.onFailure {
            rendering = false
            onError("MapLibre snapshot start failed: ${it.message}")
        }
    }

    private val renderRunnable = Runnable { renderLatest() }

    private fun drawAnnotations(snapshot: MapSnapshot, frame: MapRenderer.Frame) {
        val bitmap = snapshot.bitmap
        val canvas = Canvas(bitmap)
        val route = frame.route
        if (route.size >= 2) {
            val path = projectedPath(snapshot, route)
            canvas.drawPath(path, routeCasing)
            canvas.drawPath(path, routePaint)
        }

        if (frame.destLat != null && frame.destLng != null) {
            val p = snapshot.pixelForLatLng(LatLng(frame.destLat, frame.destLng))
            markerPaint.color = Color.WHITE
            canvas.drawCircle(p.x, p.y, 10f, markerPaint)
            markerPaint.color = Color.rgb(234, 67, 53)
            canvas.drawCircle(p.x, p.y, 7f, markerPaint)
            markerPaint.color = Color.WHITE
            canvas.drawCircle(p.x, p.y, 2.5f, markerPaint)
        }

        if (frame.riderLat != null && frame.riderLng != null) {
            val p = snapshot.pixelForLatLng(LatLng(frame.riderLat, frame.riderLng))
            val tint = when {
                frame.gpsLost -> Color.rgb(150, 154, 160)
                frame.gpsWeak -> Color.rgb(251, 188, 5)
                else -> Color.rgb(66, 133, 244)
            }
            markerPaint.color = Color.argb(65, Color.red(tint), Color.green(tint), Color.blue(tint))
            canvas.drawCircle(p.x, p.y, 16f, markerPaint)
            markerPaint.color = Color.WHITE
            canvas.drawCircle(p.x, p.y, 9f, markerPaint)
            markerPaint.color = tint
            markerPath.reset()
            markerPath.moveTo(p.x, p.y - 10f)
            markerPath.lineTo(p.x - 7f, p.y + 7f)
            markerPath.lineTo(p.x + 7f, p.y + 7f)
            markerPath.close()
            val save = canvas.save()
            if (!frame.headingUp) canvas.rotate(frame.heading, p.x, p.y)
            canvas.drawPath(markerPath, markerPaint)
            canvas.restoreToCount(save)
        }
    }

    private fun projectedPath(snapshot: MapSnapshot, points: List<GeoPoint>): Path =
        Path().apply {
            points.forEachIndexed { index, point ->
                val pixel: PointF = snapshot.pixelForLatLng(LatLng(point.lat, point.lng))
                if (index == 0) moveTo(pixel.x, pixel.y) else lineTo(pixel.x, pixel.y)
            }
        }

    fun release() {
        main.post {
            if (released) return@post
            released = true
            main.removeCallbacks(renderRunnable)
            runCatching { snapshotter?.cancel() }
            snapshotter = null
            latestFrame = null
        }
    }
}
