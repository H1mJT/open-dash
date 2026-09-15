package com.example.opendash.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.webkit.WebView
import android.webkit.WebViewClient
import android.graphics.Path
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import com.example.opendash.data.MapProvider
import com.example.opendash.data.MapProviderSettings
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.opendash.dash.nav.GeoPoint
import com.example.opendash.dash.map.Mercator
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.plugins.annotation.LineManager
import org.maplibre.android.plugins.annotation.LineOptions
import org.maplibre.android.plugins.annotation.SymbolManager
import org.maplibre.android.plugins.annotation.SymbolOptions
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

// Free, keyless, redistributable vector basemap (look-first, per the distribution decision).
private const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
private const val FOLLOW_ZOOM = 15.5
private const val NAV_ZOOM = 16.5
private const val NAV_TILT = 55.0
private const val NAV_LOOK_AHEAD_METERS = 115.0
private const val MOVEMENT_HEADING_THRESHOLD_KPH = 10f
private const val RIDER_ICON = "rider-chevron"
private const val DEST_ICON = "dest-pin"

/**
 * In-app phone map. Riders can select keyless MapLibre/OpenFreeMap or Google Maps Embed
 * in Settings; the physical dash still uses the off-screen power-efficient renderer.
 *
 * Modes: [fitRoute] frames the whole route; [navMode] tilts/zooms/rotates to heading with a
 * rider chevron; default follows the rider north-up.
 */
@Composable
fun OpenDashMap(
    riderLat: Double?,
    riderLng: Double?,
    dest: Pair<Double, Double>?,
    routePoints: List<GeoPoint>,
    hasLocationPermission: Boolean,
    modifier: Modifier = Modifier,
    fitRoute: Boolean = false,
    navMode: Boolean = false,
    riderBearing: Float = 0f,
    riderSpeedKph: Float = 0f,
    /** Whether the in-app navigation preview uses the same perspective tilt as the dash. */
    navigationTiltEnabled: Boolean = true,
    /** Dash renderer camera values, used by the in-app preview when its controls are used. */
    dashZoom: Int? = null,
    dashPanX: Float = 0f,
    dashPanY: Float = 0f,
) {
    val provider by MapProviderSettings.provider.collectAsState()
    val hasGoogleMapsKey by MapProviderSettings.hasGoogleMapsKey.collectAsState()
    // Google Maps Embed does not expose route-progress, heading, or camera-tilt APIs.
    // Keep it for static browsing, but use the same controllable MapLibre navigation
    // camera as the dash whenever guidance is active.
    if (provider == MapProvider.GOOGLE_MAPS && hasGoogleMapsKey && !navMode) {
        GoogleMapsEmbed(
            riderLat = riderLat,
            riderLng = riderLng,
            dest = dest,
            modifier = modifier,
        )
    } else {
        MapLibreOpenDashMap(
            riderLat, riderLng, dest, routePoints, hasLocationPermission, fitRoute, navMode,
            riderBearing, riderSpeedKph, navigationTiltEnabled, dashZoom, dashPanX, dashPanY, modifier,
        )
    }
}

