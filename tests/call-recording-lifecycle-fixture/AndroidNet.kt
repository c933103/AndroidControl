// SPDX-FileCopyrightText: 2026 AndroidControl contributors
// SPDX-License-Identifier: Apache-2.0
package android.net

import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.androidcontrol.app.recording.services.shell.Fixture

class LocalServerSocket(name: String) {
    @Volatile var closed = false
    private val connected = CountDownLatch(1)
    init {
        if (Fixture.failure == "socket") throw IOException("controlled socket failure")
        allocated.add(this)
    }
    fun connect() { connected.countDown() }
    fun accept(): LocalSocket {
        waiting.incrementAndGet()
        Fixture.accepting.countDown()
        try {
            check(connected.await(10, TimeUnit.SECONDS)) { "fixture accept timeout" }
            if (closed) throw IOException("closed")
            return LocalSocket()
        } finally { waiting.decrementAndGet() }
    }
    fun close() { closed = true; connected.countDown() }
    companion object {
        val allocated = CopyOnWriteArrayList<LocalServerSocket>()
        val waiting = AtomicInteger()
    }
}
class LocalSocket {
    @Volatile var closed = false
    init { allocated.add(this) }
    val inputStream = object : InputStream() {
        private var sent = false
        override fun read(): Int {
            check(Fixture.processStopped.await(10, TimeUnit.SECONDS)) { "fixture read timeout" }
            if (closed) throw IOException("closed")
            if (sent) return -1
            sent = true
            return 42 // Synthetic byte, never call/audio data.
        }
    }
    fun close() { closed = true; Fixture.processStopped.countDown() }
    companion object { val allocated = CopyOnWriteArrayList<LocalSocket>() }
}
