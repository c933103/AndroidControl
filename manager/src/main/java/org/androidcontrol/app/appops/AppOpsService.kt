package org.androidcontrol.app.appops

import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.Process
import android.util.AtomicFile
import androidx.annotation.Keep
import org.androidcontrol.app.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.hidden.compat.PackageManagerApis
import rikka.hidden.compat.UserManagerApis
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** A separate UserService preserves root privilege; the portrait daemon drops to shell. */
@Keep
class AppOpsService @Keep constructor(serviceContext: Context) : IAppOpsControlService.Stub() {
    private val context = serviceContext.createPackageContext("com.android.shell", Context.CONTEXT_IGNORE_SECURITY)
    private val lock = Any()
    private val worker = Executors.newSingleThreadScheduledExecutor()
    private val appOps: AppOpsManager
    private val rulesFile = AtomicFile(File("/data/local/tmp/androidcontrol-appops-rules.json"))
    private var rules = JSONObject().put("user", 0).put("enabled", false).put("ops", JSONArray())
    private data class Op(val code: Int, val name: String, val permission: String?, val group: String,
        val switch: Int, val defaultMode: Int)
    private val catalog: List<Op>
    private var installMonitor: ScheduledFuture<*>? = null
    private var knownInstalls = emptyMap<String, Long>()

    init {
        HiddenApiBypass.addHiddenApiExemptions("")
        appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val count = AppOpsManager::class.java.getDeclaredField("_NUM_OP").apply { isAccessible = true }.getInt(null)
        catalog = (0 until count).map { code ->
            val name = runCatching { static("opToPublicName", code) as? String }.getOrNull()
                ?: static("opToName", code) as String
            val permission = static("opToPermission", code) as? String
            val group = permission?.let {
                runCatching { context.packageManager.getPermissionInfo(it, 0).group }.getOrNull()
            } ?: "Other operations"
            Op(code, name, permission, group, static("opToSwitch", code) as Int,
                static("opToDefaultMode", code) as Int)
        }.filter { it.name.isNotBlank() && it.switch in 0 until count }
        checkStateFiles()
        try {
            val saved = JSONObject(rulesFile.openRead().bufferedReader().use { it.readText() })
            validateRules(saved)
            rules = saved
        } catch (_: java.io.FileNotFoundException) { }
        catch (t: Throwable) {
            rules.put("error", "Saved automatic rules disabled: ${t.message}")
            android.util.Log.e("AndroidControlAppOps", "Invalid saved rule; automatic restrictions disabled", t)
        }
        if (rules.getBoolean("enabled")) {
            try { startInstallMonitor(installedIdentities(rules.getInt("user"))) }
            catch (t: Throwable) {
                rules.put("enabled", false).put("error", "Automatic rules disabled: ${failureMessage(t)}")
                android.util.Log.e("AndroidControlAppOps", "Could not start install monitor", t)
            }
        }
    }

    override fun destroy() {
        enforceManager()
        worker.shutdownNow()
        kotlin.system.exitProcess(0)
    }

    override fun execute(request: String): String {
        enforceManager()
        return synchronized(lock) {
            try {
                require(request.length <= 262144) { "Request is too large" }
                val input = JSONObject(request)
                val user = input.optInt("user", Process.myUid() / 100000)
                require(UserManagerApis.getUserIdsNoThrow().contains(user)) { "Unknown Android user" }
                val data: Any = when (input.getString("action")) {
                    "users" -> JSONArray().apply {
                        UserManagerApis.getUserIdsNoThrow().forEach { put(JSONObject().put("id", it)) }
                    }
                    "apps" -> JSONArray().apply {
                        packages(user).forEach { pi ->
                            val ai = pi.applicationInfo ?: return@forEach
                            put(JSONObject().put("package", pi.packageName).put("uid", ai.uid)
                                .put("system", ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0)
                                .put("label", runCatching { ai.loadLabel(context.packageManager).toString() }.getOrDefault(pi.packageName)))
                        }
                    }
                    "ops" -> operations(user, input.getString("package"))
                    "appsForOp" -> appsForOp(user, input.getString("op"))
                    "set" -> {
                        setMode(user, input.getString("package"), input.getString("op"), input.getInt("mode"))
                    }
                    "snapshot" -> snapshot(user, input.getString("package"))
                    "reset" -> resetPackage(user, input.getString("package"))
                    "rules" -> JSONObject(rules.toString())
                    "saveRules" -> {
                        val proposed = input.getJSONObject("rules")
                        validateRules(proposed)
                        require(proposed.getInt("user") == user) { "Rule user does not match selection" }
                        // Capture existing apps before enabling; never treat them
                        // as new installs after a rule edit or daemon restart.
                        val baseline = if (proposed.getBoolean("enabled")) installedIdentities(user) else emptyMap()
                        writeRules(proposed)
                        startInstallMonitor(baseline)
                        JSONObject(rules.toString())
                    }
                    "restrict" -> restrict(user, input.getString("package"), input.getJSONArray("ops"))
                    else -> error("Unsupported AppOps request")
                }
                JSONObject().put("ok", true).put("data", data).toString()
            } catch (t: Throwable) {
                val error = if (t is InvocationTargetException) t.targetException else t
                JSONObject().put("ok", false).put("error", error.message ?: error.javaClass.simpleName).toString()
            }
        }
    }

