package org.androidcontrol.app.appops

import android.content.Context
import org.androidcontrol.app.R

/**
 * Presentation-only labels for stable Android AppOps identifiers.
 *
 * Legacy wording for these operations comes from 8enet/AppOpsX under MIT.
 * Do not send labels back to the Binder API or include them in backups or rules:
 * names must remain the platform's stable public identifiers.
 */
internal class AppOpsLabels(private val context: Context) {
    private val groupLabels = mutableMapOf<String, String>()
    private val permissionLabels = mutableMapOf<String, String>()

    fun operation(name: String): String {
        val resource = when (name) {
            "android:post_notification" -> R.string.appops_op_post_notification
            "android:play_audio" -> R.string.appops_op_play_audio
            "android:read_clipboard" -> R.string.appops_op_read_clipboard
            "android:write_clipboard" -> R.string.appops_op_write_clipboard
            "android:turn_screen_on" -> R.string.appops_op_turn_screen_on
            "android:run_in_background" -> R.string.appops_op_run_in_background
            "android:monitor_location" -> R.string.appops_op_monitor_location
            "android:monitor_location_high_power" -> R.string.appops_op_monitor_location_high_power
            "android:neighboring_cells" -> R.string.appops_op_neighboring_cells
            "android:audio_master_volume" -> R.string.appops_op_audio_master_volume
            "android:audio_voice_volume" -> R.string.appops_op_audio_voice_volume
            "android:audio_ring_volume" -> R.string.appops_op_audio_ring_volume
            "android:audio_media_volume" -> R.string.appops_op_audio_media_volume
            "android:audio_alarm_volume" -> R.string.appops_op_audio_alarm_volume
            "android:audio_notification_volume" -> R.string.appops_op_audio_notification_volume
            "android:audio_bluetooth_volume" -> R.string.appops_op_audio_bluetooth_volume
            "android:toast_window" -> R.string.appops_op_toast_window
            "android:activate_vpn" -> R.string.appops_op_activate_vpn
            "android:take_audio_focus" -> R.string.appops_op_take_audio_focus
            "android:access_mobile_network_data" -> R.string.appops_op_access_mobile_network_data
            "android:access_wifi_network_data" -> R.string.appops_op_access_wifi_network_data
            // Corrected spelling of the legacy AppOpsX resource key also covers the
            // old AOSP debug-name spelling, without affecting persisted identifiers.
            "android:access_moblie_network_data" -> R.string.appops_op_access_mobile_network_data
            else -> 0
        }
        return if (resource == 0) name.removePrefix("android:") else context.getString(resource)
    }

    fun operationWithIdentifier(name: String): String {
        val display = operation(name)
        return if (display == name || display == name.removePrefix("android:")) display else "$display · $name"
    }

    fun group(group: String): String {
        if (group == "Other") return context.getString(R.string.appops_group_other)
        if (group.isBlank()) return ""
        return groupLabels.getOrPut(group) {
            @Suppress("DEPRECATION")
            val label = runCatching {
                context.packageManager.getPermissionGroupInfo(group, 0)
                    .loadLabel(context.packageManager).toString()
            }.getOrNull()
            label?.takeIf { it.isNotBlank() } ?: group
        }
    }

    fun permission(permission: String): String {
        if (permission.isBlank()) return ""
        return permissionLabels.getOrPut(permission) {
            @Suppress("DEPRECATION")
            val label = runCatching {
                context.packageManager.getPermissionInfo(permission, 0)
                    .loadLabel(context.packageManager).toString()
            }.getOrNull()
            label?.takeIf { it.isNotBlank() } ?: permission
        }
    }

    fun groupWithIdentifier(raw: String): String = withIdentifier(raw, group(raw))
    fun permissionWithIdentifier(raw: String): String = withIdentifier(raw, permission(raw))

    private fun withIdentifier(raw: String, localized: String): String =
        if (raw.isBlank() || raw == localized) raw else "$localized · $raw"
}
