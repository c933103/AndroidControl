// SPDX-FileCopyrightText: 2026 AndroidControl contributors
// SPDX-License-Identifier: Apache-2.0
package org.androidcontrol.app.recording.integrations.scrcpy
import android.media.MediaMuxer
import java.io.FileDescriptor
class ScrcpyAudioCodec(val cliKey:String="aac") { val outputFormat=0; val mimeType=if(cliKey=="opus") "audio/opus" else "audio/mp4a-latm" }
object ScrcpyConfig { const val AUDIO_SAMPLE_RATE=48000; const val AUDIO_CHANNELS=2 }
class ScrcpyClient { data class AudioPacket(val data:ByteArray,val isConfigPacket:Boolean,val pts:Long=0) }
fun main(args:Array<String>) {
    val m=ScrcpyAudioMuxer(FileDescriptor(), "synthetic")
    val c=ScrcpyAudioCodec(if(args.single().startsWith("opus-")) "opus" else "aac")
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
        "opus-end-granule" -> {
            config()
            repeat(10) { m.writePacket(ScrcpyClient.AudioPacket(byteArrayOf(0x98.toByte(),1),false,1_000_000L+it*20_000L),c) }
            check(MediaMuxer.timestamps==(1..10).map { it*20_000L }) { "Opus end granules: ${MediaMuxer.timestamps}" }
            m.close()
        }
        "opus-all-toc-durations" -> {
            config(); var capture=0L
            val expected=mutableListOf<Long>()
            val durations=listOf(10000L,20000L,40000L,60000L,10000L,20000L,40000L,60000L,10000L,20000L,40000L,60000L,10000L,20000L,10000L,20000L,2500L,5000L,10000L,20000L,2500L,5000L,10000L,20000L,2500L,5000L,10000L,20000L,2500L,5000L,10000L,20000L)
            durations.forEachIndexed { index,duration ->
                m.writePacket(ScrcpyClient.AudioPacket(byteArrayOf((index shl 3).toByte(),1),false,capture),c)
                capture+=duration; expected.add(capture)
            }
            check(MediaMuxer.timestamps==expected); m.close()
        }
        "opus-multiple-frames" -> {
            config()
            // 10ms configuration: two CBR frames, two VBR frames, then three frames.
            val packets=listOf(byteArrayOf(1,1,1),byteArrayOf(2,1,1,1),byteArrayOf(3,3,1,1,1))
            packets.forEachIndexed { index,data -> m.writePacket(ScrcpyClient.AudioPacket(data,false,index*20_000L),c) }
            check(MediaMuxer.timestamps==listOf(20_000L,40_000L,70_000L)); m.close()
        }
        "opus-reset-shorter", "opus-duplicate-shorter" -> {
            config()
            m.writePacket(ScrcpyClient.AudioPacket(byteArrayOf(24,1),false,100_000),c) //60ms
            val next=if(args.single()=="opus-reset-shorter") 10_000L else 100_000L
            m.writePacket(ScrcpyClient.AudioPacket(byteArrayOf(8,1),false,next),c) //20ms
            m.writePacket(ScrcpyClient.AudioPacket(byteArrayOf(8,1),false,next+20_000),c)
            check(MediaMuxer.timestamps==listOf(60_000L,80_000L,100_000L)) { "Rebased Opus end granules: ${MediaMuxer.timestamps}" }
            m.close()
        }
        "opus-reuse-end" -> {
            config(); m.writePacket(ScrcpyClient.AudioPacket(byteArrayOf(24,1),false,100_000),c)
            m.close(); m.initialize(c); config()
            m.writePacket(ScrcpyClient.AudioPacket(byteArrayOf(8,1),false,500_000),c)
            check(MediaMuxer.timestamps==listOf(60_000L,20_000L)); m.close()
        }
        "opus-empty-packet", "opus-missing-count", "opus-zero-count", "opus-oversize-duration" -> {
            config()
            val data=when(args.single()) {
                "opus-empty-packet" -> byteArrayOf()
                "opus-missing-count" -> byteArrayOf(3)
                "opus-zero-count" -> byteArrayOf(3,0)
                else -> byteArrayOf(27,3)
            }
            check(runCatching { m.writePacket(ScrcpyClient.AudioPacket(data,false),c) }.isFailure)
            check(MediaMuxer.timestamps.isEmpty()); m.close(); check(!m.finalizedSuccessfully)
        }
        else -> error("Unknown scenario")
    }
    println("PASS ${args.single()}")
}