@Composable
private fun MapLibreOpenDashMap(
    riderLat: Double?,
    riderLng: Double?,
    dest: Pair<Double, Double>?,
    routePoints: List<GeoPoint>,
    hasLocationPermission: Boolean,
    fitRoute: Boolean,
    navMode: Boolean,
    riderBearing: Float,
    riderSpeedKph: Float,
    navigationTiltEnabled: Boolean,
    dashZoom: Int?,
    dashPanX: Float,
    dashPanY: Float,
    modifier: Modifier,
) {
    val context = LocalContext.current
    remember { MapLibre.getInstance(context) }
    val mapView = remember { MapView(context) }
    val phoneHeading = rememberPhoneHeading()
    // At riding speed, GPS/map-matched bearing is more trustworthy than a phone that may
    // be tilted in its mount. Below that speed, use the compass like Google Maps does.
    val cameraBearing = if (navMode && riderSpeedKph < MOVEMENT_HEADING_THRESHOLD_KPH)
        phoneHeading ?: riderBearing
    else riderBearing

    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var lineMgr by remember { mutableStateOf<LineManager?>(null) }
    var symbolMgr by remember { mutableStateOf<SymbolManager?>(null) }
    var styleReady by remember { mutableStateOf(false) }
    var destroyed by remember { mutableStateOf(false) }

    // Bind the MapView to the composition lifecycle.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_CREATE  -> mapView.onCreate(null)
                Lifecycle.Event.ON_START   -> mapView.onStart()
                Lifecycle.Event.ON_RESUME  -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE   -> mapView.onPause()
                Lifecycle.Event.ON_STOP    -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose {
            destroyed = true
            lifecycleOwner.lifecycle.removeObserver(obs)
            mapView.onDestroy()
        }
    }

    LaunchedEffect(mapView) {
        mapView.getMapAsync { m ->
            if (destroyed) return@getMapAsync
            map = m
            m.uiSettings.apply {
                isRotateGesturesEnabled = false
                isTiltGesturesEnabled = false
                isCompassEnabled = false
                isAttributionEnabled = true   // OSM/OpenFreeMap attribution (keep — licensing)
                isLogoEnabled = false
            }
            m.setStyle(Style.Builder().fromUri(STYLE_URL)) { style ->
                if (destroyed) return@setStyle
                style.addImage(RIDER_ICON, chevronBitmap())
                style.addImage(DEST_ICON, destPinBitmap())
                lineMgr = LineManager(mapView, m, style)
                symbolMgr = SymbolManager(mapView, m, style).apply {
                    iconAllowOverlap = true; iconIgnorePlacement = true
                }
                styleReady = true
            }
        }
    }

    // Redraw route + markers whenever navigation progress or mode changes. The view model
    // supplies only the untravelled route while guiding, so the blue line starts at the rider
    // and naturally disappears behind them.
    LaunchedEffect(styleReady, routePoints, dest, riderLat, riderLng, cameraBearing, navMode) {
        if (destroyed) return@LaunchedEffect
        val lm = lineMgr ?: return@LaunchedEffect
        val sm = symbolMgr ?: return@LaunchedEffect
        lm.deleteAll(); sm.deleteAll()
        if (routePoints.size >= 2) {
            lm.create(
                LineOptions().withLatLngs(routePoints.map { LatLng(it.lat, it.lng) })
                    .withLineColor("#4285F4").withLineWidth(5.5f)
            )
        }
        dest?.let { sm.create(SymbolOptions().withLatLng(LatLng(it.first, it.second)).withIconImage(DEST_ICON).withIconSize(1.1f)) }
        if (navMode && riderLat != null && riderLng != null) {
            sm.create(
                SymbolOptions().withLatLng(LatLng(riderLat, riderLng))
                    .withIconImage(RIDER_ICON)
                    // In heading-up mode the camera already rotates the road beneath a fixed
                    // arrow; rotating the icon again would make it drift away from "forward".
                    .withIconRotate(if (navMode) 0f else cameraBearing).withIconSize(1.0f)
            )
        }
    }

    // Camera control.
    LaunchedEffect(styleReady, riderLat, riderLng, cameraBearing, navMode, navigationTiltEnabled, fitRoute, routePoints, dashZoom, dashPanX, dashPanY) {
        if (destroyed) return@LaunchedEffect
        val m = map ?: return@LaunchedEffect
        if (!styleReady) return@LaunchedEffect
        when {
            fitRoute && routePoints.size >= 2 -> {
                val b = LatLngBounds.Builder()
                routePoints.forEach { b.include(LatLng(it.lat, it.lng)) }
                runCatching { m.animateCamera(CameraUpdateFactory.newLatLngBounds(b.build(), 110)) }
            }
            riderLat != null && riderLng != null -> {
                // Target a point ahead of the rider rather than the rider itself. This leaves
                // the arrow in the lower part of the screen and exposes substantially more of
                // the upcoming road, matching a navigation camera rather than a top-down map.
                val target = if (navMode) {
                    val ahead = pointAhead(riderLat, riderLng, cameraBearing.toDouble(), NAV_LOOK_AHEAD_METERS)
                    LatLng(ahead.lat, ahead.lng)
                } else {
                    LatLng(riderLat, riderLng)
                }
                // The physical dash uses Web-Mercator tile pixels for pan. Transform the
                // same pixel offsets into a geographic camera target for this preview.
                val dashTarget = dashZoom?.let { zoom ->
                    val tileX = Mercator.lngToTileX(target.longitude, zoom) + dashPanX / Mercator.TILE_SIZE
                    val tileY = Mercator.latToTileY(target.latitude, zoom) + dashPanY / Mercator.TILE_SIZE
                    LatLng(Mercator.tileYToLat(tileY, zoom), Mercator.tileXToLng(tileX, zoom))
                } ?: target
                val previewZoom = dashZoom?.toDouble() ?: if (navMode) NAV_ZOOM else FOLLOW_ZOOM
                val pos = if (navMode)
                    CameraPosition.Builder().target(dashTarget).zoom(previewZoom)
                        .tilt(if (navigationTiltEnabled) NAV_TILT else 0.0)
                        .bearing(cameraBearing.toDouble()).build()
                else
                    CameraPosition.Builder().target(dashTarget).zoom(previewZoom).tilt(0.0).bearing(0.0).build()
                runCatching { m.animateCamera(CameraUpdateFactory.newCameraPosition(pos), 600) }
            }
            dest != null -> runCatching {
                m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(dest.first, dest.second), 13.0))
            }
        }
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}

