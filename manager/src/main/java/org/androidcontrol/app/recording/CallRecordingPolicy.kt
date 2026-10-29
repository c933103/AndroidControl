// SPDX-FileCopyrightText: 2026 AndroidControl contributors
// SPDX-License-Identifier: Apache-2.0

package org.androidcontrol.app.recording

import java.io.File

/**
 * Security checks for the privileged audio-capture binder.
 *
 * The shell worker must never become an arbitrary app_process launcher for other
 * Shizuku clients or accept user-controlled commands/paths. No shell interpolation.
 */
internal object CallRecordingPolicy {
    const val SCRCPY_VERSION = "4.0"
    const val SCRCPY_SHA256 = "84924bd564a1eb6089c872c7521f968058977f91f5ff02514a8c74aff3210f3a"
    const val SCRCPY_ASSET = "scrcpy-server"
    const val MANAGER_PACKAGE = "org.androidcontrol.app"

    fun validateRequest(
        callingUid: Int,
        managerUid: Int,
        serverPath: String,
        audioSource: String,
        codec: String,
        bitRate: Int
    ) {
        if (callingUid < 10000 || callingUid != managerUid) {
            throw SecurityException("Call recording is restricted to AndroidControl")
        }
        require(audioSource == "voice-call" || audioSource == "mic-voice-communication") {
            "Unsupported call-recording audio source"
        }
        require(codec == "opus" || codec == "aac") { "Unsupported recording codec" }
        require(bitRate in 8000..128000) { "Recording bitrate outside supported bounds" }
        val canonical = File(serverPath).canonicalPath
        val relative = "/Android/data/$MANAGER_PACKAGE/"
        require(
            canonical.endsWith("${relative}files/scrcpy-$SCRCPY_VERSION-server.jar") ||
            canonical.endsWith("${relative}cache/scrcpy-$SCRCPY_VERSION-server.jar")
        ) { "Untrusted scrcpy server location" }
        require(File(canonical).isFile) { "scrcpy server missing" }
        // The shell pipeline additionally hashes the JAR before executing it.
    }
}
