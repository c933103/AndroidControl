// SPDX-FileCopyrightText: 2026 AndroidControl contributors
// SPDX-License-Identifier: Apache-2.0
package androidx.core.content
import android.content.Context
import android.content.Intent
import org.androidcontrol.app.recording.Fixture
object ContextCompat {
    fun checkSelfPermission(context: Context, permission: String) = if (permission == "notify") Fixture.notificationPermission else 0
    fun startForegroundService(context: Context, intent: Intent) {}
}
