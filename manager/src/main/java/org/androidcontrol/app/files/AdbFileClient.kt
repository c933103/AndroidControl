package org.androidcontrol.app.files

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Looper
import org.androidcontrol.app.BuildConfig
import rikka.shizuku.Shizuku
import java.io.IOException

object AdbFileClient {
    private val userServiceArgs =
        Shizuku.UserServiceArgs(
            ComponentName(BuildConfig.APPLICATION_ID, AdbFileService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("adb_files")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)

    private val binding = RetryingServiceBinding<IAdbFileService, ServiceConnection>(
        isAlive = { it.asBinder().pingBinder() },
        connection = { connected, disconnected ->
            object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder?) {
                    connected(binder?.takeIf { it.pingBinder() }?.let { IAdbFileService.Stub.asInterface(it) })
                }
                override fun onServiceDisconnected(name: ComponentName) = disconnected()
                override fun onBindingDied(name: ComponentName) = disconnected()
                override fun onNullBinding(name: ComponentName) = disconnected()
            }
        },
        bind = { Shizuku.bindUserService(userServiceArgs, it) },
        unbind = { Shizuku.unbindUserService(userServiceArgs, it, false) },
    )

    @Throws(IOException::class)
    fun requireService(timeoutSeconds: Long = 8): IAdbFileService {
        binding.peek()?.let { return it }
        if (!Shizuku.pingBinder()) {
            throw IOException("AndroidControl is not running")
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw IOException("Privileged file service cannot be connected from the main thread")
        }
        return binding.requireService(timeoutSeconds)
    }
}
