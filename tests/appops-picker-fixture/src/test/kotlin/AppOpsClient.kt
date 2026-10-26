// Test-only daemon: records profile-scoped calls and never contacts Binder or a live target.
package org.androidcontrol.app.appops
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executor
object AppOpsClient {
    data class Call(val operation: String, val user: Int, val pkg: String?)
    val calls = mutableListOf<Call>()
    val queue = java.util.ArrayDeque<Runnable>()
    val executor = Executor { queue.add(it) }
    var failApps = false
    fun reset() { calls.clear(); queue.clear(); failApps = false }
    fun catalog() = JSONArray("""[{"code":26,"switch":26,"name":"android:camera","mode":1,"effective":1,"relevant":true,"permission":"","group":"","access":0,"reject":0}]""")
    fun request(operation: String, user: Int, pkg: String? = null, customize: (JSONObject) -> Unit = {}): Any {
        calls.add(Call(operation, user, pkg))
        customize(JSONObject())
        return when (operation) {
            "apps" -> { check(!failApps) { "controlled enumeration failure" }; JSONArray().put(JSONObject().put("package", if (user == 10) "other.app" else "primary.app").put("label", "Fixture $user")) }
            "rules" -> JSONObject().put("enabled", false).put("ops", JSONArray()).put("user", user)
            "ops" -> catalog()
            "snapshot" -> JSONObject().put("package", pkg).put("ops", catalog())
            "set" -> JSONObject().put("stored", 1).put("effective", 1)
            "users" -> JSONArray().put(JSONObject().put("id", 0)).put(JSONObject().put("id", 10))
            else -> error("Unexpected fixture operation $operation")
        }
    }
}
