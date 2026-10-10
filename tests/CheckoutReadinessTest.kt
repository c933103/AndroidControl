package org.androidcontrol.app.regression

import java.io.File

private const val COMPONENT = "org.androidcontrol.regression.checkout/org.androidcontrol.regression.checkout.CheckoutActivity"

private fun inputDump(rotation: Int = 1): String {
    val matrix = when (rotation) {
        0 -> "1 0 0\n0 1 0\n0 0 1"
        1 -> "0 1 0\n-1 0 320\n0 0 1"
        2 -> "-1 0 320\n0 -1 640\n0 0 1"
        else -> "0 -1 640\n1 0 0\n0 0 1"
    }
    val size = if (rotation % 2 == 0) "320x640" else "640x320"
    return """
        DispatchEnabled: true
        DispatchFrozen: false
        FocusedWindows:
          displayId=0, name='abc $COMPONENT'
        FocusRequests:
        Display: 0
          logicalSize=$size
          transform TEST
        $matrix
          Windows:
          0: name=abc $COMPONENT, id=741, displayId=0, inputConfig=SENSITIVE_FOR_PRIVACY, alpha=1, frame=[24,0][316,640], globalScale=1
    """.trimIndent()
}

private fun snapshot(sequence: Long = 1, sampleMs: Long = 1000, generation: Long = 500) =
    CheckoutReadySnapshot(0, true, true, 320, 248, generation, sequence, sampleMs, 1, 640, 320,
        CheckoutRect(0, 4, 640, 296), CheckoutRect(16, 224, 624, 272))

