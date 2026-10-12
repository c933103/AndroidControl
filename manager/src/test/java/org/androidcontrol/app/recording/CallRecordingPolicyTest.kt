// SPDX-FileCopyrightText: 2026 AndroidControl contributors
// SPDX-License-Identifier: Apache-2.0

package org.androidcontrol.app.recording

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Pure JVM tests: no real Android phone service or capture permissions required. */
class CallRecordingPolicyTest {
    @get:Rule val temp = TemporaryFolder()

    private fun file(): File {
        val root = temp.newFolder("user-emulated")
        return File(root, "Android/data/org.androidcontrol.app/files/scrcpy-4.0-server.jar").also {
            check(checkNotNull(it.parentFile).mkdirs())
            it.writeText("fixture: never execute")
        }
    }

    private fun check(
        path: String,
        calling: Int = 10304,
        manager: Int = 10304,
        source: String = "voice-call",
        codec: String = "opus",
        bitrate: Int = 16000
    ) = CallRecordingPolicy.validateRequest(calling, manager, path, source, codec, bitrate)

    @Test fun `valid caller and constrained parameters accepted`() {
        check(file().absolutePath)
    }

    @Test fun `root system and shell UIDs cannot impersonate manager`() {
        val p = file().absolutePath
        for (uid in listOf(0, 1000, 2000, 9999)) {
            assertThrows(SecurityException::class.java) { check(p, calling = uid, manager = uid) }
        }
    }

    @Test fun `a different installed application is rejected`() {
        val p = file().absolutePath
        assertThrows(SecurityException::class.java) { check(p, calling = 10305) }
    }

    @Test fun `unlisted source formats and bit rates rejected`() {
        val p = file().absolutePath
        assertThrows(IllegalArgumentException::class.java) { check(p, source = "playback") }
        assertThrows(IllegalArgumentException::class.java) { check(p, source = "mic") }
        assertThrows(IllegalArgumentException::class.java) { check(p, codec = "pcm") }
        assertThrows(IllegalArgumentException::class.java) { check(p, bitrate = -1) }
        assertThrows(IllegalArgumentException::class.java) { check(p, bitrate = 1_000_000) }
        check(p, source = "mic-voice-communication", codec = "aac", bitrate = 32000)
    }

    @Test fun `wrong paths and missing jars rejected`() {
        val p = file()
        val external = File(checkNotNull(checkNotNull(p.parentFile).parentFile), "scrcpy-4.0-server.jar")
        external.writeText("wrong")
        val evil = File(temp.root, "Android/data/other.app/files/scrcpy-4.0-server.jar")
        checkNotNull(evil.parentFile).mkdirs()
        evil.writeText("wrong")
        assertThrows(IllegalArgumentException::class.java) { check(external.absolutePath) }
        assertThrows(IllegalArgumentException::class.java) { check(evil.absolutePath) }
        assertThrows(IllegalArgumentException::class.java) { check(File(p.parentFile, "missing.jar").path) }
    }

    @Test fun `suffix spoofing path does not bypass canonical name restriction`() {
        val p = file()
        val relative = File(temp.root, "elsewhere/../Android/data/org.androidcontrol.app/files/scrcpy-4.0-server.jar")
        assertThrows(IllegalArgumentException::class.java) { check(relative.path) }
        assertTrue(p.isFile)
    }
}
