package org.androidcontrol.app.files
import android.content.ComponentName
import android.os.IBinder
import android.os.Looper
import rikka.shizuku.Shizuku
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private class Service : IAdbFileService, IBinder {
    override fun asBinder(): IBinder = this
    override val isBinderAlive = true
    override fun pingBinder(): Boolean {
        check(!java.lang.Boolean.getBoolean("adb.fixture.forbidPing")) { "Synchronous service ping is forbidden" }
        return true
    }
}
private fun fails(block: () -> Unit): IOException {
    try { block() } catch (e: IOException) { return e }
    error("Expected IOException")
}
fun main() {
    Shizuku.running = false
    check(fails { AdbFileClient.requireService(0) }.message == "AndroidControl is not running")
    Shizuku.running = true
    Looper.onMainThread = true
    check(fails { AdbFileClient.requireService(0) }.message!!.contains("main thread"))
    Looper.onMainThread = false
    check(Shizuku.callbacks.isEmpty())
    val executor = Executors.newSingleThreadExecutor()
    try {
        val initial = executor.submit<IAdbFileService> { AdbFileClient.requireService(30) }
        val stale = checkNotNull(Shizuku.callbacks.poll(1, TimeUnit.SECONDS))
        check(fails { AdbFileClient.requireService(0) }.message!!.contains("Timed out"))
        // Fixed source wakes the old waiter. Baseline leaves it waiting, so use a
        // separate caller for the fresh-bind observation in both versions.
        val retryExecutor = Executors.newSingleThreadExecutor()
        try {
            val pending = retryExecutor.submit<IAdbFileService> { AdbFileClient.requireService(3) }
            val fresh = Shizuku.callbacks.poll(1, TimeUnit.SECONDS)
            check(fresh != null) { "AC-001 reproduced: retry did not issue a fresh bind after timeout" }
            val name = ComponentName("fixture", "service")
            stale.onServiceConnected(name, Service())
            stale.onServiceDisconnected(name)
            check(!pending.isDone) { "Abandoned callback completed replacement waiters" }
            val service = Service()
            fresh.onServiceConnected(name, service)
            check(pending.get(1, TimeUnit.SECONDS) === service)
            stale.onServiceDisconnected(name)
            check(AdbFileClient.requireService(0) === service)
            check(Shizuku.unbinds == 1)
            check(Shizuku.registrations.single() === fresh)
            check(runCatching { initial.get(1, TimeUnit.SECONDS) }.exceptionOrNull()?.cause is IOException)
            println("PASS: actual AdbFileClient timeout retry, stale connect/disconnect, registration isolation, main-thread/backend guards and nonblocking liveness")
        } finally { retryExecutor.shutdownNow() }
    } finally { executor.shutdownNow() }
}
