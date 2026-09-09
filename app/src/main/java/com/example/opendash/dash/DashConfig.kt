package com.example.opendash.dash

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.opendash.util.DebugLog
import com.example.opendash.dash.nav.ManeuverType

/** Rider-selected hierarchy for the already-rendered video dashboard. */
enum class DashLayout(val label: String) {
    MAP_FIRST("Map first"),
    TURN_FIRST("Turn first"),
    LARGE_SPEED("Large speed"),
    MINIMAL_NIGHT("Minimal night"),
}

/**
 * Per-rider dash WiFi configuration, persisted on-device.
 *
 * OpenDash is meant to work on any compatible Tripper dash, not just the author's.
 * Every dash advertises a different SSID (e.g. `RE_P0RP_260525`, `RE_XXXX_yymmdd`) but
 * they all share the `RE_` prefix and the factory passphrase `12345678`. So out of the
 * box we connect by PREFIX (see [DashWifiManager]) — the rider just picks their dash from
 * the system dialog once — and then we remember that exact SSID here for direct reconnects.
 *
 * Everything is overridable in Settings for dashes that don't fit the defaults.
 */
class DashConfig private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val legacyPrefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val prefs = encryptedPrefsOrFallback()

    /** Broadest match across Tripper variants; rider-overridable. */
    var ssidPrefix: String
        get() = prefs.getString(KEY_PREFIX, DEFAULT_PREFIX) ?: DEFAULT_PREFIX
        set(v) = prefs.edit().putString(KEY_PREFIX, v).apply()

    /** The exact SSID once learned/entered. Empty = not yet known → discover by prefix. */
    var ssid: String
        get() = prefs.getString(KEY_SSID, "") ?: ""
        set(v) = prefs.edit().putString(KEY_SSID, v).apply()

    var password: String
        get() = prefs.getString(KEY_PASSWORD, DEFAULT_PASSWORD) ?: DEFAULT_PASSWORD
        set(v) = prefs.edit().putString(KEY_PASSWORD, v).apply()

    var layout: DashLayout
        get() = prefs.getString(KEY_LAYOUT, DashLayout.MAP_FIRST.name)
            ?.let { runCatching { DashLayout.valueOf(it) }.getOrNull() }
            ?: DashLayout.MAP_FIRST
        set(value) = prefs.edit().putString(KEY_LAYOUT, value.name).apply()

    /**
     * Perspective navigation view for the rendered dash map. This is deliberately
     * separate from heading-up: a rider can retain a conventional flat map while
     * still having the map rotate with their direction of travel.
     */
    var navigationTiltEnabled: Boolean
        get() = prefs.getBoolean(KEY_NAVIGATION_TILT, true)
        set(value) = prefs.edit().putBoolean(KEY_NAVIGATION_TILT, value).apply()

    /** Encoder frame-rate target. The renderer still drops to 2 fps while parked. */
    var streamFps: Int
        get() = prefs.getInt(KEY_STREAM_FPS, DEFAULT_STREAM_FPS).coerceIn(2, 8)
        set(value) = prefs.edit().putInt(KEY_STREAM_FPS, value.coerceIn(2, 8)).apply()

    /** H.264 target bitrate in kbps for the Tripper projection stream. */
    var streamBitrateKbps: Int
        get() = prefs.getInt(KEY_STREAM_BITRATE_KBPS, DEFAULT_STREAM_BITRATE_KBPS).coerceIn(100, 500)
        set(value) = prefs.edit().putInt(KEY_STREAM_BITRATE_KBPS, value.coerceIn(100, 500)).apply()

    /**
     * Rider-calibrated native navigation glyphs. A dash firmware may use different byte
     * values than the known defaults, so keep the mapping on the device that was tested.
     */
    var maneuverGlyphCodes: Map<ManeuverType, Int>
        get() = (prefs.getString(KEY_MANEUVER_GLYPH_CODES, null) ?: "")
            .split(',')
            .mapNotNull { entry ->
                val (type, code) = entry.split(':', limit = 2).let { it.getOrNull(0) to it.getOrNull(1) }
                val maneuver = type?.let { runCatching { ManeuverType.valueOf(it) }.getOrNull() }
                val glyph = code?.toIntOrNull()?.takeIf { it in 0..0xFF }
                if (maneuver != null && glyph != null) maneuver to glyph else null
            }
            .toMap()
        set(value) = prefs.edit().putString(
            KEY_MANEUVER_GLYPH_CODES,
            value.entries.joinToString(",") { "${it.key.name}:${it.value.coerceIn(0, 0xFF)}" },
        ).apply()

    /** Associates a tested native glyph with a route maneuver; null records no symbol. */
    fun setManeuverGlyph(code: Int, maneuver: ManeuverType?) {
        val sanitizedCode = code.coerceIn(0, 0xFF)
        val updated = maneuverGlyphCodes
            .filterValues { it != sanitizedCode }
            .toMutableMap()
        if (maneuver != null) updated[maneuver] = sanitizedCode
        maneuverGlyphCodes = updated
    }

    fun maneuverGlyphCode(maneuver: ManeuverType): Int =
        maneuverGlyphCodes[maneuver] ?: maneuver.defaultDashCode

    /** True until a specific dash has been identified — connect by prefix discovery. */
    val needsDiscovery: Boolean get() = ssid.isBlank()

    /** Forget the learned dash so the next connect re-runs prefix discovery. */
    fun forgetDash() { ssid = "" }

    private fun encryptedPrefsOrFallback(): SharedPreferences {
        return runCatching {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                appContext,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            ).also { migrateLegacyValues(it) }
        }.getOrElse { error ->
            DebugLog.w(TAG) { "Encrypted dash_config unavailable; using fallback prefs (${error.javaClass.simpleName})" }
            showEncryptionWarning()
            legacyPrefs
        }
    }

    private fun migrateLegacyValues(encryptedPrefs: SharedPreferences) {
        val legacyValues = listOf(KEY_PREFIX, KEY_SSID, KEY_PASSWORD, KEY_LAYOUT)
            .mapNotNull { key -> legacyPrefs.getString(key, null)?.let { key to it } }
        if (legacyValues.isEmpty()) return

        encryptedPrefs.edit().apply {
            legacyValues.forEach { (key, value) -> putString(key, value) }
        }.apply()
        legacyPrefs.edit().apply {
            legacyValues.forEach { (key, _) -> remove(key) }
        }.apply()
    }

    private fun showEncryptionWarning() {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(
                appContext,
                "Dash WiFi settings are using fallback storage.",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    companion object {
        private const val TAG = "DashConfig"
        private const val PREFS_NAME = "dash_config"
        private const val KEY_PREFIX   = "ssid_prefix"
        private const val KEY_SSID     = "ssid"
        private const val KEY_PASSWORD = "password"
        private const val KEY_LAYOUT = "layout"
        private const val KEY_NAVIGATION_TILT = "navigation_tilt"
        private const val KEY_STREAM_FPS = "stream_fps"
        private const val KEY_STREAM_BITRATE_KBPS = "stream_bitrate_kbps"
        private const val KEY_MANEUVER_GLYPH_CODES = "maneuver_glyph_codes"
        const val DEFAULT_PREFIX   = "RE_"
        const val DEFAULT_PASSWORD = "12345678"
        const val DEFAULT_STREAM_FPS = 4
        const val DEFAULT_STREAM_BITRATE_KBPS = 200

        @Volatile private var instance: DashConfig? = null
        fun get(context: Context): DashConfig =
            instance ?: synchronized(this) {
                instance ?: DashConfig(context).also { instance = it }
            }
    }
}
