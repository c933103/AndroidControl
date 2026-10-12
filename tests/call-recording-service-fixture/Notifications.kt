// SPDX-FileCopyrightText: 2026 AndroidControl contributors
// SPDX-License-Identifier: Apache-2.0
package androidx.core.app
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
object NotificationCompat {
    const val PRIORITY_LOW = 1
    class Builder(context: Context, channel: String) {
        fun setContentIntent(value: PendingIntent) = this
        fun setSmallIcon(value: Int) = this
        fun setContentTitle(value: String) = this
        fun setContentText(value: String) = this
        fun setOngoing(value: Boolean) = this
        fun setOnlyAlertOnce(value: Boolean) = this
        fun setPriority(value: Int) = this
        fun addAction(icon: Int, text: String, intent: PendingIntent) = this
        fun build() = Notification()
    }
}
