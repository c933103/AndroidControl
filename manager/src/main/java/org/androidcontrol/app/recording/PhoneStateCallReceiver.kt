// SPDX-FileCopyrightText: 2026 AndroidControl contributors
// SPDX-License-Identifier: Apache-2.0

package org.androidcontrol.app.recording

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import org.androidcontrol.app.recording.utils.AppLogger

/**
 * Only reacts to Android's protected PHONE_STATE broadcast.
 * Automatic recording is opt-in, and requires live READ_PHONE_STATE permission
 * and a persisted user-selected destination. Number/contact metadata is not read.
 */
class PhoneStateCallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        if (state == TelephonyManager.EXTRA_STATE_IDLE) {
            if (CallRecordingService.activeOrPreparing) {
                runCatching { CallRecordingService.stop(context) }
                    .onFailure { AppLogger.w("Could not send stop to call recorder", it) }
            }
            return
        }
        if (state != TelephonyManager.EXTRA_STATE_OFFHOOK) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val preferences = CallRecordingPreferences(context)
        if (!preferences.automatic || !preferences.isConfigured() ||
            CallRecordingService.activeOrPreparing
        ) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED) return
        try {
            CallRecordingService.start(context)
        } catch (e: RuntimeException) {
            // Android 12+ may refuse background FGS launches. Fail closed, never
            // request elevated permissions or evade lifecycle restrictions.
            AppLogger.w("Android refused background recorder startup", e)
        }
    }
}
