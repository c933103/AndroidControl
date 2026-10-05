package org.androidcontrol.app.appops

import org.json.JSONArray
import java.util.Locale

/** Stable public identifiers, including Android 7's non-public debug-name aliases. */
object AppOpsNames {
    fun normalize(name: String): String {
        if (!name.matches(Regex("[A-Z][A-Z0-9_]*"))) return name
        val suffix = when (name) {
            "RECEIVE_EMERGECY_SMS" -> "receive_emergency_broadcast"
            "MONITOR_HIGH_POWER_LOCATION" -> "monitor_location_high_power"
            "OP_READ_PHONE_STATE" -> "read_phone_state"
            "TURN_ON_SCREEN" -> "turn_screen_on"
            else -> name.lowercase(Locale.ROOT)
        }
        return "android:$suffix"
    }

    fun normalizeSelection(selected: JSONArray, available: Set<String>): JSONArray = JSONArray().apply {
        for (i in 0 until selected.length()) {
            val name = normalize(selected.getString(i))
            require(name in available) { "Unknown rule operation: $name" }
            put(name)
        }
    }
}
