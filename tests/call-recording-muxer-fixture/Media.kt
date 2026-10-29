// SPDX-FileCopyrightText: 2026 AndroidControl contributors
// SPDX-License-Identifier: Apache-2.0
package android.media
import java.io.FileDescriptor
import java.nio.ByteBuffer
class MediaCodec { class BufferInfo { var offset=0; var size=0; var presentationTimeUs=0L } }
class MediaFormat {
    fun setString(k:String,v:String) {}
    fun setInteger(k:String,v:Int) {}
    fun setByteBuffer(k:String,v:ByteBuffer) {}
    companion object { const val KEY_MIME="mime"; const val KEY_SAMPLE_RATE="sample-rate"; const val KEY_CHANNEL_COUNT="channel-count" }
}
class MediaMuxer(fd:FileDescriptor, format:Int) {
    companion object { var failStop=false; var failWrite=false; var releases=0; var constructions=0 }
    init { constructions++ }
    fun addTrack(f:MediaFormat)=0
    fun start() {}
    fun writeSampleData(i:Int,b:ByteBuffer,info:MediaCodec.BufferInfo) { check(!failWrite) }
    fun stop() { check(!failStop) }
    fun release() { releases++ }
}
