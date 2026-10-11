// SPDX-FileCopyrightText: 2026 AndroidControl contributors
// SPDX-License-Identifier: Apache-2.0

package org.androidcontrol.app.recording

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import org.androidcontrol.app.R
import org.androidcontrol.app.recording.integrations.scrcpy.ScrcpyAudioCodec
import org.androidcontrol.app.recording.integrations.scrcpy.ScrcpyAudioMuxer
import org.androidcontrol.app.recording.integrations.scrcpy.ScrcpyClient
import org.androidcontrol.app.recording.integrations.scrcpy.ScrcpyConfig
import org.androidcontrol.app.recording.integrations.scrcpy.ServerExtractor
import org.androidcontrol.app.recording.utils.AppLogger
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Foreground, user-approved phone-call audio recording.
 *
 * Audio flows only from the existing AndroidControl privileged user service to
 * the user-selected SAF document. There is no cloud transfer, interception of
 * other apps' communications, or collection of contact names/numbers.
 *
 * The user service owns the capture process; its Binder owner token ensures
 * capture stops if this app process crashes or is force-stopped.
 */
class CallRecordingService : Service() {
    enum class State { IDLE, STARTING, RECORDING, STOPPING, ERROR }

    companion object {
        const val ACTION_START = "org.androidcontrol.app.recording.START"
        const val ACTION_STOP = "org.androidcontrol.app.recording.STOP"
        const val ACTION_STATE_CHANGED = "org.androidcontrol.app.recording.STATE_CHANGED"
        private const val CHANNEL_ID = "androidcontrol_call_recording"
        private const val NOTIFICATION_ID = 32814

        @Volatile var state: State = State.IDLE
            private set
        @Volatile var lastError: String? = null
            private set

        val activeOrPreparing: Boolean
            get() = state == State.STARTING || state == State.RECORDING || state == State.STOPPING

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, CallRecordingService::class.java).setAction(ACTION_START)
            )
        }

        fun stop(context: Context) {
            // Dispatch to the already-running foreground service; it must finalize
            // MediaMuxer before leaving the foreground and stopping itself.
            context.startService(
                Intent(context, CallRecordingService::class.java).setAction(ACTION_STOP)
            )
        }
    }

    private class Session(
        val bridge: CallRecordingBridge,
        val outputUri: Uri,
        val output: ParcelFileDescriptor,
        val audioPipe: ParcelFileDescriptor,
        val muxer: ScrcpyAudioMuxer,
        val client: ScrcpyClient,
        val packets: AtomicInteger
    ) {
        val stopping = AtomicBoolean(false)
        lateinit var reader: Thread
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "AndroidControl-CallRecording").apply { isDaemon = false }
    }
    private var session: Session? = null
    private var completedStop = false
    private var consecutiveInactivePolls = 0

    // PHONE_STATE broadcasts can be missed, especially across OEM call state
    // transitions. Independently detect a finished call or permission revocation
    // and stop the privileged producer even if no IDLE broadcast was delivered.
    private val callGuard = object : Runnable {
        override fun run() {
            if (state != State.STARTING && state != State.RECORDING) return
            val permission = ContextCompat.checkSelfPermission(
                this@CallRecordingService, Manifest.permission.READ_PHONE_STATE
            ) == PackageManager.PERMISSION_GRANTED
            if (!recordingNotificationVisible()) {
                publish(State.STOPPING)
                worker.execute { finishSession("Recording notifications are blocked") }
                return
            }
            val offHook = permission && runCatching {
                getSystemService(TelephonyManager::class.java).callState ==
                    TelephonyManager.CALL_STATE_OFFHOOK
            }.getOrDefault(false)

            consecutiveInactivePolls = if (offHook) 0 else consecutiveInactivePolls + 1
            if (consecutiveInactivePolls >= 2) {
                publish(State.STOPPING)
                worker.execute { finishSession(null) }
                return
            }
            mainHandler.postDelayed(this, 2500)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.call_record_title), NotificationManager.IMPORTANCE_LOW)
        )
        publish(State.IDLE)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (activeOrPreparing) return START_NOT_STICKY
                completedStop = false
                consecutiveInactivePolls = 0
                publish(State.STARTING)
                try {
                    check(recordingNotificationVisible()) { "Allow recording notifications before beginning capture" }
                    showForeground()
                } catch (e: RuntimeException) {
                    AppLogger.e("Failed to enter recording foreground state", e)
                    errorAndStop(e.message ?: "Could not create a visible recording notification")
                    return START_NOT_STICKY
                }
                mainHandler.removeCallbacks(callGuard)
                mainHandler.postDelayed(callGuard, 2500)
                worker.execute { startSession() }
            }
            ACTION_STOP -> {
                if (!completedStop) {
                    publish(State.STOPPING)
                    worker.execute { finishSession(null) }
                }
            }
            else -> stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun recordingNotificationVisible(): Boolean = runCatching {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return@runCatching false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED) return@runCatching false
        val manager = getSystemService(NotificationManager::class.java) ?: return@runCatching false
        if (!manager.areNotificationsEnabled()) return@runCatching false
        val channel = manager.getNotificationChannel(CHANNEL_ID) ?: return@runCatching false
        if (channel.importance == NotificationManager.IMPORTANCE_NONE) return@runCatching false
        val groupId = channel.group
        groupId == null || manager.getNotificationChannelGroup(groupId)?.isBlocked == false
    }.getOrDefault(false)

    private fun showForeground() {
        val stop = Intent(this, CallRecordingService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(
            this, 1, stop, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val settingsIntent = Intent(this, CallRecordingActivity::class.java)
        val settingsPending = PendingIntent.getActivity(
            this, 2, settingsIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentIntent(settingsPending)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.call_record_title))
            .setContentText(getString(R.string.call_record_notification))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_media_pause, getString(R.string.call_record_stop), stopPending)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startSession() {
        if (session != null) return
        var bridge: CallRecordingBridge? = null
        var output: ParcelFileDescriptor? = null
        var audioPipe: ParcelFileDescriptor? = null
        var muxer: ScrcpyAudioMuxer? = null
        var outputDocument: DocumentFile? = null
        try {
            check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { "Call recording requires Android 12 or later" }
            check(ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) ==
                PackageManager.PERMISSION_GRANTED) { "Phone-state permission is missing" }
            val telephony = getSystemService(TelephonyManager::class.java)
            check(telephony.callState == TelephonyManager.CALL_STATE_OFFHOOK) {
                "No active cellular call to record"
            }
            check(recordingNotificationVisible()) { "Allow recording notifications before beginning capture" }
            val preferences = CallRecordingPreferences(this)
            check(preferences.isConfigured()) { "Accept the disclaimer and select a recording folder" }
            val folderUri = preferences.folder ?: error("Recording folder is not configured")
            check(contentResolver.persistedUriPermissions.any {
                it.uri == folderUri && it.isWritePermission
            }) { "The recording folder permission has been revoked" }
            val codec = ScrcpyAudioCodec.fromKey(preferences.codec)
            val source = preferences.source

            val serverPath = ScrcpyConfig.getServerPath(this)
            check(ServerExtractor.ensureServerFile(this, serverPath)) {
                "Bundled scrcpy server is missing or has failed its integrity check"
            }
            bridge = CallRecordingBridge.connect()

            val folder = DocumentFile.fromTreeUri(this, folderUri)
                ?.takeIf { it.isDirectory && it.canWrite() }
                ?: error("Selected folder cannot be written")
            val dateFormat = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.ROOT).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val extension = codec.containerExtension
            val mimeType = if (codec == ScrcpyAudioCodec.OPUS) "audio/ogg" else "audio/mp4"
            outputDocument = folder.createFile(mimeType, "Call-${dateFormat.format(Date())}-UTC$extension")
                ?: error("Could not create a recording document")
            output = contentResolver.openFileDescriptor(outputDocument.uri, "w")
                ?: error("Could not open the recording document for writing")
            muxer = ScrcpyAudioMuxer(output.fileDescriptor, outputDocument.uri.toString())
            // Preparation may wait for the provider or Binder. Recheck the live
            // indicator and a queued stop before starting the privileged producer.
            check(state == State.STARTING && recordingNotificationVisible()) {
                "Recording stopped or notifications became unavailable during setup"
            }
            audioPipe = bridge.remote.beginCallRecording(
                bridge.ownerToken, serverPath, source, codec.cliKey, codec.defaultBitRate
            ) ?: error("The privileged recorder could not capture call audio")

            val activeBridge = checkNotNull(bridge)
            val activeDocument = checkNotNull(outputDocument)
            val activeOutput = checkNotNull(output)
            val actualPipe = checkNotNull(audioPipe)
            val actualMuxer = checkNotNull(muxer)
            val actualCodec = codec
            val frames = AtomicInteger(0)
            val streamSession = AtomicReference<Session>()
            val client = ScrcpyClient(actualPipe, codec, object : ScrcpyClient.AudioPacketListener {
                private var detectedCodec = actualCodec

                override fun onMetadataReceived(codec: ScrcpyAudioCodec) {
                    check(codec == actualCodec) { "Audio codec does not match requested format" }
                    detectedCodec = codec
                    actualMuxer.initialize(codec)
                }

                override fun onAudioPacket(packet: ScrcpyClient.AudioPacket) {
                    actualMuxer.writePacket(packet, detectedCodec)
                    if (!packet.isConfigPacket) frames.incrementAndGet()
                }

                override fun onStreamEnd(error: String?) {
                    val current = streamSession.get()
                    if (current != null && !current.stopping.get()) {
                        worker.execute {
                            if (session === current) {
                                finishSession(error ?: "Audio capture stream ended unexpectedly")
                            }
                        }
                    }
                }
            })
            val running = Session(activeBridge, activeDocument.uri, activeOutput, actualPipe, actualMuxer, client, frames)
            running.reader = Thread({ client.start() }, "AndroidControl-AudioPipe")
            streamSession.set(running)
            session = running
            running.reader.start()
            publish(State.RECORDING)
        } catch (e: Exception) {
            AppLogger.e("Call recording could not start: ${e.message}", e)
            runCatching { audioPipe?.close() }
            runCatching { bridge?.let { it.remote.endCallRecording(it.ownerToken) } }
            runCatching { muxer?.close() }
            runCatching { output?.close() }
            runCatching { bridge?.close() }
            // Only delete documents that never entered a successful recording session.
            runCatching { outputDocument?.delete() }
            errorAndStop(e.message ?: "Recording initialization failed")
        }
    }

    private fun finishSession(streamError: String?) {
        val current = session
        if (current == null) {
            if (streamError != null) errorAndStop(streamError) else shutdown()
            return
        }
        if (!current.stopping.compareAndSet(false, true)) return
        var failure = streamError
        try {
            // Stop producer first, then drain buffered packets, then finalize the
            // container. Closing the pipe before draining would truncate audio.
            runCatching { current.bridge.remote.endCallRecording(current.bridge.ownerToken) }
                .onFailure { failure = it.message ?: "Privileged recorder disconnected" }
            current.reader.join(6000)
            if (current.reader.isAlive) {
                current.client.close()
                current.reader.join(1500)
            }
            if (current.reader.isAlive) failure = "Audio reader did not stop cleanly"
            if (current.packets.get() == 0) failure = "The recorder captured no audio frames"
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            failure = "Recording finalization interrupted"
        } finally {
            runCatching { current.client.close() }
            runCatching { current.audioPipe.close() }
            runCatching { current.muxer.close() }
                .onFailure { failure = it.message ?: "Could not finalize audio file" }
            if (!current.muxer.finalizedSuccessfully) {
                failure = failure ?: "Recording container was not finalized correctly"
            }
            runCatching { current.output.close() }
            current.bridge.close()
            session = null
        }
        if (failure != null) {
            errorAndStop(failure)
        } else {
            publish(State.IDLE)
            shutdown()
        }
    }

    private fun errorAndStop(message: String) {
        AppLogger.w("Call recording: $message")
        publish(State.ERROR, message)
        shutdown()
    }

    private fun shutdown() {
        mainHandler.removeCallbacks(callGuard)
        completedStop = true
        mainHandler.post {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun publish(newState: State, error: String? = null) {
        state = newState
        lastError = error
        sendBroadcast(Intent(ACTION_STATE_CHANGED).setPackage(packageName))
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(callGuard)
        // Cleanup must remain serialized behind an in-progress start operation.
        if (!worker.isShutdown) {
            worker.execute {
                if (session != null) finishSession("Recorder service stopped")
            }
            worker.shutdown()
        }
        super.onDestroy()
    }
}
