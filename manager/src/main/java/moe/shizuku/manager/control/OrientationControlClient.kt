package moe.shizuku.manager.control

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import moe.shizuku.manager.BuildConfig
import rikka.shizuku.Shizuku
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

object OrientationControlClient {

    data class State(
        val available: Boolean = false,
        val forcedPortrait: Boolean? = null,
        val error: String? = null
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val listeners = CopyOnWriteArraySet<(State) -> Unit>()

    @Volatile
    private var remote: IAndroidControlService? = null

    @Volatile
    private var binding = false

    @Volatile
    private var pendingToggle = false

    @Volatile
    var state = State()
        private set

    private val userServiceArgs =
        Shizuku.UserServiceArgs(
            ComponentName(BuildConfig.APPLICATION_ID, AndroidControlService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("android_control")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(componentName: ComponentName, binder: IBinder?) {
            binding = false
            if (binder == null || !binder.pingBinder()) {
                remote = null
                publish(State(error = "Invalid privileged service binder"))
                return
            }

            remote = IAndroidControlService.Stub.asInterface(binder)
            if (pendingToggle) {
                pendingToggle = false
                toggle()
            } else {
                refresh()
            }
        }

        override fun onServiceDisconnected(componentName: ComponentName) {
            remote = null
            binding = false
            publish(State())
        }
    }

    fun addListener(listener: (State) -> Unit) {
        listeners.add(listener)
        mainHandler.post { listener(state) }
    }

    fun removeListener(listener: (State) -> Unit) {
        listeners.remove(listener)
    }

    fun connect() {
        if (!Shizuku.pingBinder()) {
            remote = null
            binding = false
            publish(State())
            return
        }

        val current = remote
        if (current != null && current.asBinder().pingBinder()) {
            refresh()
            return
        }

        synchronized(this) {
            if (binding) return
            binding = true
        }

        try {
            Shizuku.bindUserService(userServiceArgs, serviceConnection)
        } catch (t: Throwable) {
            binding = false
            remote = null
            publish(State(error = t.message ?: t.javaClass.simpleName))
        }
    }

    fun onShizukuBinderDead() {
        remote = null
        binding = false
        pendingToggle = false
        publish(State())
    }

    fun refresh() {
        val service = remote ?: run {
            connect()
            return
        }

        executor.execute {
            try {
                val forced = service.isForcePortraitEnabled
                publish(State(available = true, forcedPortrait = forced))
            } catch (t: Throwable) {
                remote = null
                publish(State(error = t.message ?: t.javaClass.simpleName))
            }
        }
    }

    fun toggle() {
        val service = remote
        if (service == null || !service.asBinder().pingBinder()) {
            pendingToggle = true
            connect()
            return
        }

        executor.execute {
            try {
                val forced = service.toggleForcePortrait()
                publish(State(available = true, forcedPortrait = forced))
            } catch (t: Throwable) {
                publish(
                    State(
                        available = true,
                        forcedPortrait = state.forcedPortrait,
                        error = t.message ?: t.javaClass.simpleName
                    )
                )
            }
        }
    }

    private fun publish(newState: State) {
        state = newState
        mainHandler.post {
            listeners.forEach { listener ->
                listener(newState)
            }
        }
    }
}