/** Current compass heading of the phone, or null on devices without a rotation-vector sensor. */
@Composable
private fun rememberPhoneHeading(): Float? {
    val context = LocalContext.current
    var heading by remember { mutableStateOf<Float?>(null) }
    DisposableEffect(context) {
        val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val rotationSensor = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val matrix = FloatArray(9)
                val orientation = FloatArray(3)
                SensorManager.getRotationMatrixFromVector(matrix, event.values)
                SensorManager.getOrientation(matrix, orientation)
                val next = ((Math.toDegrees(orientation[0].toDouble()) + 360.0) % 360.0).toFloat()
                val current = heading
                // Ignore imperceptible compass noise so we do not constantly restart the
                // camera animation while the phone is resting in its mount.
                if (current == null || headingDifference(current, next) >= 2f) heading = next
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        if (rotationSensor != null) sensors.registerListener(listener, rotationSensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { sensors.unregisterListener(listener) }
    }
    return heading
}

private fun headingDifference(first: Float, second: Float): Float =
    kotlin.math.abs(((first - second + 540f) % 360f) - 180f)

@Composable
private fun GoogleMapsEmbed(
    riderLat: Double?,
    riderLng: Double?,
    dest: Pair<Double, Double>?,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val key = MapProviderSettings.googleMapsKey() ?: return
    val webView = remember { WebView(context) }
    val url = remember(key, riderLat, riderLng, dest) {
        val destination = dest?.let { "${it.first},${it.second}" }
        val origin = if (riderLat != null && riderLng != null) "$riderLat,$riderLng" else null
        when {
            destination != null && origin != null ->
                "https://www.google.com/maps/embed/v1/directions?key=${Uri.encode(key)}&origin=${Uri.encode(origin)}&destination=${Uri.encode(destination)}&mode=driving"
            destination != null ->
                "https://www.google.com/maps/embed/v1/place?key=${Uri.encode(key)}&q=${Uri.encode(destination)}"
            origin != null ->
                "https://www.google.com/maps/embed/v1/place?key=${Uri.encode(key)}&q=${Uri.encode(origin)}"
            else -> "https://www.google.com/maps/embed/v1/view?key=${Uri.encode(key)}&center=0,0&zoom=1"
        }
    }
    AndroidView(
        factory = {
            webView.apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = false
                settings.setGeolocationEnabled(false)
                webViewClient = WebViewClient()
                loadUrl(url)
            }
        },
        update = { if (it.url != url) it.loadUrl(url) },
        modifier = modifier,
    )
    DisposableEffect(webView) {
        onDispose { webView.destroy() }
    }
}

/** Google-style blue chevron-in-a-circle, pointing "up" (rotated to heading by the symbol). */
private fun chevronBitmap(): Bitmap {
    val s = 84
    val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.color = android.graphics.Color.WHITE
    c.drawCircle(s / 2f, s / 2f, s * 0.34f, p)
    p.color = android.graphics.Color.rgb(66, 133, 244)
    c.drawCircle(s / 2f, s / 2f, s * 0.30f, p)
    p.color = android.graphics.Color.WHITE
    val cx = s / 2f
    c.drawPath(Path().apply {
        moveTo(cx, s * 0.24f); lineTo(s * 0.72f, s * 0.70f); lineTo(cx, s * 0.58f); lineTo(s * 0.28f, s * 0.70f); close()
    }, p)
    return bmp
}

/** Simple red destination pin (white ring + red fill). */
private fun destPinBitmap(): Bitmap {
    val s = 72
    val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.color = android.graphics.Color.WHITE; c.drawCircle(s / 2f, s / 2f, s * 0.30f, p)
    p.color = android.graphics.Color.rgb(234, 67, 53); c.drawCircle(s / 2f, s / 2f, s * 0.24f, p)
    p.color = android.graphics.Color.WHITE; c.drawCircle(s / 2f, s / 2f, s * 0.09f, p)
    return bmp
}

/** Returns the coordinate [meters] ahead along [bearingDegrees] on a spherical Earth. */
private fun pointAhead(lat: Double, lng: Double, bearingDegrees: Double, meters: Double): GeoPoint {
    val angularDistance = meters / 6_371_000.0
    val bearing = Math.toRadians(bearingDegrees)
    val lat1 = Math.toRadians(lat)
    val lng1 = Math.toRadians(lng)
    val lat2 = asin(sin(lat1) * cos(angularDistance) + cos(lat1) * sin(angularDistance) * cos(bearing))
    val lng2 = lng1 + atan2(
        sin(bearing) * sin(angularDistance) * cos(lat1),
        cos(angularDistance) - sin(lat1) * sin(lat2),
    )
    return GeoPoint(Math.toDegrees(lat2), Math.toDegrees(lng2))
}
