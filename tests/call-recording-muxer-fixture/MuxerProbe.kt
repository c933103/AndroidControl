// SPDX-FileCopyrightText: 2026 AndroidControl contributors
// SPDX-License-Identifier: Apache-2.0
package org.androidcontrol.app.recording.integrations.scrcpy
import android.media.MediaMuxer
import java.io.FileDescriptor
class ScrcpyAudioCodec { val cliKey="aac"; val outputFormat=0; val mimeType="audio/mp4a-latm" }
object ScrcpyConfig { const val AUDIO_SAMPLE_RATE=48000; const val AUDIO_CHANNELS=2 }
class ScrcpyClient { data class AudioPacket(val data:ByteArray,val isConfigPacket:Boolean,val pts:Long=0) }
fun main(args:Array<String>) {
    val m=ScrcpyAudioMuxer(FileDescriptor(), "synthetic")
    val c=ScrcpyAudioCodec()
    fun config() = m.writePacket(ScrcpyClient.AudioPacket(byteArrayOf(1),true),c)
    fun sample(pts:Long=0) = m.writePacket(ScrcpyClient.AudioPacket(byteArrayOf(2),false,pts),c)
    m.initialize(c)
    when(args.single()) {
        "sample-success" -> { config(); sample(); m.close(); check(m.finalizedSuccessfully) }
        "no-config" -> { sample(); m.close(); check(!m.finalizedSuccessfully) }
        "no-samples" -> { config(); m.close(); check(!m.finalizedSuccessfully) }
        "stop-failure" -> { config(); sample(); MediaMuxer.failStop=true; m.close(); check(!m.finalizedSuccessfully); check(MediaMuxer.releases==1) }
        "write-failure" -> { config(); MediaMuxer.failWrite=true; check(runCatching { sample() }.isFailure); m.close(); check(!m.finalizedSuccessfully) }
        "duplicate-close" -> { config(); sample(); m.close(); m.close(); check(m.finalizedSuccessfully); check(MediaMuxer.releases==1) }
        "duplicate-initialize" -> { config(); sample(); m.initialize(c); m.close(); check(m.finalizedSuccessfully); check(MediaMuxer.constructions==1) }
        "reuse-clears-result" -> { config(); sample(); m.close(); check(m.finalizedSuccessfully); m.initialize(c); check(!m.finalizedSuccessfully) }
        "reuse-empty-session" -> { config(); sample(); m.close(); m.initialize(c); config(); m.close(); check(!m.finalizedSuccessfully) }
        "queued-timestamps" -> { config(); repeat(10) { sample(1_000_000L+it*20_000L) }; check(MediaMuxer.timestamps==(0..9).map { it*20_000L }) { "Queued capture timestamps: ${MediaMuxer.timestamps}" }; m.close() }
        "capture-gap" -> { config(); sample(1_000_000); sample(3_000_000); check(MediaMuxer.timestamps==listOf(0L,2_000_000L)); m.close() }
        "capture-discontinuity" -> { config(); listOf(100_000L,120_000L,10_000L,30_000L,30_000L).forEach { sample(it) }; check(MediaMuxer.timestamps==listOf(0L,20_000L,20_001L,40_001L,40_002L)); m.close() }
        "reuse-timestamps" -> { config(); sample(100_000); sample(120_000); m.close(); m.initialize(c); config(); sample(500_000); sample(520_000); check(MediaMuxer.timestamps==listOf(0L,20_000L,0L,20_000L)); m.close() }
        else -> error("Unknown scenario")
    }
    println("PASS ${args.single()}")
}
