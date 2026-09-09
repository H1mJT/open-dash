package com.example.opendash.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class MapProvider { OPEN_FREE_MAP, GOOGLE_MAPS }

/**
 * The selected phone-map provider and, when needed, the rider's Google Maps Embed API key.
 *
 * The key is encrypted at rest because it is supplied by the rider, never logged, and only
 * inserted into the Google Maps Embed URL when Google Maps is the active provider.
 */
object MapProviderSettings {
    private const val PREFS = "map_provider"
    private const val KEY_PROVIDER = "provider"
    private const val KEY_GOOGLE_MAPS_KEY = "google_maps_embed_key"

    private lateinit var prefs: SharedPreferences
    private val _provider = MutableStateFlow(MapProvider.OPEN_FREE_MAP)
    val provider = _provider.asStateFlow()
    private val _hasGoogleMapsKey = MutableStateFlow(false)
    val hasGoogleMapsKey = _hasGoogleMapsKey.asStateFlow()

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        synchronized(this) {
            if (::prefs.isInitialized) return
            val app = context.applicationContext
            val masterKey = MasterKey.Builder(app)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            prefs = EncryptedSharedPreferences.create(
                app,
                PREFS,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            _provider.value = prefs.getString(KEY_PROVIDER, MapProvider.OPEN_FREE_MAP.name)
                ?.let { runCatching { MapProvider.valueOf(it) }.getOrNull() }
                ?: MapProvider.OPEN_FREE_MAP
            _hasGoogleMapsKey.value = !googleMapsKey().isNullOrBlank()
        }
    }

    fun select(context: Context, provider: MapProvider) {
        init(context)
        prefs.edit().putString(KEY_PROVIDER, provider.name).apply()
        _provider.value = provider
    }

    fun googleMapsKey(): String? = if (::prefs.isInitialized) {
        prefs.getString(KEY_GOOGLE_MAPS_KEY, null)?.trim()?.takeIf { it.isNotEmpty() }
    } else null

    fun saveGoogleMapsKey(context: Context, key: String) {
        init(context)
        val normalized = key.trim()
        prefs.edit().putString(KEY_GOOGLE_MAPS_KEY, normalized).apply()
        _hasGoogleMapsKey.value = normalized.isNotEmpty()
    }
}
