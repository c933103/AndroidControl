@file:Suppress("UNUSED_PARAMETER")
package rikka.shizuku
import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
object Shizuku {
    class UserServiceArgs(name: ComponentName) {
        fun daemon(value: Boolean) = this
        fun processNameSuffix(value: String) = this
        fun debuggable(value: Boolean) = this
        fun version(value: Int) = this
    }
    // Model the pinned API's cached multiplexer, including retention when unbind throws.
    class Multiplexer {
        val connections = mutableListOf<ServiceConnection>()
        fun connected(binder: IBinder) {
            val snapshot = synchronized(Shizuku) { connections.toList() }
            snapshot.forEach { it.onServiceConnected(ComponentName("fixture", "service"), binder) }
        }
    }
    private var current: Multiplexer? = null
    val callbacks = java.util.concurrent.LinkedBlockingQueue<ServiceConnection>()
    val registrations get() = synchronized(this) { current?.connections?.toList() ?: emptyList() }
    fun multiplexer() = synchronized(this) { checkNotNull(current) }
    @Volatile var running = true
    @Volatile var unbinds = 0
    @Volatile var failUnbind = false
    private val backend = object : IBinder {
        override val isBinderAlive get() = running
        override fun pingBinder() = Shizuku.pingBinder()
    }
    fun getBinder(): IBinder? = backend.takeIf { running }
    fun pingBinder(): Boolean {
        check(!java.lang.Boolean.getBoolean("adb.fixture.forbidPing")) { "Synchronous backend ping is forbidden" }
        return running
    }
    fun bindUserService(args: UserServiceArgs, connection: ServiceConnection) {
        synchronized(this) {
            val multiplexer = current ?: Multiplexer().also { current = it }
            multiplexer.connections.add(connection)
        }
        callbacks.add(connection)
    }
    fun unbindUserService(args: UserServiceArgs, connection: ServiceConnection, remove: Boolean) {
        check(!remove) { "Recovery must not terminate the remote service" }
        unbinds++
        check(!failUnbind) { "Controlled remote cleanup failure" }
        synchronized(this) {
            current?.connections?.clear()
            current = null
        }
    }
}
