package org.androidcontrol.app.control

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import org.androidcontrol.app.BuildConfig
import rikka.shizuku.Shizuku
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

object OrientationControlClient {

    data class State(
        val available: Boolean = false,
        val forcedPortrait: Boolean? = null,
        val systemSupported: Boolean = false,
        val systemOverride: Boolean = false,
        val busy: Boolean = false,
        val targetStatus: String? = null,
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
            .daemon(true)
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
            publish(State(error = "Privileged control service disconnected"))
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
                val targetStatus = service.targetPortraitStatus
                var systemError: String? = null
                val supported = service.isSystemPortraitSupported
                val saved = service.hasSystemPortraitOverride()
                val forced = try { service.isForcePortraitEnabled() } catch (t: Throwable) {
                    systemError = t.message ?: t.javaClass.simpleName
                    null
                }
                publish(State(available = true, forcedPortrait = forced,
                    systemSupported = supported, systemOverride = saved,
                    targetStatus = targetStatus, error = systemError))
            } catch (t: Throwable) {
                remote = null
                publish(State(error = t.message ?: t.javaClass.simpleName))
            }
        }
    }

    fun toggle() {
        if (state.busy) return
        val service = remote
        if (service == null || !service.asBinder().pingBinder()) {
            pendingToggle = true
            connect()
            return
        }

        publish(state.copy(busy = true, error = null))
        executor.execute {
            try {
                val forced = service.toggleForcePortrait()
                val targetStatus = service.targetPortraitStatus
                publish(
                    state.copy(
                        available = true,
                        forcedPortrait = forced,
                        systemSupported = service.isSystemPortraitSupported,
                        systemOverride = service.hasSystemPortraitOverride(),
                        busy = false,
                        targetStatus = targetStatus,
                        error = null
                    )
                )
            } catch (t: Throwable) {
                publish(
                    state.copy(
                        available = true,
                        forcedPortrait = state.forcedPortrait,
                        systemOverride = runCatching { service.hasSystemPortraitOverride() }.getOrDefault(state.systemOverride),
                        busy = false,
                        error = t.message ?: t.javaClass.simpleName
                    )
                )
            }
        }
    }

    fun saveTargetPackage(value: String, callback: (String?) -> Unit) {
        val service = remote
        if (service == null) {
            mainHandler.post { callback("Start the privileged service first") }
            return
        }
        executor.execute {
            val failure = runCatching {
                val name = PortraitTarget.validate(value)
                service.validateTargetPackage(name)
                PortraitTarget.save(name)
            }.exceptionOrNull()
            mainHandler.post { callback(failure?.message) }
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
