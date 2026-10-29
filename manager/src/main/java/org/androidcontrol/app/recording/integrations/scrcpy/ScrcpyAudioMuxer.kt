/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  SPDX-FileCopyrightText: 2026-present kitsumed (Med)
 *  SPDX-License-Identifier: LicenseRef-ShizuCallRecorder-GPL3-Section7
 *  SPDX-FileComment: GPL-3.0-or-later plus upstream Section 7 terms; see LICENSES/LicenseRef-ShizuCallRecorder-GPL3-Section7.txt
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full GPL-3.0 and Section 7 terms are in LICENSES/LicenseRef-ShizuCallRecorder-GPL3-Section7.txt.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

// Modified for integration with AndroidControl (2026-10-11); upstream revision 41d1dc7.
package org.androidcontrol.app.recording.integrations.scrcpy

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import org.androidcontrol.app.recording.utils.AppLogger
import java.io.Closeable
import java.nio.ByteBuffer

/**
 * Writes scrcpy audio packets into an OGG (Opus) or MPEG-4/M4A (AAC) container via [MediaMuxer].
 *
 * You MUST call [close]: [MediaMuxer.stop] writes the container index (MP4 "moov" atom /
 * OGG final page flush). Without it the file may be unplayable by some audio players.
 *
 * Packet flow:
 *   CONFIG packet -> addAudioTrack() (one-time muxer setup with CSD)
 *   Audio packet  -> writeSampleData() with normalized capture PTS
 *
 * Timestamp strategy: normalize capture timestamps from scrcpy to a zero-based
 * session timeline. Reader scheduling and SAF backpressure must not compress audio.
 * Forward source gaps are preserved; duplicate/backward timestamps start a new
 * monotonic segment one microsecond after the last sample.
 *
 * Maintainer's guide — when updating scrcpy-server, check the server files below to see if
 * CSD formats or MIME types have changed:
 *   https://github.com/Genymobile/scrcpy/blob/master/server/src/main/java/com/genymobile/scrcpy/device/Streamer.java
 *   https://github.com/Genymobile/scrcpy/blob/master/server/src/main/java/com/genymobile/scrcpy/audio/AudioCodec.java
 *
 * @param outputFileDescriptor Writable [java.io.FileDescriptor] for the output file (from SAF).
 * @param outputDisplayPath    Human-readable path for logs (e.g. "Recordings/call_….ogg").
 */