fun main(args: Array<String>) {
    var checks = 0
    fun verify(value: Boolean, label: String) { check(value) { label }; checks++ }
    val input = checkNotNull(CheckoutInputState.parse(inputDump()))
    verify(input.window == CheckoutRect(0, 4, 640, 296), "native window frame must transform to logical coordinates")
    val diagnostic = CheckoutInputState.numericEvidence(inputDump())
    verify("24,0,316,640" in diagnostic && "SENSITIVE_FOR_PRIVACY" in diagnostic,
        "rejected-format diagnostic retains numeric frame and allowed flags")
    verify("org.androidcontrol" !in diagnostic && "name=" !in diagnostic,
        "rejected-format diagnostic excludes component/window names")
    val staleWindow = "0: name=stale $COMPONENT, id=740, displayId=0, inputConfig=SENSITIVE_FOR_PRIVACY, alpha=1, frame=[0,0][10,10], globalScale=1"
    val twoWindows = inputDump().replace("0: name=abc", "$staleWindow\n1: name=abc")
    verify(CheckoutInputState.parse(twoWindows) == input, "select exact focused token when stale same-component window comes first")
    val focusedElsewhere = twoWindows.replace("frame=[0,0][10,10]", "frame=[24,0][316,640]")
        .replace("1: name=abc $COMPONENT, id=741, displayId=0, inputConfig=SENSITIVE_FOR_PRIVACY, alpha=1, frame=[24,0][316,640]",
            "1: name=abc $COMPONENT, id=741, displayId=0, inputConfig=SENSITIVE_FOR_PRIVACY, alpha=1, frame=[0,0][10,10]")
    verify(!CheckoutReadiness().observe(snapshot(), CheckoutInputState.parse(focusedElsewhere), 1000),
        "matching stale frame cannot stand in for the distinct focused window's frame")
    verify(!CheckoutRect(Int.MIN_VALUE, 0, 100, 100).near(CheckoutRect(Int.MAX_VALUE, 0, 100, 100)),
        "coordinate differences cannot overflow into a near match")
    for (rotation in 0..3) verify(CheckoutInputState.parse(inputDump(rotation))?.rotation == rotation, "rotation $rotation")
    val published = "display=0;buttonVisible=true;focused=true;x=320;y=248;generation=500;sequence=1;" +
        "sampleMs=1000;rotation=1;width=640;height=320;window=0,4,640,296;button=16,224,624,272"
    verify(CheckoutReadySnapshot.parse(published) == snapshot(), "complete atomic snapshot parses")
    for (bad in listOf(published.dropLast(3), published + ";x=10", published + ";unknown=1",
            published.replace("focused=true", "focused=maybe"))) {
        verify(CheckoutReadySnapshot.parse(bad) == null, "incomplete/mixed/duplicate snapshot rejected")
    }
    val good = CheckoutReadiness()
    verify(!good.observe(snapshot(), input, 1000), "first sample is not stable")
    verify(!good.observe(snapshot(), input, 1500), "rereading one cached generation is not freshness")
    verify(!good.observe(snapshot(2, 1250), input, 1250), "250 ms is not settled")
    verify(good.observe(snapshot(3, 1500), input, 1500), "coherent fresh 500 ms geometry passes")
    val mixed = CheckoutReadiness()
    mixed.observe(snapshot(), input, 1000)
    mixed.observe(snapshot(2, 1250), input, 1250)
    verify(!mixed.observe(snapshot(2, 1500), input, 1500), "same sequence with different sample contents is mixed publication")
    val rejected = listOf(
        "stale timestamp" to snapshot(sampleMs=1000),
        "future timestamp" to snapshot(sampleMs=2001),
        "old rotation" to snapshot(sampleMs=2000).copy(rotation=0),
        "old display size" to snapshot(sampleMs=2000).copy(width=320, height=640),
        "mixed window generation" to snapshot(sampleMs=2000).copy(window=CheckoutRect(0, 324, 320, 616)),
        "negative logical point" to snapshot(sampleMs=2000).copy(x=-92),
        "off-screen button" to snapshot(sampleMs=2000).copy(button=CheckoutRect(16, 388, 624, 436), y=412),
        "point outside button" to snapshot(sampleMs=2000).copy(y=12),
        "hidden button" to snapshot(sampleMs=2000).copy(visible=false),
        "unfocused fixture" to snapshot(sampleMs=2000).copy(focused=false)
    )
    for ((label, sample) in rejected) {
        val gate = CheckoutReadiness()
        verify(!gate.observe(sample, input, 2000), label)
        verify(!gate.observe(sample.copy(sequence=3), input, 2500), "$label must not settle by waiting")
    }
    val oldUncheckedPoint = published.replace("y=248", "y=412")
    verify(oldUncheckedPoint.contains("display=0;") && oldUncheckedPoint.contains("buttonVisible=true") &&
        oldUncheckedPoint.contains("focused=true"), "sensitivity control: original booleans accept unchecked off-screen coordinates")
    verify(!CheckoutReadiness().observe(CheckoutReadySnapshot.parse(oldUncheckedPoint), input, 1000),
        "new gate rejects the old readiness-predicate sensitivity case")
    val recreated = CheckoutReadiness()
    verify(!recreated.observe(snapshot(), input, 1000), "old activity first sample")
    verify(!recreated.observe(snapshot(2, 1250), input, 1250), "old activity second sample")
    verify(!recreated.observe(snapshot(1, 1500, 1400), input, 1500), "new generation resets stability")
    verify(!recreated.observe(snapshot(2, 1750, 1400), input, 1750), "new generation still unsettled")
    verify(recreated.observe(snapshot(3, 2000, 1400), input, 2000), "new generation settles independently")
    for (bad in listOf(inputDump().replace("DispatchFrozen: false", "DispatchFrozen: true"),
            inputDump().replace("DispatchEnabled: true", "DispatchEnabled: false"),
            inputDump().replace("displayId=0, name='abc", "displayId=0, name='wrong"),
            inputDump().replace("SENSITIVE_FOR_PRIVACY", "NOT_TOUCHABLE"),
            inputDump().replace("SENSITIVE_FOR_PRIVACY", "NO_INPUT_CHANNEL"),
            inputDump().replace("SENSITIVE_FOR_PRIVACY", "DROP_INPUT"),
            inputDump().replace("SENSITIVE_FOR_PRIVACY", "DROP_INPUT_IF_OBSCURED"),
            inputDump().replace("0 0 1", "0 1 1"),
            inputDump().replace("0 1 0\n-1 0 320", "0 2 0\n-1 0 320"))) {
        verify(CheckoutInputState.parse(bad) == null, "inactive/mismatched focused token or input state rejected")
    }
    val pausedInput = CheckoutInputState.parse(inputDump().replace("SENSITIVE_FOR_PRIVACY", "PAUSE_DISPATCHING"))
    val paused = CheckoutReadiness()
    paused.observe(snapshot(), pausedInput, 1000)
    paused.observe(snapshot(2, 1250), pausedInput, 1250)
    verify(!paused.observe(snapshot(3, 1500), pausedInput, 1500),
        "PAUSE_DISPATCHING must not settle into tappable readiness")
    verify(pausedInput == null, "paused focused window must be rejected")
    val resume = CheckoutReadiness()
    resume.observe(snapshot(), input, 1000)
    verify(!resume.observe(snapshot(2, 1250), pausedInput, 1250), "per-window pause resets settling")
    verify(!resume.observe(snapshot(3, 1500), input, 1500), "resumed window must start a fresh stability interval")
    verify(resume.observe(snapshot(5, 2000), input, 2000), "resumed window can settle normally")
    val reset = CheckoutReadiness()
    reset.observe(snapshot(), input, 1000)
    reset.observe(snapshot(2, 1250), input, 1250)
    verify(!reset.observe(snapshot(3, 1500), null, 1500), "bad active window resets settling")
    verify(!reset.observe(snapshot(4, 1750), input, 1750), "cannot reuse pre-transition settling time")
    verify(reset.observe(snapshot(6, 2250), input, 2250), "settles after active window restoration")
    // Minimal format/geometry reproduction from artifact 11624086374, input lines 1216/1239.
    // The complete authorized dump can also be supplied locally as args[1].
    val api33Dump = inputDump().replace("abc", "4727ed")
        .replace("name=4727ed $COMPONENT,", "name='4727ed $COMPONENT',")
        .replace("id=741", "id=752").replace("SENSITIVE_FOR_PRIVACY", "0x0")
        .replace("frame=[24,0][316,640]", "frame=[0,0][292,640]")
    val api33Input = CheckoutInputState(1, 640, 320, CheckoutRect(0, 28, 640, 320))
    fun verifyApi33(dump: String, label: String) {
        verify(CheckoutInputState.parse(dump) == api33Input, "$label quoted record preserves logical geometry")
        val evidence = CheckoutInputState.numericEvidence(dump)
        verify("0,0,292,640" in evidence && "fixtureInputFlags=0x0" in evidence,
            "$label numeric rejection evidence includes selected quoted window")
        verify("org.androidcontrol" !in evidence && "name=" !in evidence,
            "$label numeric evidence excludes component/window names")
        val quotedName = "name='4727ed $COMPONENT'"
        val stale = "0: name='stale $COMPONENT', id=740, displayId=0, inputConfig=0x0, alpha=1, frame=[0,0][10,10], globalScale=1"
        val twoWindows = dump.replace(Regex("(?m)^(\\s*\\d+: )name='4727ed")) { match ->
            "$stale\n${match.groupValues[1]}name='4727ed"
        }
        verify(CheckoutInputState.parse(twoWindows) == api33Input, "$label quoted stale same-component window cannot replace exact focused token")
        val focusedMoved = twoWindows.lineSequence().joinToString("\n") { line ->
            when {
                quotedName + ", id=" in line -> line.replace("frame=[0,0][292,640]", "frame=[0,0][10,10]")
                "name='stale $COMPONENT'" in line -> line.replace("frame=[0,0][10,10]", "frame=[0,0][292,640]")
                else -> line
            }
        }
        verify(CheckoutInputState.parse(focusedMoved)?.window != api33Input.window,
            "$label a matching stale frame cannot substitute for the different focused frame")
        verify(CheckoutInputState.parse(dump.replace("displayId=0, name='4727ed", "displayId=0, name='wrong")) == null,
            "$label unmatched focused token fails closed")
        for (badName in listOf("name=4727ed $COMPONENT'", "name='4727ed $COMPONENT", "name=\"4727ed $COMPONENT\"")) {
            verify(CheckoutInputState.parse(dump.replace(quotedName + ", id=", badName + ", id=")) == null,
                "$label malformed/unknown quoting fails closed")
        }
        for (flag in listOf("PAUSE_DISPATCHING", "DROP_INPUT", "DROP_INPUT_IF_OBSCURED", "NOT_TOUCHABLE", "NO_INPUT_CHANNEL")) {
            verify(CheckoutInputState.parse(dump.replace("inputConfig=0x0", "inputConfig=$flag")) == null,
                "$label quoted focused window still rejects $flag")
        }
    }
    verifyApi33(api33Dump, "API33 format fixture")
    val api33Ready = snapshot().copy(y=264, window=api33Input.window, button=CheckoutRect(32, 240, 608, 288))
    val api33Gate = CheckoutReadiness()
    verify(!api33Gate.observe(api33Ready, api33Input, 1000), "API33 quoted window must still settle")
    verify(!api33Gate.observe(api33Ready.copy(sequence=2, sampleMs=1250), api33Input, 1250), "API33 250 ms remains insufficient")
    verify(api33Gate.observe(api33Ready.copy(sequence=3, sampleMs=1500), api33Input, 1500), "API33 recorded logical geometry can settle across fresh samples")
    if (args.isNotEmpty() && args[0].isNotEmpty()) {
        val actual = checkNotNull(CheckoutInputState.parse(File(args[0]).readText()))
        verify(actual == input, "real API35 dump parses to expected logical window/rotation")
    }
    if (args.size > 1) verifyApi33(File(args[1]).readText(), "actual API33 dump")
    println("PASS: $checks checkout-readiness checks, including fresh positives and stale/mixed-generation sensitivity")
}
