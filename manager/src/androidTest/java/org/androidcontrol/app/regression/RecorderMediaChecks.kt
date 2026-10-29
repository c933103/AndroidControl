// SPDX-FileCopyrightText: 2026 AndroidControl contributors
// SPDX-License-Identifier: Apache-2.0
package org.androidcontrol.app.regression

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import org.androidcontrol.app.recording.integrations.scrcpy.ScrcpyAudioCodec
import org.androidcontrol.app.recording.integrations.scrcpy.ScrcpyAudioMuxer
import org.androidcontrol.app.recording.integrations.scrcpy.ScrcpyClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Installed Android media checks. Generates PCM; never opens AudioRecord or a telephone API. */
object RecorderMediaChecks {
    private const val RATE = 48000
    private const val CHANNELS = 2
    private const val FRAMES = RATE
    private const val TIMEOUT_MS = 15000L
    private lateinit var evidence: JSONObject
    private lateinit var evidenceFile: File
    private fun checkpoint(event: String, details: String = "") {
        evidence.getJSONArray("lifecycle").put(JSONObject().put("event", event)
            .put("elapsedMs", SystemClock.elapsedRealtime()).put("details", details))
        // Diagnostics must not interrupt native-resource cleanup.
        runCatching { evidenceFile.writeText(evidence.toString(2)) }
            .onFailure { android.util.Log.w("RecorderMediaChecks", "Checkpoint write failed", it) }
    }
    private data class Packet(val pts: Long, val config: Boolean, val bytes: ByteArray)
    private data class Encoded(val name: String, val packets: List<Packet>)
    private data class Outcome(val error: String?, val finalized: Boolean, val packets: Int)

    fun run(context: Context) {
        val directory = File(context.cacheDir, "recorder-media").apply { mkdirs() }
        directory.listFiles()?.forEach { check(it.delete()) }
        val report = JSONObject().put("sdk", android.os.Build.VERSION.SDK_INT)
            .put("input", "generated 440 Hz PCM, 48000 Hz stereo, one second")
            .put("scope", "installed MediaCodec, production parser/muxer, pipes; no call, microphone, service/Binder or physical-device acceptance")
        report.put("pid", android.os.Process.myPid()).put("lifecycle", JSONArray())
        evidence = report
        evidenceFile = File(directory, "report.json")
        checkpoint("run-start")
        val cases = JSONArray()
        report.put("cases", cases)
        try {
            for (codec in listOf(ScrcpyAudioCodec.AAC, ScrcpyAudioCodec.OPUS)) {
                val encoded = encode(codec)
                check(encoded.packets.count { !it.config } > 10)
                for (iteration in 1..2) {
                    val file = File(directory, "${codec.cliKey}-$iteration${codec.containerExtension}")
                    val outcome = pipeToMuxer(codec, encoded.packets, file, "valid")
                    check(outcome.error == null && outcome.finalized) { "$codec roundtrip: $outcome" }
                    cases.put(decode(codec, file).put("case", "${codec.cliKey}-roundtrip-$iteration")
                        .put("encoder", encoded.name).put("encodedPackets", outcome.packets)
                        .put("sha256", sha256(file)).put("bytes", file.length()))
                }
                for (mode in listOf("truncated", "empty", "mismatch")) {
                    val file = File(directory, "${codec.cliKey}-$mode${codec.containerExtension}")
                    val outcome = pipeToMuxer(codec, encoded.packets, file, mode)
                    if (mode == "empty") check(outcome.error == null && !outcome.finalized && outcome.packets == 0)
                    else check(outcome.error != null) { "$codec $mode incorrectly reported clean EOF" }
                    if (mode == "mismatch") check(outcome.error == "Synthetic codec mismatch" && !outcome.finalized && outcome.packets == 0)
                    if (mode == "truncated") check(outcome.error == "Audio stream was truncated" && outcome.packets > 0) { "No successful media preceded truncation" }
                    cases.put(JSONObject().put("case", "${codec.cliKey}-$mode")
                        .put("streamError", outcome.error ?: JSONObject.NULL)
                        .put("containerFinalized", outcome.finalized).put("mediaPackets", outcome.packets))
                }
            }
            checkNonSeekableAac()
            cases.put(JSONObject().put("case", "aac-unseekable-output-rejected"))
            report.put("passed", true)
        } catch (failure: Throwable) {
            report.put("passed", false).put("failure", failure.stackTraceToString())
            throw failure
        } finally {
            File(directory, "report.json").writeText(report.toString(2))
        }
    }