    private fun enforceManager() {
        val uid = Binder.getCallingUid()
        if (uid == Process.myUid()) return
        val info = PackageManagerApis.getApplicationInfoNoThrow(BuildConfig.APPLICATION_ID, 0, uid / 100000)
        if (info == null || info.uid != uid) throw SecurityException("Only AndroidControl may use this service")
    }
    private fun failureMessage(t: Throwable): String {
        val cause = if (t is InvocationTargetException) t.targetException else t
        return cause.message ?: cause.javaClass.simpleName
    }

    private fun installedIdentities(user: Int): Map<String, Long> {
        // Use the throwing API: a failed read must not erase the baseline and
        // cause a later successful scan to restrict every existing package.
        return requireNotNull(PackageManagerApis.getInstalledPackages(0, user)) {
            "Package enumeration unavailable for user $user"
        }.list
            .associate { it.packageName to it.firstInstallTime }
    }

    private fun startInstallMonitor(baseline: Map<String, Long>) {
        installMonitor?.cancel(false)
        installMonitor = null
        knownInstalls = baseline
        if (!rules.getBoolean("enabled")) return
        // UserServices lack an ActivityManager app record, so receiver
        // registration can silently fail. Scoped package snapshots work in
        // both shell and root mode, including other Android users.
        installMonitor = worker.scheduleWithFixedDelay({
            synchronized(lock) {
                if (rules.getBoolean("enabled")) {
                    try {
                        val user = rules.getInt("user")
                        require(UserManagerApis.getUserIdsNoThrow().contains(user)) { "Rule user no longer exists" }
                        val current = installedIdentities(user)
                        val added = current.filter { (pkg, firstInstall) ->
                            pkg != BuildConfig.APPLICATION_ID && knownInstalls[pkg] != firstInstall
                        }.keys
                        knownInstalls = current
                        rules.remove("error")
                        added.forEach { pkg ->
                            val result = runCatching { restrict(user, pkg, rules.getJSONArray("ops")) }
                                .getOrElse { error -> JSONObject().put("applied", 0).put("failures",
                                    JSONArray().put(JSONObject().put("error", failureMessage(error)))) }
                            rules.put("lastResult", result.put("package", pkg).put("time", System.currentTimeMillis()))
                            if (result.getJSONArray("failures").length() > 0) {
                                android.util.Log.e("AndroidControlAppOps", "New-app rule partly failed for $pkg: $result")
                            }
                        }
                    } catch (t: Throwable) {
                        rules.put("error", "New-app scan failed: ${failureMessage(t)}")
                        android.util.Log.e("AndroidControlAppOps", "New-app scan failed; baseline retained", t)
                    }
                }
            }
        }, 5, 5, TimeUnit.SECONDS)
    }

