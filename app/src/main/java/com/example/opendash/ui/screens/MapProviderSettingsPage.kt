package com.example.opendash.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.opendash.data.MapProvider
import com.example.opendash.data.MapProviderSettings
import com.example.opendash.ui.components.BtnSize
import com.example.opendash.ui.components.BtnVariant
import com.example.opendash.ui.components.OpenDashBtn
import com.example.opendash.ui.components.OpenDashSegmented
import com.example.opendash.ui.theme.GeistFamily

/** Dedicated page keeps the map selector reachable from both More and Settings. */
@Composable
internal fun MapProviderSettingsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val provider by MapProviderSettings.provider.collectAsState()
    val hasGoogleMapsKey by MapProviderSettings.hasGoogleMapsKey.collectAsState()
    var googleMapsKey by remember { mutableStateOf(MapProviderSettings.googleMapsKey().orEmpty()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(androidx.compose.material3.MaterialTheme.colorScheme.background)
            .padding(18.dp),
    ) {
        ScreenHeader(title = "Map provider", onBack = onBack)
        Text(
            "Choose which provider renders maps in OpenDash.",
            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = GeistFamily,
            modifier = Modifier.padding(bottom = 16.dp),
        )
        OpenDashSegmented(
            options = listOf("OpenFreeMap", "Google Maps"),
            selected = if (provider == MapProvider.GOOGLE_MAPS) "Google Maps" else "OpenFreeMap",
            onSelect = { choice ->
                MapProviderSettings.select(
                    context,
                    if (choice == "Google Maps") MapProvider.GOOGLE_MAPS else MapProvider.OPEN_FREE_MAP,
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )
        if (provider == MapProvider.GOOGLE_MAPS) {
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = googleMapsKey,
                onValueChange = { googleMapsKey = it },
                label = { Text("Google Maps Embed API key") },
                supportingText = {
                    Text("Enable Maps Embed API and billing in your Google Cloud project. The key is encrypted on this device.")
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OpenDashBtn(
                if (hasGoogleMapsKey) "Save Google Maps key" else "Add Google Maps key",
                onClick = { MapProviderSettings.saveGoogleMapsKey(context, googleMapsKey) },
                variant = BtnVariant.Secondary,
                size = BtnSize.Md,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}
