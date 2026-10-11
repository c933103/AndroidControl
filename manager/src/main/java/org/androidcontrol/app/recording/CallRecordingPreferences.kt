// SPDX-FileCopyrightText: 2026 AndroidControl contributors
// SPDX-License-Identifier: Apache-2.0

package org.androidcontrol.app.recording

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

/** Persist only user-approved preferences. No contacts, dialled numbers or call audio in prefs. */
internal class CallRecordingPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("androidcontrol_call_recording", Context.MODE_PRIVATE)

    var termsAccepted: Boolean
        get() = prefs.getBoolean("terms_accepted", false)
        set(value) = prefs.edit().putBoolean("terms_accepted", value).apply()

    var automatic: Boolean
        get() = prefs.getBoolean("automatic", false)
        set(value) = prefs.edit().putBoolean("automatic", value).apply()

    var codec: String
        get() = prefs.getString("codec", "opus").takeIf { it == "opus" || it == "aac" } ?: "opus"
        set(value) { require(value == "opus" || value == "aac"); prefs.edit().putString("codec", value).apply() }

    var source: String
        get() = prefs.getString("source", "voice-call").takeIf {
            it == "voice-call" || it == "mic-voice-communication"
        } ?: "voice-call"
        set(value) {
            require(value == "voice-call" || value == "mic-voice-communication")
            prefs.edit().putString("source", value).apply()
        }

    var folder: Uri?
        get() = prefs.getString("folder_uri", null)?.let { runCatching { Uri.parse(it) }.getOrNull() }
            ?.takeIf { it.scheme == "content" && DocumentsContract.isTreeUri(it) }
        set(value) {
            require(value == null || (value.scheme == "content" && DocumentsContract.isTreeUri(value)))
            prefs.edit().putString("folder_uri", value?.toString()).apply()
        }

    fun isConfigured(): Boolean = termsAccepted && folder != null
}
