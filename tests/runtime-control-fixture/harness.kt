import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

interface IBinder
object FixtureBinder : IBinder
class ComponentName(val packageName: String, val className: String)
interface ServiceConnection {
    fun onServiceConnected(name: ComponentName, binder: IBinder)
    fun onServiceDisconnected(name: ComponentName)
}
interface IAndroidControlService {
    object Stub {
        fun asInterface(binder: IBinder): IAndroidControlService {
            check(binder === FixtureBinder)
            return FixtureService
        }
    }
}
object FixtureService : IAndroidControlService
class AndroidControlService
object BuildConfig {
    const val APPLICATION_ID = "fixture.test"
    const val DEBUG = true
    const val VERSION_CODE = 1
}
object SystemClock {
    fun uptimeMillis(): Long = System.nanoTime() / 1_000_000
    fun sleep(ms: Long) = Thread.sleep(ms)
}
class MainThreadCrash(cause: Throwable) : RuntimeException("uncaught main-thread exception", cause)

class Environment(
    val initiallyAttached: Boolean = true,
    val attachDelayMs: Long? = null,
    val connectionDelayMs: Long = 0,
    val bindFailure: Throwable? = null,
    val attachDuringFirstRegistration: Boolean = false,
    val readyPublicationAfterRegistrations: Int = 0,
    val dieBeforeBind: Boolean = false
) : AutoCloseable {
    val main = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "fixture-main") }
    private val timer = Executors.newSingleThreadScheduledExecutor()
    val uncaught = AtomicReference<Throwable?>()
    @Volatile var attachObserved = initiallyAttached
    @Volatile var binderReady = initiallyAttached
    @Volatile var registrationCalls = 0
    var maximumListeners = 0
    @Volatile var bindCalls = 0
    @Volatile var prematureBind = false
    var interruptionAtAwait = false

    fun schedule(delay: Long, block: () -> Unit) {
        timer.schedule({ main.execute(block) }, delay, TimeUnit.MILLISECONDS)
    }
    fun openManager() {
        attachDelayMs?.let { delay ->
            schedule(delay) {
                completeAttach()
            }
        }
        if (interruptionAtAwait) Thread.currentThread().interrupt()
    }
    fun completeAttach() {
        // Pinned API: service and attach payload exist before notification;
        // binderReady is set only after iterating the listener list.
        attachObserved = true
        synchronized(Shizuku.listeners) {
            Shizuku.listeners.forEach { Shizuku.dispatch(it) }
        }
        if (readyPublicationAfterRegistrations == 0) binderReady = true
    }
    fun runOnMainSync(block: () -> Unit) {
        val done = CountDownLatch(1)
        main.execute {
            try { block() } catch (failure: Throwable) { uncaught.set(failure) }
            finally { done.countDown() }
        }
        check(done.await(5, TimeUnit.SECONDS)) { "fixture main thread stalled" }
        // Real Android's uncaught main-thread exception terminates instrumentation.
        // Fail promptly here rather than allowing a host test to hang for 15s.
        uncaught.get()?.let { throw MainThreadCrash(it) }
    }
    override fun close() {
        Thread.interrupted()
        timer.shutdownNow()
        main.shutdownNow()
        timer.awaitTermination(5, TimeUnit.SECONDS)
        main.awaitTermination(5, TimeUnit.SECONDS)
        Shizuku.listeners.clear()
    }
}

object Shizuku {
    fun interface OnBinderReceivedListener { fun onBinderReceived() }
    lateinit var environment: Environment
    val listeners = mutableListOf<OnBinderReceivedListener>()
    fun dispatch(listener: OnBinderReceivedListener) {
        if (Thread.currentThread().name == "fixture-main") listener.onBinderReceived()
        else environment.main.execute { listener.onBinderReceived() }
    }
    fun addBinderReceivedListenerSticky(listener: OnBinderReceivedListener) {
        // Match pinned Shizuku.java: readiness check precedes synchronized add.
        val wasReady = environment.binderReady
        environment.registrationCalls++
        if (environment.attachDuringFirstRegistration && environment.registrationCalls == 1) {
            check(!wasReady && listeners.isEmpty())
            // Deterministically insert attach after read-false but before add.
            environment.completeAttach()
        }
        if (environment.readyPublicationAfterRegistrations == environment.registrationCalls) {
            // Resume the paused notifier only after several failed sticky reads.
            environment.binderReady = true
        }
        if (wasReady) dispatch(listener)
        synchronized(listeners) {
            listeners.add(listener)
            environment.maximumListeners = maxOf(environment.maximumListeners, listeners.size)
        }
    }
    fun removeBinderReceivedListener(listener: OnBinderReceivedListener): Boolean =
        synchronized(listeners) { listeners.removeAll { it === listener } }
    // The controlled historical failure has a live raw binder before API attach.
    fun pingBinder(): Boolean = true
    class UserServiceArgs(name: ComponentName) {
        fun daemon(value: Boolean) = this
        fun processNameSuffix(value: String) = this
        fun debuggable(value: Boolean) = this
        fun version(value: Int) = this
    }
    fun bindUserService(args: UserServiceArgs, connection: ServiceConnection) {
        check(Thread.currentThread().name == "fixture-main")
        environment.bindCalls++
        if (environment.dieBeforeBind) environment.attachObserved = false
        if (!environment.attachObserved) {
            environment.prematureBind = true
            throw IllegalStateException("fixture binder has not attached")
        }
        environment.bindFailure?.let { throw it }
        environment.schedule(environment.connectionDelayMs) {
            connection.onServiceConnected(ComponentName("fixture.test", "fixture.service"), FixtureBinder)
        }
    }
}
class ActualHelper(private val environment: Environment) {
    private fun openManager() = environment.openManager()
    private fun runOnMainSync(block: () -> Unit) = environment.runOnMainSync(block)
    // ACTUAL_CONTROL_SERVICE
}

