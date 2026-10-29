// SPDX-FileCopyrightText: 2026 AndroidControl contributors
// SPDX-License-Identifier: Apache-2.0
package org.androidcontrol.app.regression

fun main() {
    fun trace(pid: Int = 123, pkg: String = RecorderAnrThreads.PACKAGE) = """
----- pid $pid at 2026-10-29 06:00:00 -----
Cmd line: $pkg
DO_NOT_RETAIN_HEADER
"main" prio=5 tid=1 Native
  | sysTid=123
  native: #00 synthetic_native
  at android.os.MessageQueue.nativePollOnce(Native method)
  - waiting on <0x123> (synthetic)
DO_NOT_RETAIN_FREE_TEXT
"other-thread" prio=5 tid=2 Runnable
  at unrelated.DO_NOT_RETAIN()
"Instr: org.androidcontrol.app.regression.RecorderMediaInstrumentation" prio=5 tid=3 Runnable
  at android.media.MediaCodec.dequeueInputBuffer(Native method)
----- end $pid -----
"main" prio=5 tid=1 Native
  at DO_NOT_RETAIN_AFTER_END()
""".trimIndent()
    val result = RecorderAnrThreads.select(trace(), 123)
    check(result.size == 7 && result.any { "MessageQueue" in it } && result.any { "dequeueInputBuffer" in it })
    check(result.none { "DO_NOT_RETAIN" in it }); println("PASS target-main-instrumentation-only")
    check(RecorderAnrThreads.select(trace(pid=999), 123).isEmpty()); println("PASS unrelated-pid")
    check(RecorderAnrThreads.select(trace(pkg="another.app"), 123).isEmpty()); println("PASS unrelated-package")
    check(RecorderAnrThreads.select(trace(pkg=RecorderAnrThreads.PACKAGE+".other"), 123).isEmpty()); println("PASS lookalike-package")
    check(RecorderAnrThreads.select(trace().substringAfter("Cmd line:"), 123).isEmpty()); println("PASS missing-header")
    check(RecorderAnrThreads.select(trace().replace("Cmd line: ${RecorderAnrThreads.PACKAGE}", "missing"), 123).isEmpty()); println("PASS missing-package")
    check(RecorderAnrThreads.select(trace(999,"another.app")+"\n"+trace(), 123) == result); println("PASS mixed-processes")
    check(RecorderAnrThreads.select(trace()+"\n"+trace(999,"another.app"), 123) == result); println("PASS trailing-other-process")
    val large = trace().replace("  | sysTid=123", (1..150).joinToString("\n") { "  at synthetic.frame$it()" })
    check(RecorderAnrThreads.select(large,123).size <= 200); println("PASS bounded-thread-lines")
    check(runCatching { RecorderAnrThreads.select("x".repeat(RecorderAnrThreads.MAX_BYTES+1),123) }.isFailure); println("PASS oversized-trace")
    check(runCatching { RecorderAnrThreads.select(trace(),0) }.isFailure); println("PASS invalid-pid")
}
