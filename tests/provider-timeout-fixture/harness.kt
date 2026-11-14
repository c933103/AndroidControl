// SPDX-FileCopyrightText: 2026 AndroidControl contributors
// SPDX-License-Identifier: Apache-2.0
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class Bundle {
    var classLoader: ClassLoader? = null
    private val values = mutableMapOf<String, Any>()
    fun getString(key: String) = values[key] as? String
    @Suppress("UNCHECKED_CAST") fun <T> getParcelable(key: String) = values[key] as? T
    fun putParcelable(key: String, value: Any) { values[key] = value }
}
fun bundleOf(vararg values: Pair<String, Any>) = Bundle().apply {
    values.forEach { (key, value) -> putParcelable(key, value) }
}
class BinderContainer(val binder: Any?)
const val USER_SERVICE_ARG_TOKEN = "token"
val workerHandler = Any()
object LOGGER { fun e(error: Throwable, message: String) {} }
open class ShizukuProvider {
    fun disableAutomaticSuiInitialization() {}
    open fun onCreate() = true
    open fun call(method: String, arg: String?, extras: Bundle?): Bundle? = null
}
object Shizuku {
    interface OnBinderReceivedListener { fun onBinderReceived() }
    val listeners = CopyOnWriteArrayList<OnBinderReceivedListener>()
    var saved: OnBinderReceivedListener? = null
    var delivery: (OnBinderReceivedListener) -> Unit = {}
    var attachment: () -> Unit = {}
    val attachments = AtomicInteger()
    val serverBinder = Any()
    fun addBinderReceivedListenerSticky(listener: OnBinderReceivedListener, handler: Any) {
        listeners.add(listener)
        saved = listener
        delivery(listener)
    }
    fun removeBinderReceivedListener(listener: OnBinderReceivedListener) { listeners.remove(listener) }
    fun attachUserService(binder: Any, extras: Bundle) {
        attachments.incrementAndGet()
        attachment()
    }
    fun getBinder() = serverBinder
    fun reset() {
        Thread.interrupted()
        listeners.clear()
        saved = null
        delivery = {}
        attachment = {}
        attachments.set(0)
    }
}
fun invoke(): Bundle? = ShizukuManagerProvider().call("sendUserService", null,
    bundleOf(USER_SERVICE_ARG_TOKEN to "synthetic-token",
        "moe.shizuku.privileged.api.intent.extra.BINDER" to BinderContainer(Any())))
fun main() {
    var failures = 0
    fun test(name: String, block: () -> Unit) {
        Shizuku.reset()
        try { block(); println("PASS $name") }
        catch (failure: Throwable) { failures++; println("FAIL $name: ${failure.message}") }
        finally { Thread.interrupted() }
    }
    test("success and duplicate callback") {
        Shizuku.delivery = { it.onBinderReceived(); it.onBinderReceived() }
        val reply = invoke()
        check(reply?.getParcelable<BinderContainer>("moe.shizuku.privileged.api.intent.extra.BINDER")?.binder === Shizuku.serverBinder)
        check(Shizuku.attachments.get() == 1 && Shizuku.listeners.isEmpty())
    }
    test("attachment failure") {
        Shizuku.delivery = { it.onBinderReceived() }
        Shizuku.attachment = { error("synthetic attach failure") }
        check(invoke() == null && Shizuku.listeners.isEmpty())
    }
    test("timeout returns null and ignores queued delivery") {
        check(invoke() == null) { "timeout returned an apparent reply" }
        check(Shizuku.listeners.isEmpty()) { "timeout leaked listener" }
        Shizuku.saved!!.onBinderReceived()
        check(Shizuku.attachments.get() == 0) { "late callback started attachment" }
    }
    test("interruption retains flag and removes listener") {
        Thread.currentThread().interrupt()
        check(invoke() == null && Thread.currentThread().isInterrupted)
        check(Shizuku.listeners.isEmpty())
        Shizuku.saved!!.onBinderReceived()
        check(Shizuku.attachments.get() == 0)
    }
    test("registration failure removes listener") {
        Shizuku.delivery = { error("synthetic registration failure") }
        check(invoke() == null && Shizuku.listeners.isEmpty())
    }
    test("in-flight attachment does not change returned timeout") {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = AtomicReference<Thread>()
        Shizuku.attachment = { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
        Shizuku.delivery = { listener ->
            worker.set(Thread { listener.onBinderReceived() }.apply { start() })
            check(entered.await(2, TimeUnit.SECONDS))
        }
        try {
            check(invoke() == null) { "in-flight timeout returned a reply" }
            check(Shizuku.listeners.isEmpty())
        } finally {
            release.countDown()
            worker.get().join(2000)
        }
        check(!worker.get().isAlive && Shizuku.attachments.get() == 1)
    }
    check(failures == 0) { "$failures provider cases failed" }
}
