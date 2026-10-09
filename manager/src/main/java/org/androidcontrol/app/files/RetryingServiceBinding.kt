package org.androidcontrol.app.files

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * One generation owns both its callback and its waiters.
 * [isAlive] must be a nonblocking local-state read, never a remote health ping.
 */
internal class RetryingServiceBinding<T : Any, C : Any>(
    private val isAlive: (T) -> Boolean,
    private val connection: (connected: (T?) -> Unit, disconnected: () -> Unit) -> C,
    private val bind: (C) -> Unit,
    private val unbind: (C) -> Unit,
    private val worker: Executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "adb-file-binding").apply { isDaemon = true }
    },
) {
    private class Attempt<T : Any, C : Any> {
        val latch = CountDownLatch(1)
        lateinit var connection: C
        var service: T? = null
        var failure: Throwable? = null
    }

    private val lock = Any()
    private var current: Attempt<T, C>? = null
    private var retiring: CountDownLatch? = null

    fun peek(): T? = synchronized(lock) { current?.service?.takeIf(isAlive) }

    @Throws(IOException::class)
    fun requireService(timeout: Long, unit: TimeUnit = TimeUnit.SECONDS): T {
        val started = System.nanoTime()
        val budget = unit.toNanos(timeout.coerceAtLeast(0))
        fun remaining() = (budget - (System.nanoTime() - started)).coerceAtLeast(0)
        while (true) {
            val cleanup: CountDownLatch?
            val attempt = synchronized(lock) {
                current?.service?.takeIf(isAlive)?.let { return it }
                current?.takeIf { it.latch.count == 0L }?.let { retire(it) }
                cleanup = retiring
                if (cleanup != null) null else current ?: start()
            }
            if (attempt == null) {
                // Cleanup may be stalled in Binder. Callers still have bounded waits;
                // retries neither bypass it nor enqueue unbounded replacement binds.
                if (!await(checkNotNull(cleanup), remaining())) {
                    throw IOException("Timed out connecting to privileged file service")
                }
                continue
            }
            val answered = await(attempt.latch, remaining())
            return synchronized(lock) {
                if (current === attempt) {
                    attempt.service?.takeIf(isAlive)?.let { return@synchronized it }
                    if (!answered) retire(attempt)
                }
                attempt.failure?.let { throw IOException(it.message ?: it.javaClass.simpleName, it) }
                throw IOException(if (answered) "Privileged file service is unavailable"
                    else "Timed out connecting to privileged file service")
            }
        }
    }

    private fun await(latch: CountDownLatch, nanos: Long): Boolean = try {
        latch.await(nanos, TimeUnit.NANOSECONDS)
    } catch (e: InterruptedException) {
        // Interruption belongs to this caller, not other waiters sharing the bind.
        Thread.currentThread().interrupt()
        throw IOException("Interrupted connecting to privileged file service", e)
    }

    /** Called under lock; only scheduling and state changes happen here, never Binder I/O. */
    private fun start(): Attempt<T, C> {
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
        worker.execute {
            if (synchronized(lock) { current !== next }) return@execute
            try {
                bind(next.connection)
            } catch (t: Throwable) {
                synchronized(lock) {
                    if (current === next) {
                        next.service = null
                        next.failure = t
                        next.latch.countDown()
                    }
                }
            }
        }
        return next
    }

    /** Called under lock: the retiring gate prevents any replacement registration. */
    private fun retire(attempt: Attempt<T, C>) {
        if (current !== attempt) return
        current = null
        attempt.service = null
        attempt.latch.countDown()
        val cleanup = CountDownLatch(1)
        retiring = cleanup
        // The same serial worker orders cleanup after even a stalled bind. Shizuku's
        // non-removing unbind clears ALL callbacks for this service, so the gate stays
        // closed until cleanup finishes. Neither bind nor unbind holds the state lock.
        worker.execute {
            try {
                unbind(attempt.connection)
            } catch (_: Throwable) {
                // A stopped backend may reject cleanup; identity checks still protect
                // future generations from its outstanding callbacks.
            } finally {
                synchronized(lock) {
                    if (retiring === cleanup) retiring = null
                    cleanup.countDown()
                }
            }
        }
    }
}