fun main() {
    var failures = 0
    fun test(name: String, block: () -> Unit) {
        try {
            block()
            println("PASS $name")
        } catch (failure: Throwable) {
            failures++
            println("FAILED $name: ${failure.javaClass.simpleName}: ${failure.message}")
        }
    }
    test("already attached") {
        Environment().use { env ->
            Shizuku.environment = env
            check(ActualHelper(env).controlService() === FixtureService)
            check(env.bindCalls == 1 && env.uncaught.get() == null && Shizuku.listeners.isEmpty())
        }
    }
    test("delayed attach") {
        Environment(initiallyAttached = false, attachDelayMs = 75).use { env ->
            Shizuku.environment = env
            check(ActualHelper(env).controlService() === FixtureService)
            check(env.attachObserved && !env.prematureBind && env.bindCalls == 1)
            check(env.uncaught.get() == null && Shizuku.listeners.isEmpty())
        }
    }
    test("attach races sticky registration") {
        Environment(initiallyAttached = false, attachDuringFirstRegistration = true).use { env ->
            Shizuku.environment = env
            check(ActualHelper(env).controlService() === FixtureService)
            check(env.attachObserved && env.binderReady && !env.prematureBind && env.bindCalls == 1)
            check(env.registrationCalls >= 2 && env.maximumListeners == 1 && Shizuku.listeners.isEmpty())
        }
    }
    test("delayed sticky flag publication") {
        Environment(initiallyAttached = false, attachDuringFirstRegistration = true,
            readyPublicationAfterRegistrations = 3).use { env ->
            Shizuku.environment = env
            check(ActualHelper(env).controlService() === FixtureService)
            check(env.registrationCalls >= 4 && env.maximumListeners == 1 && Shizuku.listeners.isEmpty())
            check(!env.prematureBind && env.bindCalls == 1)
        }
    }
    test("callback can precede readiness flag") {
        Environment(initiallyAttached = false).use { env ->
            Shizuku.environment = env
            val called = CountDownLatch(1)
            val listener = Shizuku.OnBinderReceivedListener {
                check(env.attachObserved && !env.binderReady)
                called.countDown()
            }
            Shizuku.addBinderReceivedListenerSticky(listener)
            env.runOnMainSync { env.completeAttach() }
            check(called.count == 0L && env.binderReady)
            Shizuku.removeBinderReceivedListener(listener)
        }
    }
    test("delayed service connection") {
        Environment(connectionDelayMs = 75).use { env ->
            Shizuku.environment = env
            check(ActualHelper(env).controlService() === FixtureService)
            check(env.bindCalls == 1 && env.uncaught.get() == null)
        }
    }
    test("bind exception") {
        val original = IllegalStateException("fixture-bind-rejection")
        Environment(bindFailure = original).use { env ->
            Shizuku.environment = env
            val failure = runCatching { ActualHelper(env).controlService() }.exceptionOrNull()
            check(failure === original) { "bind exception did not reach instrumentation thread unchanged" }
            check(env.uncaught.get() == null && env.bindCalls == 1 && Shizuku.listeners.isEmpty())
        }
    }
    test("death before bind is reported without retry") {
        Environment(dieBeforeBind = true).use { env ->
            Shizuku.environment = env
            val failure = runCatching { ActualHelper(env).controlService() }.exceptionOrNull()
            check(failure is IllegalStateException && failure.message == "fixture binder has not attached")
            check(env.uncaught.get() == null && env.bindCalls == 1 && Shizuku.listeners.isEmpty())
        }
    }
    test("missing attach keeps readiness deadline") {
        Environment(initiallyAttached = false).use { env ->
            Shizuku.environment = env
            val start = SystemClock.uptimeMillis()
            val failure = runCatching { ActualHelper(env).controlService() }.exceptionOrNull()
            val elapsed = SystemClock.uptimeMillis() - start
            check(failure is IllegalStateException && failure.message == "Shizuku application attach timed out")
            check(elapsed >= 9900 && elapsed < 20000) { "readiness timeout changed: $elapsed ms" }
            check(env.bindCalls == 0 && env.maximumListeners == 1 && Shizuku.listeners.isEmpty())
        }
    }
    test("interrupted readiness removes listener") {
        Environment(initiallyAttached = false).use { env ->
            Shizuku.environment = env
            env.interruptionAtAwait = true
            val failure = runCatching { ActualHelper(env).controlService() }.exceptionOrNull()
            Thread.interrupted()
            check(failure is InterruptedException)
            check(env.bindCalls == 0 && Shizuku.listeners.isEmpty())
        }
    }
    check(failures == 0) { "$failures controlService fixture checks failed" }
}