    private fun encode(codec: ScrcpyAudioCodec): Encoded {
        checkpoint("encoder-create-request", codec.mimeType)
        val encoder = MediaCodec.createEncoderByType(codec.mimeType)
        val name = encoder.name
        val identity = "$name/${System.identityHashCode(encoder)} ${codec.mimeType}"
        checkpoint("encoder-created", identity)
        var started = false
        val packets = mutableListOf<Packet>()
        try {
            val format = MediaFormat.createAudioFormat(codec.mimeType, RATE, CHANNELS).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, codec.defaultBitRate)
                setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                if (codec == ScrcpyAudioCodec.AAC) setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            }
            checkpoint("encoder-configure-request", identity)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            checkpoint("encoder-configured", identity)
            encoder.start(); started = true
            checkpoint("encoder-started", identity)
            var supplied = 0
            var inputEnded = false
            var outputEnded = false
            val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
            val info = MediaCodec.BufferInfo()
            while (!outputEnded) {
                check(SystemClock.elapsedRealtime() < deadline) { "Encoder timeout: $name" }
                if (!inputEnded) {
                    val index = encoder.dequeueInputBuffer(10000)
                    if (index >= 0) {
                        val buffer = checkNotNull(encoder.getInputBuffer(index)).apply { clear(); order(ByteOrder.LITTLE_ENDIAN) }
                        val count = minOf(960, FRAMES - supplied, buffer.remaining() / (CHANNELS * 2))
                        check(count > 0 || supplied == FRAMES)
                        val pts = 1_000_000L + supplied * 1_000_000L / RATE
                        repeat(count) { frame ->
                            val value = (6000 * sin(2 * PI * 440 * (supplied + frame) / RATE)).toInt().toShort()
                            repeat(CHANNELS) { buffer.putShort(value) }
                        }
                        val flags = if (count == 0) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                        encoder.queueInputBuffer(index, 0, count * CHANNELS * 2, pts, flags)
                        supplied += count
                        inputEnded = count == 0
                    }
                }
                val index = encoder.dequeueOutputBuffer(info, 10000)
                if (index >= 0) {
                    try {
                        if (info.size > 0) {
                            val buffer = checkNotNull(encoder.getOutputBuffer(index)).duplicate()
                            buffer.position(info.offset); buffer.limit(info.offset + info.size)
                            val bytes = ByteArray(info.size); buffer.get(bytes)
                            val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                            packets.add(Packet(info.presentationTimeUs, config,
                                if (config && codec == ScrcpyAudioCodec.OPUS) opusHeader(bytes) else bytes))
                        }
                        outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    } finally { encoder.releaseOutputBuffer(index, false) }
                }
            }
            check(packets.firstOrNull()?.config == true) { "Encoder did not emit initial CSD: $name" }
            check(packets.filter { !it.config }.all { it.pts >= 0 })
            val packetDigest = MessageDigest.getInstance("SHA-256")
            packets.forEach { packetDigest.update(it.bytes) }
            val payloadHash = packetDigest.digest().joinToString("") { "%02x".format(it) }
            checkpoint("encoder-output", "$identity packets=${packets.size} payloadSha256=$payloadHash")
            return Encoded(name, packets)
        } finally {
            if (started) {
                checkpoint("encoder-stop-request", identity)
                runCatching { encoder.stop() }.onFailure { checkpoint("encoder-stop-error", it.toString()) }
            }
            checkpoint("encoder-release-request", identity)
            encoder.release()
            checkpoint("encoder-released", identity)
        }
    }

    /** scrcpy v4.0 sends only the OpusHead section of Android's AOPUSHDR config. */
    private fun opusHeader(bytes: ByteArray): ByteArray {
        check(bytes.size >= 16 && String(bytes, 0, 8, Charsets.US_ASCII) == "AOPUSHDR")
        val length = ByteBuffer.wrap(bytes, 8, 8).order(ByteOrder.nativeOrder()).long
        check(length in 19..(bytes.size - 16).toLong())
        return bytes.copyOfRange(16, 16 + length.toInt())
    }

    private fun pipeToMuxer(codec: ScrcpyAudioCodec, encoded: List<Packet>, file: File, mode: String): Outcome {
        val pipe = ParcelFileDescriptor.createPipe()
        val output = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_CREATE or
            ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_READ_WRITE)
        val outputFd = output.fileDescriptor
        val muxer = ScrcpyAudioMuxer(outputFd, "synthetic-${codec.cliKey}")
        val streamError = AtomicReference<String?>()
        val readerFailure = AtomicReference<Throwable?>()
        val writerFailure = AtomicReference<Throwable?>()
        val writerMayClose = CountDownLatch(1)
        val firstMedia = CountDownLatch(1)
        var mediaPackets = 0
        var terminalCallbacks = 0
        val bytes = ByteArrayOutputStream().also { sink ->
            DataOutputStream(sink).use { data ->
                val wireCodec = if (mode == "mismatch") {
                    if (codec == ScrcpyAudioCodec.AAC) ScrcpyAudioCodec.OPUS else ScrcpyAudioCodec.AAC
                } else codec
                data.writeInt(wireCodec.codecFourCC)
                val selected = if (mode == "empty") encoded.filter { it.config } else encoded
                for (packet in selected) {
                    data.writeLong(if (packet.config) 1L shl 62 else packet.pts)
                    data.writeInt(packet.bytes.size); data.write(packet.bytes)
                }
                if (mode == "truncated") data.writeByte(0)
            }
        }.toByteArray()
        val client = ScrcpyClient(pipe[0], codec, object : ScrcpyClient.AudioPacketListener {
            override fun onMetadataReceived(received: ScrcpyAudioCodec) {
                check(received == codec) { "Synthetic codec mismatch" }
                muxer.initialize(received)
            }
            override fun onAudioPacket(packet: ScrcpyClient.AudioPacket) {
                muxer.writePacket(packet, codec)
                if (!packet.isConfigPacket) { mediaPackets++; firstMedia.countDown() }
            }
            override fun onStreamEnd(error: String?) { terminalCallbacks++; streamError.set(error) }
        })
        val writer = Thread({
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use {
                    it.write(bytes); it.flush()
                    if (mode == "valid") check(writerMayClose.await(10, TimeUnit.SECONDS)) { "Producer stop not signaled" }
                }
            } catch (failure: Throwable) { writerFailure.set(failure) }
        }, "SyntheticAudioWriter")
        val reader = Thread({ try { client.start() } catch (failure: Throwable) { readerFailure.set(failure) } }, "SyntheticAudioReader")
        try {
            writer.start(); reader.start()
            if (mode == "valid") {
                check(firstMedia.await(10, TimeUnit.SECONDS)) { "Reader did not receive synthetic media" }
                check(reader.isAlive) { "Reader ended before producer stop" }
                writerMayClose.countDown()
            }
            reader.join(10000); writer.join(10000)
            check(!reader.isAlive && !writer.isAlive) { "Pipe did not drain/stop" }
            readerFailure.get()?.let { throw it }
            // An early codec rejection may close the read end while the writer is still flushing.
            if (mode != "mismatch") writerFailure.get()?.let { throw it }
            check(terminalCallbacks == 1) { "Terminal callback count=$terminalCallbacks" }
            muxer.close(); muxer.close()
            return Outcome(streamError.get(), muxer.finalizedSuccessfully, mediaPackets)
        } finally {
            writerMayClose.countDown()
            runCatching { pipe[1].close() }; runCatching { client.close() }; runCatching { pipe[0].close() }
            reader.join(2000); writer.join(2000)
            runCatching { muxer.close() }; output.close()
            check(!reader.isAlive && !writer.isAlive) { "Synthetic media thread leaked" }
            check(!outputFd.valid()) { "Output descriptor remained open" }
        }
    }

    private fun decode(codec: ScrcpyAudioCodec, file: File): JSONObject {
        checkpoint("decode-file", "${file.name} mime=${codec.mimeType} bytes=${file.length()} sha256=${sha256(file)}")
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var started = false
        var identity = "not-created"
        try {
            extractor.setDataSource(file.absolutePath)
            check(extractor.trackCount == 1)
            val format = extractor.getTrackFormat(0)
            check(format.getString(MediaFormat.KEY_MIME) == codec.mimeType)
            check(format.getInteger(MediaFormat.KEY_SAMPLE_RATE) == RATE)
            check(format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) == CHANNELS)
            extractor.selectTrack(0)
            val timestamps = mutableListOf<Long>()
            while (extractor.sampleTime >= 0) {
                timestamps.add(extractor.sampleTime)
                if (!extractor.advance()) break
            }
            check(timestamps.size > 10)
            check(timestamps.zipWithNext().all { (a, b) -> b > a }) { "Non-monotonic container PTS: $timestamps" }
            val span = timestamps.last() - timestamps.first()
            check(span in 750_000L..1_250_000L) { "Queued capture timeline compressed: $span us" }
            extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            checkpoint("decoder-create-request", "${file.name} ${codec.mimeType}")
            decoder = MediaCodec.createDecoderByType(codec.mimeType)
            val name = decoder.name
            identity = "$name/${System.identityHashCode(decoder)} ${codec.mimeType} ${file.name}"
            checkpoint("decoder-created", identity)
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            checkpoint("decoder-configure-request", identity)
            decoder.configure(format, null, null, 0)
            checkpoint("decoder-configured", identity)
            decoder.start(); started = true
            checkpoint("decoder-started", identity)
            var inputEnded = false
            var outputEnded = false
            var samples = 0L
            var square = 0.0
            var sine = 0.0
            var cosine = 0.0
            val info = MediaCodec.BufferInfo()
            val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
            while (!outputEnded) {
                check(SystemClock.elapsedRealtime() < deadline) { "Decoder timeout: $name" }
                if (!inputEnded) {
                    val index = decoder.dequeueInputBuffer(10000)
                    if (index >= 0) {
                        val buffer = checkNotNull(decoder.getInputBuffer(index))
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputEnded = true
                        } else {
                            decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0); extractor.advance()
                        }
                    }
                }
                val index = decoder.dequeueOutputBuffer(info, 10000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val decodedFormat = decoder.outputFormat
                    check(decodedFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) == CHANNELS)
                    check(decodedFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) == RATE)
                    if (decodedFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) check(decodedFormat.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_16BIT)
                } else if (index >= 0) {
                    try {
                        if (info.size > 0) {
                            val buffer = checkNotNull(decoder.getOutputBuffer(index)).duplicate().order(ByteOrder.LITTLE_ENDIAN)
                            buffer.position(info.offset); buffer.limit(info.offset + info.size)
                            check(info.size % (CHANNELS * 2) == 0)
                            while (buffer.remaining() >= CHANNELS * 2) {
                                val value = buffer.short.toDouble(); buffer.short
                                val angle = 2 * PI * 440 * samples / RATE
                                square += value * value; sine += value * sin(angle); cosine += value * cos(angle)
                                samples++
                            }
                        }
                        outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    } finally { decoder.releaseOutputBuffer(index, false) }
                }
            }
            check(samples in 36_000L..62_400L) { "Decoded duration out of bounds: $samples" }
            val rms = sqrt(square / samples)
            val amplitude = 2 * sqrt(sine * sine + cosine * cosine) / samples
            check(rms > 500 && amplitude > 1000 && amplitude * amplitude / (2 * rms * rms) > 0.2) {
                "Synthetic tone not recovered: samples=$samples rms=$rms amplitude=$amplitude"
            }
            return JSONObject().put("decoder", name).put("containerSamples", timestamps.size)
                .put("timestampSpanUs", span).put("decodedFrames", samples).put("rms", rms).put("toneAmplitude", amplitude)
        } finally {
            if (started) {
                checkpoint("decoder-stop-request", identity)
                runCatching { decoder?.stop() }.onFailure { checkpoint("decoder-stop-error", it.toString()) }
            }
            checkpoint("decoder-release-request", identity)
            decoder?.release()
            checkpoint("decoder-released", identity)
            extractor.release()
        }
    }

    private fun checkNonSeekableAac() {
        val pipe = ParcelFileDescriptor.createPipe()
        val muxer = ScrcpyAudioMuxer(pipe[1].fileDescriptor, "synthetic-unseekable")
        try {
            check(runCatching { muxer.initialize(ScrcpyAudioCodec.AAC) }.isFailure) { "AAC accepted non-seekable pipe output" }
            muxer.close(); check(!muxer.finalizedSuccessfully)
        } finally { runCatching { muxer.close() }; pipe.forEach { it.close() } }
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }
}
