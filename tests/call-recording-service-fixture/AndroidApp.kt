// SPDX-FileCopyrightText: 2026 AndroidControl contributors
// SPDX-License-Identifier: Apache-2.0
package android.app
import android.content.Context
import android.content.Intent
import android.os.IBinder
import org.androidcontrol.app.recording.Fixture
open class Service : Context() {
    companion object { const val START_NOT_STICKY = 2; const val STOP_FOREGROUND_REMOVE = 1 }
    open fun onBind(intent: Intent?): IBinder? = null
    open fun onCreate() {}
    open fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = 0
    open fun onDestroy() {}
    fun startForeground(id: Int, notification: Notification) { Fixture.foregrounds++ }
    fun startForeground(id: Int, notification: Notification, type: Int) { Fixture.foregrounds++ }
    fun stopForeground(flags: Int) {}
    fun stopSelf(id: Int = 0) {}
}
class Notification
class NotificationChannel(val id: String, name: String, var importance: Int) { var group: String? = null }
class NotificationChannelGroup(var isBlocked: Boolean)
class NotificationManager {
    companion object { const val IMPORTANCE_LOW = 2; const val IMPORTANCE_NONE = 0 }
    var enabled = true
    var channel: NotificationChannel? = NotificationChannel("androidcontrol_call_recording", "fixture", IMPORTANCE_LOW)
    var group: NotificationChannelGroup? = null
    var fail = false
    fun createNotificationChannel(value: NotificationChannel) { if (channel == null) channel = value }
    fun areNotificationsEnabled(): Boolean { check(!fail); return enabled }
    fun getNotificationChannel(id: String) = channel
    fun getNotificationChannelGroup(id: String) = group
}
class PendingIntent {
    companion object {
        const val FLAG_UPDATE_CURRENT = 1; const val FLAG_IMMUTABLE = 2
        fun getService(context: Context, request: Int, intent: Intent, flags: Int) = PendingIntent()
        fun getActivity(context: Context, request: Int, intent: Intent, flags: Int) = PendingIntent()
    }
}
