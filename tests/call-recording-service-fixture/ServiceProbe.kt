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
    @Volatile var begins = 0
    @Volatile var ends = 0
    @Volatile var frames = 0
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
            return ParcelFileDescriptor("pipe").also { Fixture.pipes.add(it) }
        }
        fun endCallRecording(owner: Any) { Fixture.ends++ }
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
