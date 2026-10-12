// SPDX-FileCopyrightText: 2026 AndroidControl contributors
// SPDX-License-Identifier: Apache-2.0

package org.androidcontrol.app.recording.utils

import android.util.Log
import org.androidcontrol.app.BuildConfig

/**
 * Minimal, privacy-conscious AndroidControl logger for adapted ShizuCallRecorder sources.
 * Never log audio samples or call participants. Verbose diagnostic output is debug-only.
 */
object AppLogger {
    private const val TAG = "AndroidControl.Recorder"
    fun v(message: String, error: Throwable? = null) { if (BuildConfig.DEBUG) Log.v(TAG, message, error) }
    fun d(message: String, error: Throwable? = null) { if (BuildConfig.DEBUG) Log.d(TAG, message, error) }
    fun i(message: String, error: Throwable? = null) { if (BuildConfig.DEBUG) Log.i(TAG, message, error) }
    fun w(message: String, error: Throwable? = null) { Log.w(TAG, message, error) }
    fun e(message: String, error: Throwable? = null) { Log.e(TAG, message, error) }
}
