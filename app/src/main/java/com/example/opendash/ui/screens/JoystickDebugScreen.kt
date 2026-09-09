package com.example.opendash.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.opendash.data.JoystickAction
import com.example.opendash.data.JoystickEvent
import com.example.opendash.data.JoystickMappingStore
import com.example.opendash.ui.components.OpenDashCard
import com.example.opendash.viewmodel.DashViewModel
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JoystickDebugScreen(vm: DashViewModel, onBack: () -> Unit) {
    val mappings by vm.joystickMapping.collectAsState()
    val events by vm.joystickEvents.collectAsState()
    val capture by vm.captureAction.collectAsState()
    val conflict by vm.captureConflict.collectAsState()
    var selected by remember { mutableStateOf(JoystickAction.ZOOM_IN) }
    var expanded by remember { mutableStateOf(false) }

    conflict?.let { conflict ->
        AlertDialog(
            onDismissRequest = vm::cancelJoystickCapture,
            title = { Text("Replace mapping?") },
            text = { Text("${JoystickMappingStore.formatCode(conflict.existingCode)} is mapped to ${conflict.existingAction.label}. Move ${capture?.label} to ${JoystickMappingStore.formatCode(conflict.capturedCode)} and replace that mapping?") },
            confirmButton = { TextButton(onClick = vm::confirmJoystickReplacement) { Text("Replace") } },
            dismissButton = { TextButton(onClick = vm::cancelJoystickCapture) { Text("Cancel") } },
        )
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(18.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Joystick diagnostics", fontSize = 25.sp, color = MaterialTheme.colorScheme.onBackground)
            TextButton(onClick = onBack) { Text("Back") }
        }
        OpenDashCard(Modifier.fillMaxWidth(), padding = 14.dp) {
            val latest = events.firstOrNull()
            Text("Most recent event", color = MaterialTheme.colorScheme.onSurface)
            Text(latest?.summary() ?: "Waiting for a dash button event", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                OutlinedTextField(selected.label, onValueChange = {}, readOnly = true, label = { Text("App action") }, modifier = Modifier.menuAnchor().fillMaxWidth())
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    JoystickAction.entries.forEach { action -> DropdownMenuItem(text = { Text(action.label) }, onClick = { selected = action; expanded = false }) }
                }
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = { vm.beginJoystickCapture(selected) }, modifier = Modifier.fillMaxWidth()) {
                Text(if (capture == null) "Assign next joystick event" else "Listening for ${capture!!.label}…")
            }
            if (capture != null) TextButton(onClick = vm::cancelJoystickCapture) { Text("Cancel capture") }
            TextButton(onClick = vm::resetJoystickMappings) { Text("Reset to defaults") }
        }
        Spacer(Modifier.height(12.dp))
        Text("Current mappings", color = MaterialTheme.colorScheme.onBackground)
        mappings.entries.sortedBy { it.key }.forEach { (code, action) -> Text("${JoystickMappingStore.formatCode(code)}  •  ${action.label}", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Spacer(Modifier.height(12.dp))
        Text("Event history (latest 100)", color = MaterialTheme.colorScheme.onBackground)
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) { items(events, key = { it.timestampMs }) { event -> Text(event.summary(), modifier = Modifier.padding(vertical = 7.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) } }
    }
}

private fun JoystickEvent.summary(): String = "${DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(timestampMs))}  ${JoystickMappingStore.formatCode(code)}  • default: ${defaultAction?.label ?: "None"} • mapped: ${mappedAction?.label ?: "Unmapped"}"
