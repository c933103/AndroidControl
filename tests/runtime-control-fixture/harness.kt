import java.util.concurrent.CopyOnWriteArraySet
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
    val bindFailure: Throwable? = null
) : AutoCloseable {
    val main = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "fixture-main") }
    private val timer = Executors.newSingleThreadScheduledExecutor()
    val uncaught = AtomicReference<Throwable?>()
    @Volatile var attachObserved = initiallyAttached
    @Volatile var bindCalls = 0
    @Volatile var prematureBind = false
    var interruptionAtAwait = false

    fun schedule(delay: Long, block: () -> Unit) {
        timer.schedule({ main.execute(block) }, delay, TimeUnit.MILLISECONDS)
    }
    fun openManager() {
        attachDelayMs?.let { delay ->
            schedule(delay) {
                attachObserved = true
                Shizuku.listeners.forEach { it.onBinderReceived() }
            }
        }
        if (interruptionAtAwait) Thread.currentThread().interrupt()
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
    val listeners = CopyOnWriteArraySet<OnBinderReceivedListener>()
    fun addBinderReceivedListenerSticky(listener: OnBinderReceivedListener) {
        listeners.add(listener)
        if (environment.attachObserved) environment.main.execute { listener.onBinderReceived() }
    }
    fun removeBinderReceivedListener(listener: OnBinderReceivedListener): Boolean = listeners.remove(listener)
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
