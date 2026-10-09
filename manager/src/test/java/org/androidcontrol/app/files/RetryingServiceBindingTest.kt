package org.androidcontrol.app.files

import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class RetryingServiceBindingTest {
    private class Service(var alive: Boolean = true)
    private class Connection(val connected: (Service?) -> Unit, val disconnected: () -> Unit)
    private val executors = mutableListOf<java.util.concurrent.ExecutorService>()
    private fun pool() = Executors.newCachedThreadPool().also { executors.add(it) }
    @After fun stopWorkers() { executors.forEach { it.shutdownNow() } }

    private inner class Fixture {
        val bound = LinkedBlockingQueue<Connection>()
        val unbound = Collections.synchronizedList(mutableListOf<Connection>())
        val cleanupStarted = LinkedBlockingQueue<Connection>()
        var onBind: (Connection) -> Unit = { }
        var onUnbind: (Connection) -> Unit = { }
        private val worker = Executors.newSingleThreadExecutor().also { executors.add(it) }
        val binding = RetryingServiceBinding<Service, Connection>(
            { it.alive }, { connected, disconnected -> Connection(connected, disconnected) },
            { bound.add(it); onBind(it) },
            { unbound.add(it); cleanupStarted.add(it); onUnbind(it) }, worker,
        )
        fun next() = checkNotNull(bound.poll(2, TimeUnit.SECONDS)) { "No bind started" }
        fun cleanup() = checkNotNull(cleanupStarted.poll(2, TimeUnit.SECONDS)) { "No cleanup started" }
        fun pending(): Pair<Future<Service>, Connection> {
            val result = pool().submit<Service> { binding.requireService(30) }
            return result to next()
        }
        fun timeoutPending(): Connection {
            val (result, old) = pending()
            assertTrue(failure { binding.requireService(0) }.message!!.contains("Timed out"))
            assertTrue(runCatching { result.get(2, TimeUnit.SECONDS) }.exceptionOrNull()?.cause is IOException)
            assertSame(old, cleanup())
            return old
        }
    }
    private fun failure(action: () -> Unit): IOException {
        try { action() } catch (e: IOException) { return e }
        throw AssertionError("Expected IOException")
    }
    private fun awaitCaller(ref: AtomicReference<Thread>) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        fun awaiting(): Boolean = ref.get()?.let { thread ->
            thread.state == Thread.State.TIMED_WAITING && thread.stackTrace.any {
                it.className == CountDownLatch::class.java.name && it.methodName == "await"
            }
        } == true
        while (!awaiting() && System.nanoTime() < deadline) Thread.yield()
        assertTrue("Caller did not reach the latch", awaiting())
    }

    @Test fun unansweredTimeoutAllowsFreshBindAndRejectsLateCallbacks() {
        val f = Fixture()
        val old = f.timeoutPending()
        val (result, replacement) = f.pending()
        old.connected(Service())
        old.disconnected()
        assertFalse(result.isDone)
        val service = Service()
        replacement.connected(service)
        assertSame(service, result.get(2, TimeUnit.SECONDS))
        old.disconnected()
        old.connected(Service())
        assertSame(service, f.binding.requireService(0))
        assertTrue(f.bound.isEmpty())
    }

    @Test fun pendingCallersShareOneBindAndAllReceiveItsService() {
        val f = Fixture()
        val firstThread = AtomicReference<Thread>()
        val secondThread = AtomicReference<Thread>()
        val callers = pool()
        val first = callers.submit<Service> { firstThread.set(Thread.currentThread()); f.binding.requireService(30) }
        val callback = f.next()
        val second = callers.submit<Service> { secondThread.set(Thread.currentThread()); f.binding.requireService(30) }
        // Both callers must be inside the actual latch wait before callback delivery.
        awaitCaller(firstThread)
        awaitCaller(secondThread)
        val service = Service()
        callback.connected(service)
        assertSame(service, first.get(2, TimeUnit.SECONDS))
        assertSame(service, second.get(2, TimeUnit.SECONDS))
        assertTrue(f.bound.isEmpty())
    }

    @Test fun timeoutWakesOtherWaitersWithoutRetiringReplacement() {
        val f = Fixture()
        val (waiting, old) = f.pending()
        failure { f.binding.requireService(0) }
        val nextService = Service()
        f.onBind = { it.connected(nextService) }
        assertSame(nextService, f.binding.requireService(2))
        assertTrue(runCatching { waiting.get(2, TimeUnit.SECONDS) }.exceptionOrNull()?.cause is IOException)
        assertEquals(listOf(old), f.unbound)
        assertSame(nextService, f.binding.peek())
    }

    @Test fun stalledCleanupDoesNotBlockTimeoutOrQueueReplacementBinds() {
        val f = Fixture()
        val finishCleanup = CountDownLatch(1)
        f.onUnbind = { finishCleanup.await() }
        try {
            val (waiting, old) = f.pending()
            val timeout = pool().submit<IOException> { failure { f.binding.requireService(0) } }
            assertSame(old, f.cleanup())
            assertTrue(timeout.get(2, TimeUnit.SECONDS).message!!.contains("Timed out"))
            assertTrue(runCatching { waiting.get(2, TimeUnit.SECONDS) }.exceptionOrNull()?.cause is IOException)
            val retries = pool().submit<Int> {
                repeat(100) { failure { f.binding.requireService(0) } }
                100
            }
            assertEquals(100, retries.get(2, TimeUnit.SECONDS))
            assertTrue(f.bound.isEmpty())
            assertEquals(listOf(old), f.unbound)
            val service = Service()
            f.onBind = { it.connected(service) }
            finishCleanup.countDown()
            assertSame(service, f.binding.requireService(2))
            old.disconnected()
            assertSame(service, f.binding.peek())
        } finally { finishCleanup.countDown() }
    }

    @Test fun cleanupFinishesBeforeWaitingRetryCanRegister() {
        val f = Fixture()
        val finishCleanup = CountDownLatch(1)
        f.onUnbind = { finishCleanup.await() }
        try {
            val old = f.timeoutPending()
            val retryThread = AtomicReference<Thread>()
            val retry = pool().submit<Service> { retryThread.set(Thread.currentThread()); f.binding.requireService(30) }
            awaitCaller(retryThread)
            assertTrue(f.bound.isEmpty())
            old.connected(Service())
            assertFalse(retry.isDone)
            finishCleanup.countDown()
            val fresh = f.next()
            val service = Service()
            fresh.connected(service)
            assertSame(service, retry.get(2, TimeUnit.SECONDS))
        } finally { finishCleanup.countDown() }
    }

    @Test fun stalledBindDoesNotBlockTimeoutAndCleanupRemainsOrdered() {
        val f = Fixture()
        val finishBind = CountDownLatch(1)
        f.onBind = { finishBind.await() }
        try {
            val (waiting, old) = f.pending()
            val timeout = pool().submit<IOException> { failure { f.binding.requireService(0) } }
            timeout.get(2, TimeUnit.SECONDS)
            assertTrue(runCatching { waiting.get(2, TimeUnit.SECONDS) }.exceptionOrNull()?.cause is IOException)
            assertTrue(f.unbound.isEmpty())
            failure { f.binding.requireService(0) }
            assertTrue(f.bound.isEmpty())
            finishBind.countDown()
            assertSame(old, f.cleanup())
            val service = Service()
            f.onBind = { it.connected(service) }
            assertSame(service, f.binding.requireService(2))
        } finally { finishBind.countDown() }
    }

    @Test fun bindExceptionAndFailedCleanupPermitRetry() {
        val f = Fixture()
        f.onBind = { error("backend stopped") }
        f.onUnbind = { error("backend still stopped") }
        assertEquals("backend stopped", failure { f.binding.requireService(2) }.message)
        val old = f.next()
        val service = Service()
        f.onBind = { it.connected(service) }
        assertSame(service, f.binding.requireService(2))
        old.connected(Service())
        old.disconnected()
        assertSame(service, f.binding.peek())
    }

    @Test fun nullConnectionAndDisconnectAndDeadServiceEachPermitRetry() {
        val f = Fixture()
        f.onBind = { it.connected(null) }
        failure { f.binding.requireService(2) }
        val nullCallback = f.next()
        val service = Service()
        f.onBind = { it.connected(service) }
        assertSame(service, f.binding.requireService(2))
        val connected = f.next()
        assertEquals(listOf(nullCallback), f.unbound)
        connected.disconnected()
        assertSame(service, f.binding.requireService(2))
        f.next()
        service.alive = false
        val replacement = Service()
        f.onBind = { it.connected(replacement) }
        assertSame(replacement, f.binding.requireService(2))
        connected.connected(Service())
        assertSame(replacement, f.binding.peek())
    }

    @Test fun interruptedWaiterKeepsInterruptAndDoesNotCancelSharedBind() {
        val f = Fixture()
        val exited = CountDownLatch(1)
        var error: Throwable? = null
        val thread = Thread {
            try {
                val exception = failure { f.binding.requireService(30) }
                assertTrue(exception.cause is InterruptedException)
                assertTrue(Thread.currentThread().isInterrupted)
            } catch (t: Throwable) { error = t } finally { exited.countDown() }
        }
        thread.start()
        val callback = f.next()
        thread.interrupt()
        assertTrue(exited.await(2, TimeUnit.SECONDS))
        error?.let { throw it }
        assertTrue(f.unbound.isEmpty())
        val service = Service()
        callback.connected(service)
        assertSame(service, f.binding.requireService(0))
    }
}