    private fun packages(user: Int) = PackageManagerApis.getInstalledPackagesNoThrow(PackageManager.GET_PERMISSIONS.toLong(), user)
    private fun packageInfo(user: Int, pkg: String): PackageInfo {
        require(pkg.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)*"))) { "Invalid package name" }
        return PackageManagerApis.getPackageInfoNoThrow(pkg, PackageManager.GET_PERMISSIONS.toLong(), user)
            ?: error("$pkg is not installed for user $user")
    }
    private fun static(name: String, code: Int): Any? = AppOpsManager::class.java
        .getDeclaredMethod(name, Int::class.javaPrimitiveType).apply { isAccessible = true }.invoke(null, code)
    private fun invoke(name: String, types: Array<Class<*>>, vararg args: Any?): Any? = AppOpsManager::class.java
        .getDeclaredMethod(name, *types).apply { isAccessible = true }.invoke(appOps, *args)
    private fun entries(uid: Int, pkg: String): List<Any> {
        val packages = invoke("getOpsForPackage", arrayOf(Int::class.javaPrimitiveType!!, String::class.java, IntArray::class.java), uid, pkg, null) as? List<*> ?: return emptyList()
        return packages.flatMap { record ->
            @Suppress("UNCHECKED_CAST")
            (record!!.javaClass.getMethod("getOps").invoke(record) as List<Any>)
        }
    }
    private fun entryInt(entry: Any, name: String) = entry.javaClass.getMethod(name).invoke(entry) as Int
    private fun entryTime(entry: Any?, name: String): Long = if (entry == null) 0L else
        runCatching { entry.javaClass.getMethod(name).invoke(entry) as Long }.getOrDefault(0L)
    private fun rawMode(op: Op, uid: Int, pkg: String): Int {
        val method = if (Build.VERSION.SDK_INT >= 29) "unsafeCheckOpRawNoThrow" else "checkOpNoThrow"
        return invoke(method, arrayOf(Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, String::class.java), op.code, uid, pkg) as Int
    }
    private fun operations(user: Int, pkg: String): JSONArray {
        val pi = packageInfo(user, pkg)
        val uid = pi.applicationInfo!!.uid
        val records = entries(uid, pkg).associateBy { entryInt(it, "getOp") }
        val permissions = pi.requestedPermissions?.toSet() ?: emptySet()
        return JSONArray().apply {
            catalog.forEach { op ->
                val own = records[op.switch]
                val usage = records[op.code]
                val mode = own?.let { entryInt(it, "getMode") } ?: catalog.first { it.code == op.switch }.defaultMode
                put(JSONObject().put("code", op.code).put("name", op.name).put("permission", op.permission ?: "")
                    .put("group", op.group).put("switch", op.switch).put("mode", mode)
                    .put("effective", rawMode(op, uid, pkg)).put("recorded", own != null || usage != null)
                    .put("relevant", (op.permission == null) || permissions.contains(op.permission) || own != null || usage != null)
                    .put("access", entryTime(usage, "getTime")).put("reject", entryTime(usage, "getRejectTime")))
            }
        }
    }
    private fun appsForOp(user: Int, name: String): JSONArray {
        val op = catalog.firstOrNull { it.name == name } ?: error("Unavailable operation")
        val records = invoke("getPackagesForOps", arrayOf(IntArray::class.java), intArrayOf(op.code, op.switch)) as? List<*> ?: emptyList<Any>()
        val byPackage = records.filter { record -> entryInt(record!!, "getUid") / 100000 == user }
            .associateBy { it!!.javaClass.getMethod("getPackageName").invoke(it) as String }
        return JSONArray().apply {
            packages(user).forEach { pi ->
                val ai = pi.applicationInfo ?: return@forEach
                val record = byPackage[pi.packageName]
                if (op.permission != null && pi.requestedPermissions?.contains(op.permission) != true && record == null) return@forEach
                val packageOps = record?.javaClass?.getMethod("getOps")?.invoke(record) as? List<*> ?: emptyList<Any>()
                val stored = packageOps.firstOrNull { entryInt(it!!, "getOp") == op.switch }?.let { entryInt(it, "getMode") }
                    ?: catalog.first { it.code == op.switch }.defaultMode
                put(JSONObject().put("package", pi.packageName).put("uid", ai.uid).put("mode", stored)
                    .put("effective", rawMode(op, ai.uid, pi.packageName))
                    .put("label", runCatching { ai.loadLabel(context.packageManager).toString() }.getOrDefault(pi.packageName)))
            }
        }
    }
    private fun setMode(user: Int, pkg: String, name: String, mode: Int): JSONObject {
        require(mode in 0..4 && (mode != 4 || Build.VERSION.SDK_INT >= 29)) { "Unsupported AppOps mode" }
        val op = catalog.firstOrNull { it.name == name } ?: error("Operation is unavailable on this Android version: $name")
        val uid = packageInfo(user, pkg).applicationInfo!!.uid
        invoke("setMode", arrayOf(Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, String::class.java, Int::class.javaPrimitiveType!!), op.code, uid, pkg, mode)
        val stored = entries(uid, pkg).firstOrNull { entryInt(it, "getOp") == op.switch }?.let { entryInt(it, "getMode") }
            ?: catalog.first { it.code == op.switch }.defaultMode
        check(stored == mode) { "Android did not retain the requested package mode (requested $mode, read $stored). This operation may be controlled by a runtime permission or device policy." }
        return JSONObject().put("stored", stored).put("effective", rawMode(op, uid, pkg))
    }
    private fun snapshot(user: Int, pkg: String): JSONObject {
        val uid = packageInfo(user, pkg).applicationInfo!!.uid
        val ops = JSONArray()
        entries(uid, pkg).forEach { record ->
            val code = entryInt(record, "getOp")
            val op = catalog.firstOrNull { it.code == code } ?: return@forEach
            if (op.switch == code) ops.put(JSONObject().put("name", op.name).put("mode", entryInt(record, "getMode")))
        }
        return JSONObject().put("package", pkg).put("ops", ops)
    }
    private fun resetPackage(user: Int, pkg: String): JSONObject {
        val ops = snapshot(user, pkg).getJSONArray("ops")
        val failures = JSONArray()
        for (i in 0 until ops.length()) {
            val name = ops.getJSONObject(i).getString("name")
            val op = catalog.first { it.name == name }
            runCatching { setMode(user, pkg, name, op.defaultMode) }
                .onFailure { failures.put(JSONObject().put("op", name).put("error", failureMessage(it))) }
        }
        return JSONObject().put("applied", ops.length() - failures.length()).put("failures", failures)
    }
    private fun restrict(user: Int, pkg: String, selected: JSONArray): JSONObject {
        val relevant = operations(user, pkg)
        val names = (0 until selected.length()).map { selected.getString(it) }.toSet()
        val done = mutableSetOf<Int>()
        val failures = JSONArray()
        for (i in 0 until relevant.length()) {
            val op = relevant.getJSONObject(i)
            if (op.getString("name") !in names || !op.getBoolean("relevant") || !done.add(op.getInt("switch"))) continue
            runCatching { setMode(user, pkg, op.getString("name"), AppOpsManager.MODE_IGNORED) }
                .onFailure { failures.put(JSONObject().put("op", op.getString("name")).put("error", failureMessage(it))) }
        }
        return JSONObject().put("applied", done.size - failures.length()).put("failures", failures)
    }
    private fun validateRules(value: JSONObject) {
        require(UserManagerApis.getUserIdsNoThrow().contains(value.getInt("user"))) { "Rule user no longer exists" }
        value.getBoolean("enabled")
        val ops = value.getJSONArray("ops")
        require(ops.length() <= catalog.size)
        for (i in 0 until ops.length()) require(catalog.any { it.name == ops.getString(i) }) { "Unknown rule operation" }
        require(!value.getBoolean("enabled") || ops.length() > 0) { "Select operations before enabling automatic restrictions" }
    }
    private fun checkStateFiles() {
        for (suffix in listOf("", ".bak", ".new")) {
            try {
                val file = rulesFile.baseFile.path + suffix
                val stat = android.system.Os.lstat(file)
                check(android.system.OsConstants.S_ISREG(stat.st_mode)) { "Invalid rules file" }
                check(stat.st_uid == Process.myUid() || stat.st_uid == 2000) { "Invalid rules owner" }
                if (Process.myUid() == 0) android.system.Os.chown(file, 2000, 2000)
            } catch (e: android.system.ErrnoException) {
                if (e.errno != android.system.OsConstants.ENOENT) throw e
            }
        }
    }
    private fun writeRules(value: JSONObject) {
        val stream = rulesFile.startWrite()
        try {
            stream.write(value.toString().toByteArray(Charsets.UTF_8))
            rulesFile.finishWrite(stream)
            android.system.Os.chmod(rulesFile.baseFile.path, 0b110000000)
            if (Process.myUid() == 0) android.system.Os.chown(rulesFile.baseFile.path, 2000, 2000)
            rules = JSONObject(value.toString())
        } catch (t: Throwable) {
            rulesFile.failWrite(stream)
            throw t
        }
    }
}
