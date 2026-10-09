package org.androidcontrol.app.files

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class RetryingServiceBindingTest {
    private class Service(var alive: Boolean = true)
    private class Connection(val connected: (Service?) -> Unit, val disconnected: () -> Unit)
    private class Fixture {
        val bound = LinkedBlockingQueue<Connection>()
        val unbound = mutableListOf<Connection>()
        var onBind: (Connection) -> Unit = { }
        var onUnbind: (Connection) -> Unit = { }
        val binding = RetryingServiceBinding<Service, Connection>(
            { it.alive }, { connected, disconnected -> Connection(connected, disconnected) },
            { bound.add(it); onBind(it) },
            { synchronized(unbound) { unbound.add(it) }; onUnbind(it) },
        )
        fun next() = checkNotNull(bound.poll(2, TimeUnit.SECONDS)) { "No bind started" }
    }
    private fun failure(action: () -> Unit): IOException {
        try { action() } catch (e: IOException) { return e }
        throw AssertionError("Expected IOException")
    }

    @Test fun unansweredTimeoutAllowsFreshBindAndRejectsLateCallbacks() {
        val f = Fixture()
        assertTrue(failure { f.binding.requireService(0) }.message!!.contains("Timed out"))
        val old = f.next()
        assertEquals(listOf(old), f.unbound)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val result = pool.submit<Service> { f.binding.requireService(2) }
            val replacement = f.next()
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
        } finally { pool.shutdownNow() }
    }

    @Test fun pendingCallersShareOneBindAndAllReceiveItsService() {
        val f = Fixture()
        val pool = Executors.newFixedThreadPool(2)
        try {
            val firstThread = AtomicReference<Thread>()
            val secondThread = AtomicReference<Thread>()
            val first = pool.submit<Service> { firstThread.set(Thread.currentThread()); f.binding.requireService(5) }
            val callback = f.next()
            val second = pool.submit<Service> { secondThread.set(Thread.currentThread()); f.binding.requireService(5) }
            // Observe both real latch waits before delivery. A start latch alone only
            // proves scheduling and could let the second caller take the cached path.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            fun awaiting(ref: AtomicReference<Thread>): Boolean = ref.get()?.let { thread ->
                thread.state == Thread.State.TIMED_WAITING && thread.stackTrace.any {
                    it.className == CountDownLatch::class.java.name && it.methodName == "await"
                }
            } == true
            while (!(awaiting(firstThread) && awaiting(secondThread)) && System.nanoTime() < deadline) {
                Thread.yield()
            }
            assertTrue("First caller did not reach the shared latch", awaiting(firstThread))
            assertTrue("Second caller did not reach the shared latch", awaiting(secondThread))
            val service = Service()
            callback.connected(service)
            assertSame(service, first.get(2, TimeUnit.SECONDS))
            assertSame(service, second.get(2, TimeUnit.SECONDS))
            assertTrue(f.bound.isEmpty())
        } finally { pool.shutdownNow() }
    }

    @Test fun timeoutWakesOtherWaitersWithoutRetiringReplacement() {
        val f = Fixture()
        val pool = Executors.newSingleThreadExecutor()
        try {
            val waiting = pool.submit<IOException> { failure { f.binding.requireService(30) } }
            val old = f.next()
            failure { f.binding.requireService(0) }
            val nextService = Service()
            f.onBind = { it.connected(nextService) }
            assertSame(nextService, f.binding.requireService(0))
            assertTrue(waiting.get(2, TimeUnit.SECONDS).message!!.contains("unavailable"))
            assertEquals(listOf(old), f.unbound)
            assertSame(nextService, f.binding.peek())
        } finally { pool.shutdownNow() }
    }

    @Test fun cleanupCannotClearReplacementRegistration() {
        val f = Fixture()
        val cleanupStarted = CountDownLatch(1)
        val finishCleanup = CountDownLatch(1)
        val retryStarted = CountDownLatch(1)
        f.onUnbind = { cleanupStarted.countDown(); check(finishCleanup.await(2, TimeUnit.SECONDS)) }
        val pool = Executors.newFixedThreadPool(2)
        try {
            val oldResult = pool.submit<IOException> { failure { f.binding.requireService(0) } }
            val old = f.next()
            assertTrue(cleanupStarted.await(2, TimeUnit.SECONDS))
            val retry = pool.submit<Service> { retryStarted.countDown(); f.binding.requireService(2) }
            assertTrue(retryStarted.await(2, TimeUnit.SECONDS))
            assertNull(f.bound.poll(100, TimeUnit.MILLISECONDS))
            finishCleanup.countDown()
            val fresh = f.next()
            val service = Service()
            fresh.connected(service)
            old.disconnected()
            oldResult.get(2, TimeUnit.SECONDS)
            assertSame(service, retry.get(2, TimeUnit.SECONDS))
        } finally { finishCleanup.countDown(); pool.shutdownNow() }
    }

    @Test fun bindExceptionAndFailedCleanupPermitRetry() {
        val f = Fixture()
        f.onBind = { error("backend stopped") }
        f.onUnbind = { error("backend still stopped") }
        assertEquals("backend stopped", failure { f.binding.requireService(0) }.message)
        val old = f.next()
        val service = Service()
        f.onBind = { it.connected(service) }
        assertSame(service, f.binding.requireService(0))
        old.connected(Service())
        old.disconnected()
        assertSame(service, f.binding.peek())
    }

    @Test fun nullConnectionAndDisconnectAndDeadServiceEachPermitRetry() {
        val f = Fixture()
        f.onBind = { it.connected(null) }
        failure { f.binding.requireService(0) }
        val nullCallback = f.next()
        val service = Service()
        f.onBind = { it.connected(service) }
        assertSame(service, f.binding.requireService(0))
        val connected = f.next()
        assertEquals(listOf(nullCallback), f.unbound)
        connected.disconnected()
        assertSame(service, f.binding.requireService(0))
        f.next()
        service.alive = false
        val replacement = Service()
        f.onBind = { it.connected(replacement) }
        assertSame(replacement, f.binding.requireService(0))
        connected.connected(Service())
        assertSame(replacement, f.binding.peek())
    }

    @Test fun interruptedWaiterKeepsInterruptAndDoesNotCancelSharedBind() {
        val f = Fixture()
        val exited = CountDownLatch(1)
        var failure: Throwable? = null
        val thread = Thread {
            try {
                val error = failure { f.binding.requireService(30) }
                assertTrue(error.cause is InterruptedException)
                assertTrue(Thread.currentThread().isInterrupted)
            } catch (t: Throwable) { failure = t } finally { exited.countDown() }
        }
        thread.start()
        val callback = f.next()
        thread.interrupt()
        assertTrue(exited.await(2, TimeUnit.SECONDS))
        failure?.let { throw it }
        assertTrue(f.unbound.isEmpty())
        val service = Service()
        callback.connected(service)
        assertSame(service, f.binding.requireService(0))
    }
}
