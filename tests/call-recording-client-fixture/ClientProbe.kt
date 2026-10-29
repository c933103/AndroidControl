// SPDX-FileCopyrightText: 2026 AndroidControl contributors
// SPDX-License-Identifier: Apache-2.0
package org.androidcontrol.app.recording.integrations.scrcpy
import android.os.ParcelFileDescriptor
import java.io.*
enum class ScrcpyAudioCodec(val cliKey:String) {
    OPUS("opus");
    companion object { fun fromFourCC(code:Int):ScrcpyAudioCodec { check(code==0x6f707573);return OPUS } }
}
fun main(args:Array<String>) {
    val scenario=args.single()
    val bytes=ByteArrayOutputStream()
    val out=DataOutputStream(bytes)
    fun packet(pts:Long,payload:ByteArray=byteArrayOf(1),declared:Int=payload.size) { out.writeLong(pts);out.writeInt(declared);out.write(payload) }
    if (scenario=="partial-codec") out.writeShort(0x6f70) else {
        out.writeInt(0x6f707573)
        if(scenario=="missing-config") packet(100) else {
            packet(1L shl 62)
            when(scenario) {
                "clean-boundary", "listener-no-message" -> packet(20_000)
                "partial-header" -> out.writeByte(0)
                "partial-payload" -> packet(20_000,byteArrayOf(1),4)
                "complete-then-partial" -> { packet(20_000);out.writeByte(0) }
                "oversize" -> packet(20_000,byteArrayOf(),1_048_577)
                else -> error("Unknown scenario")
            }
        }
    }
    val file=java.nio.file.Files.createTempFile("synthetic-scrcpy-", ".bin").toFile()
    try {
        file.writeBytes(bytes.toByteArray())
        FileInputStream(file).use { input ->
            var ended=0;var error:String?=null;var frames=0
            val client=ScrcpyClient(ParcelFileDescriptor(input.fd),ScrcpyAudioCodec.OPUS,object:ScrcpyClient.AudioPacketListener {
                override fun onMetadataReceived(codec:ScrcpyAudioCodec) {}
                override fun onAudioPacket(packet:ScrcpyClient.AudioPacket) {
                    if(!packet.isConfigPacket) { if(scenario=="listener-no-message")throw IOException();frames++ }
                }
                override fun onStreamEnd(message:String?) { ended++;error=message }
            })
            client.start()
            check(ended==1) { "Expected one terminal callback, got $ended" }
            if(scenario=="clean-boundary") {check(error==null);check(frames==1)}
            else check(!error.isNullOrBlank()) { "Incomplete/failed stream reported clean EOF: $scenario, frames=$frames" }
            if(scenario=="complete-then-partial")check(frames==1)
            println("PASS $scenario: frames=$frames, error=${error!=null}")
        }
    } finally { file.delete() }
}
