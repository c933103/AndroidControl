// SPDX-FileCopyrightText: 2026 AndroidControl contributors
// SPDX-License-Identifier: Apache-2.0
package org.androidcontrol.app.recording.services.shell

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.ParcelFileDescriptor
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object Fixture {
    var failure = ""
    var accepting = CountDownLatch(1)
    var processStopped = CountDownLatch(1)
    val output = CopyOnWriteArrayList<Int>()
    val processes = CopyOnWriteArrayList<FakeProcess>()
}
// Same-package lookup replaces only process creation in this host fixture.
// The production source is compiled unchanged; no executable is launched.
class ProcessBuilder(command: List<String>) {
    fun environment() = mutableMapOf<String, String>()
    fun redirectErrorStream(merge: Boolean) = this
    fun start(): Process {
        check(Fixture.accepting.await(2, TimeUnit.SECONDS)) { "relay never reached accept" }
        if (Fixture.failure == "process") throw IOException("controlled ProcessBuilder.start failure")
        LocalServerSocket.allocated.last().connect()
        return FakeProcess().also { Fixture.processes.add(it) }
    }
}
class FakeProcess : Process() {
    @Volatile var destroyed = false
    override fun destroy() { destroyed = true; Fixture.processStopped.countDown() }
    override fun waitFor(): Int { check(Fixture.processStopped.await(10, TimeUnit.SECONDS)); return 0 }
    override fun waitFor(timeout: Long, unit: TimeUnit) = Fixture.processStopped.await(timeout, unit)
    override fun exitValue() = if (destroyed) 0 else throw IllegalThreadStateException()
    override fun getInputStream() = ByteArrayInputStream(byteArrayOf())
    override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
    override fun getOutputStream() = ByteArrayOutputStream()
}
fun eventually(check: () -> Boolean) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
    while (!check() && System.nanoTime() < deadline) Thread.sleep(5)
    check(check()) { "resources did not settle within 4 seconds" }
}
fun field(pipeline: ShellAudioPipeline, name: String): Any? = pipeline.javaClass.getDeclaredField(name).let {
    it.isAccessible = true; it.get(pipeline)
}
fun main(args: Array<String>) {
    val scenario = args.single()
    val pipeline = ShellAudioPipeline()
    val jar = File.createTempFile("recorder-fixture-", ".jar")
    try {
        if (scenario == "success") {
            val read = pipeline.startCapture("fixture", "fixture", 16000, jar.path, false)
                ?: error("success startup returned null")
            eventually { field(pipeline, "clientConnection") != null }
            check(pipeline.startCapture("fixture", "fixture", 16000, jar.path, false) == null)
            check(!read.closed) { "caller read end closed before transfer" }
            pipeline.stopCapture()
            pipeline.stopCapture()
            check(!read.closed) { "stop closed caller-owned read end" }
            check(Fixture.output == listOf(42)) { "final synthetic byte was not drained" }
            read.close()
            check(Fixture.processes.all { it.destroyed })
        } else {
            Fixture.failure = scenario
            repeat(4) {
                Fixture.accepting = CountDownLatch(1)
                check(pipeline.startCapture("fixture", "fixture", 16000, jar.path, false) == null)
                pipeline.stopCapture()
            }
        }
        eventually { LocalServerSocket.waiting.get() == 0 }
        check(ParcelFileDescriptor.allocated.all { it.closed }) { "unclosed pipe descriptors: ${ParcelFileDescriptor.allocated.count { !it.closed }}" }
        check(LocalServerSocket.allocated.all { it.closed }) { "unclosed listening sockets" }
        check(LocalSocket.allocated.all { it.closed }) { "unclosed accepted sockets" }
        listOf("scrcpyProcess", "serverSocket", "clientConnection", "audioWriteEnd", "shellScope", "audioPipeRelayJob").forEach {
            check(field(pipeline, it) == null) { "retained resource: $it" }
        }
        println("PASS $scenario: no retained allocations after repeated failure or stop")
    } finally { jar.delete() }
}
