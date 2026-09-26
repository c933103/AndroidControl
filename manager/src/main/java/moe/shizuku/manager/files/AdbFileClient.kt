package moe.shizuku.manager.files

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Looper
import moe.shizuku.manager.BuildConfig
import rikka.shizuku.Shizuku
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object AdbFileClient {

    private val lock = Any()

    @Volatile
    private var remote: IAdbFileService? = null

    @Volatile
    private var binding = false

    @Volatile
    private var connectionLatch = CountDownLatch(0)

    private val userServiceArgs =
        Shizuku.UserServiceArgs(
            ComponentName(BuildConfig.APPLICATION_ID, AdbFileService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("adb_files")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder?) {
            synchronized(lock) {
                remote =
                    if (binder != null && binder.pingBinder()) {
                        IAdbFileService.Stub.asInterface(binder)
                    } else {
                        null
                    }
                binding = false
                connectionLatch.countDown()
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            synchronized(lock) {
                remote = null
                binding = false
                connectionLatch.countDown()
            }
        }
    }

    @Throws(IOException::class)
    fun requireService(timeoutSeconds: Long = 8): IAdbFileService {
        remote?.let {
            if (it.asBinder().pingBinder()) return it
        }

        if (!Shizuku.pingBinder()) {
            throw IOException("Shizuku is not running")
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw IOException("Privileged file service cannot be connected from the main thread")
        }

        val latch: CountDownLatch
        synchronized(lock) {
            remote?.let {
                if (it.asBinder().pingBinder()) return it
            }

            if (!binding) {
                binding = true
                connectionLatch = CountDownLatch(1)
                try {
                    Shizuku.bindUserService(userServiceArgs, connection)
                } catch (t: Throwable) {
                    binding = false
                    throw IOException(t.message ?: t.javaClass.simpleName, t)
                }
            }
            latch = connectionLatch
        }

        if (!latch.await(timeoutSeconds, TimeUnit.SECONDS)) {
            throw IOException("Timed out connecting to privileged file service")
        }

        return remote?.takeIf { it.asBinder().pingBinder() }
            ?: throw IOException("Privileged file service is unavailable")
    }
}
