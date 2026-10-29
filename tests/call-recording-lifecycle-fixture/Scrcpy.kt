// SPDX-FileCopyrightText: 2026 AndroidControl contributors
// SPDX-License-Identifier: Apache-2.0
package org.androidcontrol.app.recording.integrations.scrcpy
import java.io.File
import org.androidcontrol.app.recording.services.shell.Fixture
class ScrcpyAudioCodec { companion object { fun fromKey(key: String) = ScrcpyAudioCodec() } }
class ScrcpyAudioSource { companion object { fun fromKey(key: String) = ScrcpyAudioSource() } }
object ScrcpyConfig {
    const val SERVER_SOCKET_NAME_PREFIX = "fixture-"
    const val SERVER_MAIN_CLASS = "never.executed"
    fun getRandomSocketName() = "socket"
    fun buildServerArgs(name: String, source: ScrcpyAudioSource, codec: ScrcpyAudioCodec, rate: Int): List<String> {
        if (Fixture.failure == "arguments") throw IllegalArgumentException("controlled arguments failure")
        return emptyList()
    }
}
object ServerExtractor { fun verifyServerHash(file: File) = Fixture.failure != "hash" }
