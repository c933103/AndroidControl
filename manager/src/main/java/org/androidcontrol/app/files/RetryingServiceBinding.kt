package org.androidcontrol.app.files

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** One generation owns both its callback and its waiters. */
internal class RetryingServiceBinding<T : Any, C : Any>(
    private val isAlive: (T) -> Boolean,
    private val connection: (connected: (T?) -> Unit, disconnected: () -> Unit) -> C,
    private val bind: (C) -> Unit,
    private val unbind: (C) -> Unit,
) {
    private class Attempt<T : Any, C : Any> {
        val latch = CountDownLatch(1)
        lateinit var connection: C
        var service: T? = null
    }

    private val lock = Any()
    private var current: Attempt<T, C>? = null

    fun peek(): T? = synchronized(lock) { current?.service?.takeIf(isAlive) }

    @Throws(IOException::class)
    fun requireService(timeout: Long, unit: TimeUnit = TimeUnit.SECONDS): T {
        val attempt = synchronized(lock) {
            current?.service?.takeIf(isAlive)?.let { return it }
            current?.takeIf { it.latch.count > 0 } ?: run {
                current?.let { retire(it) }
                val next = Attempt<T, C>()
                next.connection = connection({ service ->
                    synchronized(lock) {
                        if (current === next) {
                            next.service = service?.takeIf(isAlive)
                            next.latch.countDown()
                        }
                    }
                }, {
                    synchronized(lock) {
                        if (current === next) {
                            next.service = null
                            next.latch.countDown()
                        }
                    }
                })
                current = next
                try {
                    bind(next.connection)
                } catch (t: Throwable) {
                    retire(next)
                    throw IOException(t.message ?: t.javaClass.simpleName, t)
                }
                next
            }
        }

        val answered = try {
            attempt.latch.await(timeout, unit)
        } catch (e: InterruptedException) {
            // Interruption belongs to this caller, not other waiters sharing the bind.
            Thread.currentThread().interrupt()
            throw IOException("Interrupted connecting to privileged file service", e)
        }
        return synchronized(lock) {
            // Accept a callback that won the lock at the timeout boundary, but never a
            // replacement generation's service. Old waiters cannot retire a new bind.
            if (current === attempt) {
                attempt.service?.takeIf(isAlive)?.let { return@synchronized it }
                if (!answered) retire(attempt)
            }
            throw IOException(if (answered) "Privileged file service is unavailable"
                else "Timed out connecting to privileged file service")
        }
    }

    /** Called under lock: Shizuku's non-removing unbind clears ALL callbacks for this service. */
    private fun retire(attempt: Attempt<T, C>) {
        if (current !== attempt) return
        current = null
        attempt.service = null
        attempt.latch.countDown()
        // Do not let a replacement bind begin until cleanup finishes. A stopped backend
        // can reject unbind; identity checks still make its outstanding callbacks harmless.
        runCatching { unbind(attempt.connection) }
    }
}
