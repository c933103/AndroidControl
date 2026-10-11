// SPDX-FileCopyrightText: 2026 AndroidControl contributors
// SPDX-License-Identifier: Apache-2.0
package org.androidcontrol.app.recording.integrations.scrcpy
import android.content.Context
import android.os.ParcelFileDescriptor
import java.io.FileDescriptor
import org.androidcontrol.app.recording.Fixture
enum class ScrcpyAudioCodec(val cliKey: String, val containerExtension: String, val defaultBitRate: Int) {
    OPUS("opus", ".ogg", 16000), AAC("aac", ".m4a", 32000);
    companion object { fun fromKey(key: String) = entries.first { it.cliKey == key } }
}
class ScrcpyAudioMuxer(fd: FileDescriptor, path: String) {
    val finalizedSuccessfully = true
    fun initialize(codec: ScrcpyAudioCodec) {}
    fun writePacket(packet: ScrcpyClient.AudioPacket, codec: ScrcpyAudioCodec) { Fixture.frames++ }
    fun close() {}
}
class ScrcpyClient(pipe: ParcelFileDescriptor, val codec: ScrcpyAudioCodec, val listener: AudioPacketListener) {
    class AudioPacket(val isConfigPacket: Boolean = false)
    interface AudioPacketListener {
        fun onMetadataReceived(codec: ScrcpyAudioCodec)
        fun onAudioPacket(packet: AudioPacket)
        fun onStreamEnd(error: String?)
    }
    fun start() { listener.onMetadataReceived(codec); listener.onAudioPacket(AudioPacket()) }
    fun close() {}
}
object ScrcpyConfig { fun getServerPath(context: Context) = "/fixture/never-executed" }
object ServerExtractor { fun ensureServerFile(context: Context, path: String) = true }
