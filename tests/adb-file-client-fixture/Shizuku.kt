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
    val callbacks = java.util.concurrent.LinkedBlockingQueue<ServiceConnection>()
    val registrations = mutableListOf<ServiceConnection>()
    var running = true
    var unbinds = 0
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
        synchronized(registrations) { registrations.add(connection) }
        callbacks.add(connection)
    }
    fun unbindUserService(args: UserServiceArgs, connection: ServiceConnection, remove: Boolean) {
        check(!remove) { "Recovery must not terminate the remote service" }
        // Pinned Shizuku clears the service's complete callback set, not just connection.
        synchronized(registrations) { registrations.clear() }
        unbinds++
    }
}
