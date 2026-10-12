// SPDX-FileCopyrightText: 2026 AndroidControl contributors
// SPDX-License-Identifier: Apache-2.0
package org.androidcontrol.app.recording
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.UserManager
import android.telephony.TelephonyManager

object Fixture {
    var preferenceReads = 0
    var automatic = true
    var configured = true
    var permission = 0
    var refuseStart = false
    var starts = 0
    var stops = 0
}
class CallRecordingPreferences(context: Context) {
    init {
        Fixture.preferenceReads++
        check(context.userManager?.isUserUnlocked == true) {
            "Credential encrypted preferences unavailable before unlock"
        }
    }
    val automatic get() = Fixture.automatic
    fun isConfigured() = Fixture.configured
}
object CallRecordingService {
    var activeOrPreparing = false
    fun start(context: Context) {
        Fixture.starts++
        if (Fixture.refuseStart) throw IllegalStateException("controlled background FGS rejection")
    }
    fun stop(context: Context) { Fixture.stops++ }
}
fun main(args: Array<String>) {
    val scenario = args.single()
    var unlocked = true
    var missingManager = false
    var action: String? = TelephonyManager.ACTION_PHONE_STATE_CHANGED
    var state: String? = TelephonyManager.EXTRA_STATE_OFFHOOK
    var expectedReads = 1
    var expectedStarts = 0
    var expectedStops = 0
    when (scenario) {
        "locked" -> { unlocked = false; expectedReads = 0 }
        "missing-manager" -> { missingManager = true; expectedReads = 0 }
        "unlocked" -> expectedStarts = 1
        "automatic-off" -> Fixture.automatic = false
        "unconfigured" -> Fixture.configured = false
        "active" -> CallRecordingService.activeOrPreparing = true
        "permission-denied" -> Fixture.permission = -1
        "background-refused" -> { Fixture.refuseStart = true; expectedStarts = 1 }
        "pre-android12" -> { Build.VERSION.SDK_INT = 30; expectedReads = 0 }
        "unrelated" -> { action = "fixture.UNRELATED"; expectedReads = 0 }
        "ringing" -> { state = "RINGING"; expectedReads = 0 }
        "missing-state" -> { state = null; expectedReads = 0 }
        "locked-idle-active" -> {
            unlocked = false; state = TelephonyManager.EXTRA_STATE_IDLE
            CallRecordingService.activeOrPreparing = true; expectedReads = 0; expectedStops = 1
        }
        "locked-idle-inactive" -> {
            unlocked = false; state = TelephonyManager.EXTRA_STATE_IDLE; expectedReads = 0
        }
        else -> error("Unknown scenario")
    }
    val context = Context(if (missingManager) null else UserManager(unlocked))
    PhoneStateCallReceiver().onReceive(context, Intent(action, state))
    check(Fixture.preferenceReads == expectedReads) { "unexpected credential preference access" }
    check(Fixture.starts == expectedStarts) { "unexpected start attempts" }
    check(Fixture.stops == expectedStops) { "unexpected stop attempts" }
    println("PASS $scenario: preferences=${Fixture.preferenceReads}, starts=${Fixture.starts}, stops=${Fixture.stops}")
}
