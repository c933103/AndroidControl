// SPDX-FileCopyrightText: 2026 AndroidControl contributors
// SPDX-License-Identifier: Apache-2.0
package org.androidcontrol.app.recording
import android.app.NotificationManager
import android.app.NotificationChannelGroup
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

object Fixture {
    val notifications = NotificationManager()
    var notificationPermission = 0
    var hideDuringSetup = false
    val folder = Uri("content://fixture/folder")
    var codec = "opus"
    var provider = "seekable"
    var rejectProducer = false
    var drainMode: String? = null
    val producerStopped = java.util.concurrent.CountDownLatch(1)
    @Volatile var begins = 0
    @Volatile var ends = 0
    @Volatile var frames = 0
    @Volatile var muxerFailures = 0
    var foregrounds = 0
    var documents = 0
    var deletes = 0
    val modes = CopyOnWriteArrayList<String>()
    val outputs = CopyOnWriteArrayList<ParcelFileDescriptor>()
    val pipes = CopyOnWriteArrayList<ParcelFileDescriptor>()
}
class CallRecordingActivity
class CallRecordingPreferences(context: Context) {
    val folder = Fixture.folder
    val codec = Fixture.codec
    val source = "voice-call"
    fun isConfigured() = true
}
class CallRecordingBridge {
    val ownerToken = Any()
    val remote = Remote()
    fun close() {}
    companion object { fun connect() = CallRecordingBridge() }
    class Remote {
        fun beginCallRecording(owner: Any, path: String, source: String, codec: String, rate: Int): ParcelFileDescriptor {
            Fixture.begins++
            check(!Fixture.rejectProducer) { "controlled producer failure" }
            return ParcelFileDescriptor("pipe").also { Fixture.pipes.add(it) }
        }
        fun endCallRecording(owner: Any) { Fixture.ends++; Fixture.producerStopped.countDown() }
    }
}
fun eventually(condition: () -> Boolean) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
    while (!condition() && System.nanoTime() < deadline) Thread.sleep(5)
    check(condition()) { "service did not settle: ${CallRecordingService.state}" }
}
fun main(args: Array<String>) {
    val scenario = args.single()
    val service = CallRecordingService()
    service.onCreate()
    if (scenario.startsWith("drain-")) {
        drainScenario(service, scenario)
        return
    }
    if (scenario.startsWith("storage-")) {
        storageScenario(service, scenario)
        return
    }
    val blocked = scenario !in listOf("visible-api31", "visible-api35", "visible-group", "revoke-app", "revoke-channel", "revoke-group")
    when (scenario) {
        "blocked-app-api31" -> { Build.VERSION.SDK_INT = 31; Fixture.notifications.enabled = false }
        "blocked-app-api35" -> Fixture.notifications.enabled = false
        "blocked-channel" -> Fixture.notifications.channel!!.importance = 0
        "blocked-group" -> { Fixture.notifications.channel!!.group = "fixture"; Fixture.notifications.group = NotificationChannelGroup(true) }
        "missing-group" -> Fixture.notifications.channel!!.group = "missing"
        "missing-channel" -> Fixture.notifications.channel = null
        "query-failure" -> Fixture.notifications.fail = true
        "permission-denied" -> Fixture.notificationPermission = -1
        "hidden-during-setup" -> Fixture.hideDuringSetup = true
        "visible-api31" -> { Build.VERSION.SDK_INT = 31; Fixture.notificationPermission = -1 }
        "visible-api35" -> {}
        "visible-group", "revoke-group" -> { Fixture.notifications.channel!!.group = "fixture"; Fixture.notifications.group = NotificationChannelGroup(false) }
        "revoke-app", "revoke-channel" -> {}
        else -> error("Unknown scenario")
    }
    try {
        service.onStartCommand(Intent(CallRecordingService.ACTION_START), 0, 1)
        eventually { CallRecordingService.state != CallRecordingService.State.STARTING }
        if (blocked) {
            check(Fixture.begins == 0) { "capture began without visible notification" }
            check(CallRecordingService.state == CallRecordingService.State.ERROR)
            check(Fixture.outputs.all { it.closed }) { "output descriptor leaked" }
            check(Fixture.documents == Fixture.deletes) { "failed setup document retained" }
        } else {
            eventually { Fixture.frames == 1 }
            check(Fixture.begins == 1 && Fixture.foregrounds == 1)
            if (scenario.startsWith("revoke-")) {
                when (scenario) {
                    "revoke-app" -> Fixture.notifications.enabled = false
                    "revoke-channel" -> Fixture.notifications.channel!!.importance = 0
                    "revoke-group" -> Fixture.notifications.group!!.isBlocked = true
                }
                val guard = service.javaClass.getDeclaredField("callGuard").let { it.isAccessible = true; it.get(service) as Runnable }
                guard.run()
                eventually { !CallRecordingService.activeOrPreparing }
                check(Fixture.ends > 0) { "producer not stopped after visibility loss" }
                check(Fixture.outputs.all { it.closed } && Fixture.pipes.all { it.closed }) { "active descriptors not closed" }
                check(Fixture.deletes == 0) { "previously active output deleted" }
            } else {
                service.onStartCommand(Intent(CallRecordingService.ACTION_STOP), 0, 2)
                eventually { !CallRecordingService.activeOrPreparing }
            }
        }
        println("PASS $scenario: starts=${Fixture.begins}, stops=${Fixture.ends}, created=${Fixture.documents}, deleted=${Fixture.deletes}")
    } finally { service.onDestroy() }
}