class ScrcpyAudioMuxer(
    private val outputFileDescriptor: java.io.FileDescriptor,
    private val outputDisplayPath: String
) : Closeable {

    // Muxer state

    /** The underlying [MediaMuxer] instance; null until [initialize] is called. */
    private var muxer: MediaMuxer? = null

    /**
     * The audio track index assigned by [MediaMuxer.addTrack].
     * -1 means the track has not been added yet (waiting for the config packet).
     */
    private var audioTrackIndex = -1

    /**
     * True after [MediaMuxer.start] has been called successfully.
     * Guards [writePacket] so we never write before the muxer is started.
     */
    private var isMuxerStarted = false

    // AndroidControl adaptation: never report a recording as saved merely because
    // scrcpy delivered frames. MediaMuxer must write samples AND finalize its
    // output container successfully.
    private var samplesWritten = 0
    var finalizedSuccessfully: Boolean = false
        private set

    /** Previous capture timestamp, or -1 before the first sample of this session. */
    private var lastPacketPtsUs: Long = -1L

    /** Previous normalized output timestamp; kept strictly increasing. */
    private var lastWrittenPtsUs: Long = -1L

    /**
     * Creates the [MediaMuxer] instance for the container format matching [codec].
     * Safe to call multiple times; subsequent calls after the first are ignored.
     *
     * @param codec The [ScrcpyAudioCodec] enum entry whose [ScrcpyAudioCodec.outputFormat]
     *              determines the output container format (OGG for Opus, MPEG-4 for AAC, etc.).
     */
    fun initialize(codec: ScrcpyAudioCodec) {
        if (muxer != null) return // Already initialised; ignore duplicate calls.

        // A closed instance may be initialized again. Its saved-result flag and
        // sample count belong only to the new output session, never the previous one.
        finalizedSuccessfully = false
        samplesWritten = 0
        AppLogger.d( "Initialising muxer: codec=${codec.cliKey} format=${codec.outputFormat} path='$outputDisplayPath'")
        muxer = MediaMuxer(outputFileDescriptor, codec.outputFormat)
    }

    /**
     * Processes a single audio packet received from [ScrcpyClient].
     *
     * Config packets (CSD) are used to set up the muxer track; regular frames are written
     * as audio samples with normalized capture PTS.  Packets arriving before the muxer
     * track is ready are silently dropped (this should not happen in normal operation because
     * scrcpy always sends the config packet first).
     *
     * @param packet  The decoded packet from [ScrcpyClient.AudioPacketListener.onAudioPacket].
     * @param codec   The active [ScrcpyAudioCodec], used to determine the MIME type for the track.
     */
    fun writePacket(packet: ScrcpyClient.AudioPacket, codec: ScrcpyAudioCodec) {
        if (packet.isConfigPacket) {
            // Config packet: set up the audio track if we haven't yet.
            if (audioTrackIndex < 0) {
                addAudioTrack(configData = packet.data, codec = codec)
            }
            // Config packets carry no audio data; do not write them as samples.
            return
        }

        // Guard: only write if the muxer is fully started and we have a valid track.
        if (!isMuxerStarted || audioTrackIndex < 0) {
            AppLogger.w( "writePacket(): muxer not ready – dropping frame")
            return
        }

        require(packet.pts >= 0) { "Invalid negative audio capture timestamp" }
        // Consumption can be delayed or bursty. Preserve the producer's timing,
        // not the wall clock at which a queued packet happens to reach the writer.
        // A source reset/duplicate starts a new segment; subsequent deltas retain
        // their capture spacing rather than clamping until the old clock catches up.
        val normalizedPtsUs = if (lastPacketPtsUs < 0) 0L else {
            val delta = if (packet.pts > lastPacketPtsUs) packet.pts - lastPacketPtsUs else 1L
            Math.addExact(lastWrittenPtsUs, delta)
        }

        val bufferInfo = MediaCodec.BufferInfo().apply {
            offset             = 0
            size               = packet.data.size
            presentationTimeUs = normalizedPtsUs
        }

        // ByteBuffer.wrap() creates a view over the existing array with no copy.
        checkNotNull(muxer).writeSampleData(audioTrackIndex, ByteBuffer.wrap(packet.data), bufferInfo)
        lastPacketPtsUs = packet.pts
        lastWrittenPtsUs = normalizedPtsUs
        samplesWritten++
    }

    /**
     * Finalises the output file and releases the muxer.
     *
     * IMPORTANT: [MediaMuxer.stop] writes the container index (moov/EBML).  Without this call
     * the output file is structurally incomplete and some media players will refuse to open it.
     * [close] must therefore be called even if only a single audio frame was written.
     */
    override fun close() {
        if (muxer == null) return
        finalizedSuccessfully = false
        if (isMuxerStarted) {
            AppLogger.d( "Finalising muxer for '$outputDisplayPath'")
            try {
                muxer?.stop()
                finalizedSuccessfully = samplesWritten > 0
            } catch (e: Exception) {
                AppLogger.e("Muxer stop failed (file is incomplete): ${e.message}", e)
            }
        }
        runCatching { muxer?.release() }.onFailure { e ->
            AppLogger.e("Muxer release failed: ${e.message}", e)
        }
        muxer                  = null
        isMuxerStarted         = false
        audioTrackIndex        = -1
        lastPacketPtsUs        = -1L
        lastWrittenPtsUs       = -1L
        AppLogger.d( "Muxer closed")
    }

    // Private helpers

    /**
     * Configures the muxer's audio track using the codec-specific data from the config packet,
     * then calls [MediaMuxer.start] to begin accepting sample data.
     *
     * @param configData  Raw CSD bytes from the scrcpy config packet (may be empty for Opus).
     * @param codec       The [ScrcpyAudioCodec] used to select the MIME type.
     */
    private fun addAudioTrack(configData: ByteArray, codec: ScrcpyAudioCodec) {
        val csdBytes = configData.takeIf { it.isNotEmpty() }
        if (csdBytes == null) {
            AppLogger.e( "Empty config data received. Cannot initialize audio track.")
            return
        }

        val mediaFormat = MediaFormat().apply {
            setString(MediaFormat.KEY_MIME, codec.mimeType)
            setInteger(MediaFormat.KEY_SAMPLE_RATE, ScrcpyConfig.AUDIO_SAMPLE_RATE)
            setInteger(MediaFormat.KEY_CHANNEL_COUNT, ScrcpyConfig.AUDIO_CHANNELS)
            // "csd-0" is the standard key for codec-specific data buffer 0.
            setByteBuffer("csd-0", ByteBuffer.wrap(csdBytes))
        }

        audioTrackIndex = muxer?.addTrack(mediaFormat) ?: -1
        if (audioTrackIndex < 0) {
            AppLogger.e( "Failed to add audio track (addTrack returned $audioTrackIndex)")
            return
        }

        muxer?.start()
        isMuxerStarted = audioTrackIndex >= 0
        AppLogger.d( "Audio track added (index=$audioTrackIndex mime=${codec.mimeType}) – muxer started")
    }
}
