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

    fun operation(name: String, permissionName: String = ""): String {
        // Prefer Android's current, device-localized permission label where possible.
        // Permissionless operations fall back to historical AppOpsX labels.
        if (permissionName.isNotBlank()) {
            val platform = permission(permissionName)
            if (platform.isNotBlank() && platform != permissionName) return platform
        }
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

    fun operationWithIdentifier(name: String, permissionName: String = ""): String {
        val display = operation(name, permissionName)
        return if (display == name || display == name.removePrefix("android:")) display else "$display · $name"
    }

    fun group(group: String): String {
        if (group.isBlank()) return ""
        return groupLabels.getOrPut(group) {
            // Prefer the running platform's label, including for vendor groups.
            @Suppress("DEPRECATION")
            val platform = runCatching {
                context.packageManager.getPermissionGroupInfo(group, 0)
                    .loadLabel(context.packageManager).toString()
            }.getOrNull()
            if (!platform.isNullOrBlank() && platform != group) {
                platform
            } else {
                val resource = when (group) {
                    "Other", "Other operations" -> R.string.appops_group_other
                    "android.permission-group.ACTIVITY_RECOGNITION" -> R.string.appops_group_activity_recognition
                    "android.permission-group.SENSORS" -> R.string.appops_group_sensors
                    "android.permission-group.PHONE" -> R.string.appops_group_phone
                    "android.permission-group.SMS" -> R.string.appops_group_sms
                    "android.permission-group.CONTACTS" -> R.string.appops_group_contacts
                    "android.permission-group.CAMERA" -> R.string.appops_group_camera
                    "android.permission-group.LOCATION" -> R.string.appops_group_location
                    "android.permission-group.CALENDAR" -> R.string.appops_group_calendar
                    "android.permission-group.MICROPHONE" -> R.string.appops_group_microphone
                    "android.permission-group.STORAGE" -> R.string.appops_group_storage
                    "android.permission-group.CLIPBOARD" -> R.string.appops_group_clipboard
                    "android.permission-group.DEVICE_IDENTIFIERS" -> R.string.appops_group_device_identifiers
                    else -> 0
                }
                if (resource != 0) context.getString(resource) else group
            }
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