fun storageScenario(service: CallRecordingService, scenario: String) {
    Fixture.codec = if (scenario == "storage-opus-stream") "opus" else "aac"
    when (scenario) {
        "storage-aac-seekable" -> Fixture.provider = "seekable"
        "storage-aac-w-streams", "storage-opus-stream" -> Fixture.provider = "w-streams"
        "storage-aac-no-rw" -> Fixture.provider = "no-rw"
        "storage-aac-bad-rw" -> Fixture.provider = "bad-rw"
        "storage-open-null" -> Fixture.provider = "null"
        "storage-producer-failure" -> Fixture.rejectProducer = true
        else -> error("Unknown storage scenario")
    }
    val failBeforeCapture = scenario in listOf("storage-aac-no-rw", "storage-aac-bad-rw", "storage-open-null")
    try {
        service.onStartCommand(Intent(CallRecordingService.ACTION_START), 0, 1)
        eventually { CallRecordingService.state != CallRecordingService.State.STARTING }
        eventually { Fixture.frames > 0 || Fixture.muxerFailures > 0 || CallRecordingService.state == CallRecordingService.State.ERROR }
        val expectedMode = if (Fixture.codec == "aac") "rw" else "w"
        if (!java.lang.Boolean.getBoolean("fixture.legacyModeControl")) {
            check(Fixture.modes == listOf(expectedMode)) { "Wrong output mode: ${Fixture.modes}" }
        }
        if (failBeforeCapture || Fixture.rejectProducer) {
            check(Fixture.begins == if (failBeforeCapture) 0 else 1) { "capture began with an unusable output" }
            check(CallRecordingService.state == CallRecordingService.State.ERROR)
            check(Fixture.outputs.all { it.closed }) { "failed output descriptor leaked" }
            check(Fixture.documents == 1 && Fixture.deletes == 1) { "failed new document was not deleted" }
        } else {
            check(Fixture.muxerFailures == 0) { "AAC muxer received a streaming descriptor" }
            eventually { Fixture.frames == 1 }
            check(Fixture.begins == 1)
            service.onStartCommand(Intent(CallRecordingService.ACTION_STOP), 0, 2)
            eventually { !CallRecordingService.activeOrPreparing }
            check(Fixture.outputs.all { it.closed } && Fixture.pipes.all { it.closed })
            check(Fixture.deletes == 0) { "successful output deleted" }
        }
        println("PASS $scenario: mode=${Fixture.modes}, starts=${Fixture.begins}, created=${Fixture.documents}, deleted=${Fixture.deletes}")
    } finally { service.onDestroy() }
}

fun drainScenario(service: CallRecordingService, scenario: String) {
    Fixture.drainMode = scenario
    try {
        service.onStartCommand(Intent(CallRecordingService.ACTION_START), 0, 1)
        eventually { CallRecordingService.state == CallRecordingService.State.RECORDING && Fixture.frames == 1 }
        service.onStartCommand(Intent(CallRecordingService.ACTION_STOP), 0, 2)
        eventually { !CallRecordingService.activeOrPreparing }
        if (scenario == "drain-error") {
            check(CallRecordingService.state == CallRecordingService.State.ERROR) { "Truncated drain reported ${CallRecordingService.state}" }
            check(CallRecordingService.lastError == "controlled drain I/O error")
        } else {
            check(scenario == "drain-eof")
            check(CallRecordingService.state == CallRecordingService.State.IDLE)
            check(CallRecordingService.lastError == null)
        }
        check(Fixture.ends == 1)
        check(Fixture.outputs.all { it.closed } && Fixture.pipes.all { it.closed })
        check(Fixture.deletes == 0) { "Previously active recording deleted" }
        println("PASS $scenario: state=${CallRecordingService.state}, error=${CallRecordingService.lastError}")
    } finally { service.onDestroy() }
}
