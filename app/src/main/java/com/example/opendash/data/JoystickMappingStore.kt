package com.example.opendash.data

import android.content.Context
import com.example.opendash.util.DebugLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Persistent, rider-configurable mapping from a dash joystick byte to an app action. */
enum class JoystickAction(val label: String) {
    ZOOM_IN("Zoom in"),
    ZOOM_OUT("Zoom out"),
    RECENTER("Recenter map"),
    TOGGLE_HEADING_UP("Toggle heading-up"),
    NEXT_TRACK("Next track"),
    PREVIOUS_TRACK("Previous track"),
    ANSWER_CALL("Answer call"),
    REJECT_CALL("Reject call"),
    EXIT_NAVIGATION("Exit navigation"),
}

data class JoystickEvent(
    val code: Int,
    val timestampMs: Long,
    val defaultAction: JoystickAction?,
    val mappedAction: JoystickAction?,
)

sealed interface MappingAssignment {
    data object Saved : MappingAssignment
    data class Conflict(val existingAction: JoystickAction) : MappingAssignment
}

class JoystickMappingStore(context: Context, private val defaults: Map<Int, JoystickAction>) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _mappings = MutableStateFlow(load())
    val mappings = _mappings.asStateFlow()

    fun actionFor(code: Int): JoystickAction? = _mappings.value[code]

    fun assign(code: Int, action: JoystickAction, replaceConflict: Boolean = false): MappingAssignment {
        val existing = _mappings.value[code]
        val actionCode = _mappings.value.entries.firstOrNull { it.value == action && it.key != code }?.key
        if ((existing != null && existing != action) || actionCode != null) {
            if (!replaceConflict) return MappingAssignment.Conflict(existing ?: action)
        }
        // A mapping is one-to-one: replacing either side removes its previous partner.
        val updated = _mappings.value.toMutableMap().apply {
            entries.removeAll { (mappedCode, mappedAction) -> mappedCode == code || mappedAction == action }
            put(code, action)
        }
        save(updated)
        DebugLog.i(TAG) { "Mapped ${formatCode(code)} to ${action.name}${if (existing != null || actionCode != null) " (replaced existing mapping)" else ""}" }
        return MappingAssignment.Saved
    }

    fun resetToDefaults() {
        save(defaults)
        DebugLog.i(TAG) { "Reset joystick mappings to defaults" }
    }

    private fun load(): Map<Int, JoystickAction> = buildMap {
        prefs.all.forEach { (key, value) ->
            val code = key.removePrefix("code_").toIntOrNull()
            val action = (value as? String)?.let { runCatching { JoystickAction.valueOf(it) }.getOrNull() }
            if (key.startsWith("code_") && code != null && action != null) put(code, action)
        }
        if (isEmpty()) putAll(defaults)
    }

    private fun save(values: Map<Int, JoystickAction>) {
        prefs.edit().clear().apply {
            values.forEach { (code, action) -> putString("code_$code", action.name) }
        }.apply()
        _mappings.value = values.toMap()
    }

    companion object {
        private const val PREFS = "joystick_mappings"
        private const val TAG = "JoystickMapping"
        fun formatCode(code: Int) = "0x${code.toString(16).uppercase().padStart(2, '0')}"

    }
}
