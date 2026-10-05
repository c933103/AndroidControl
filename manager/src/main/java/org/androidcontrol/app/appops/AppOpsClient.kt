package org.androidcontrol.app.appops

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import org.androidcontrol.app.BuildConfig
import org.androidcontrol.app.utils.UserHandleCompat
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object AppOpsClient {
    val executor = Executors.newSingleThreadExecutor()
    @Volatile private var remote: IAppOpsControlService? = null
    private val args = Shizuku.UserServiceArgs(ComponentName(BuildConfig.APPLICATION_ID, AppOpsService::class.java.name))
        .tag(if (UserHandleCompat.myUserId() == 0) AppOpsService::class.java.name
            else "appops-user-${UserHandleCompat.myUserId()}")
        .daemon(true).processNameSuffix("appops").debuggable(BuildConfig.DEBUG).version(BuildConfig.VERSION_CODE)

    /** Called off the UI thread. Each profile's daemon owns its optional install monitor after UI exit. */
    @Synchronized fun connect(): IAppOpsControlService {
        remote?.takeIf { it.asBinder().pingBinder() }?.let { return it }
        check(Shizuku.pingBinder()) { "Start AndroidControl's privileged service first" }
        val latch = CountDownLatch(1)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder?) {
                remote = binder?.let { IAppOpsControlService.Stub.asInterface(it) }
                latch.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) { remote = null }
        }
        Shizuku.bindUserService(args, connection)
        check(latch.await(15, TimeUnit.SECONDS)) { "AppOps service did not connect; check the privileged server" }
        return remote ?: error("AppOps service disconnected")
    }

    fun request(action: String, user: Int, pkg: String? = null, fill: (JSONObject) -> Unit = {}): Any {
        val input = JSONObject().put("action", action).put("user", user)
        if (pkg != null) input.put("package", pkg)
        fill(input)
        val reply = JSONObject(connect().execute(input.toString()))
        check(reply.getBoolean("ok")) { reply.optString("error", "AppOps request failed") }
        return reply.get("data")
    }

    fun startMonitor() { executor.execute { runCatching { connect() } } }
    fun onBinderDead() { remote = null }
}
