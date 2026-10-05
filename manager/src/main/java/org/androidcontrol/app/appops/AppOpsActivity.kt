package org.androidcontrol.app.appops

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateFormat
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.androidcontrol.app.R
import org.androidcontrol.app.app.AppBarActivity
import org.androidcontrol.app.utils.UserHandleCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.Date

class AppOpsActivity : AppBarActivity() {
    private var user = UserHandleCompat.myUserId()
    private var selectedPackage: String? = null
    private var apps = emptyList<JSONObject>()
    private var operations = emptyList<JSONObject>()
    private var shownOps = emptyList<JSONObject>()
    private var rules = JSONObject().put("enabled", false).put("ops", JSONArray()).put("user", user)
    private lateinit var status: TextView
    private lateinit var search: EditText
    private lateinit var list: RecyclerView
    private lateinit var controls: LinearLayout
    private var busy = false
    private var generation = 0
    private val adapter = OpsAdapter()
    private var showAll = false
    private var group: String? = null
    private val chosen = linkedSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.appops_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(12))
        }
        status = TextView(this).apply { text = getString(R.string.appops_start_hint) }
        root.addView(status)
        controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val top = LinearLayout(this)
        button(top, R.string.appops_user) { selectUser() }
        button(top, R.string.appops_choose_app) { selectApp() }
        button(top, R.string.appops_refresh) { loadApp() }
        controls.addView(top)
        search = EditText(this).apply {
            hint = getString(R.string.appops_filter)
            setSingleLine()
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) { }
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { filter() }
                override fun afterTextChanged(s: Editable?) { }
            })
        }
        controls.addView(search)
        val filters = LinearLayout(this)
        button(filters, R.string.appops_group) { selectGroup() }
        filters.addView(CheckBox(this).apply {
            setText(R.string.appops_all_ops)
            setOnCheckedChangeListener { _, checked -> showAll = checked; filter() }
        })
        controls.addView(filters)
        val actions = LinearLayout(this)
        button(actions, R.string.appops_restrict) { restrictSelected() }
        button(actions, R.string.appops_rules) { editRules() }
        button(actions, R.string.appops_backup) { backupMenu() }
        controls.addView(actions)
        root.addView(controls)
        list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@AppOpsActivity)
            adapter = this@AppOpsActivity.adapter
        }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root, androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
            behavior = com.google.android.material.appbar.AppBarLayout.ScrollingViewBehavior()
        })
        loadApps()
    }

    private fun dp(n: Int) = (resources.displayMetrics.density * n).toInt()
    private fun button(parent: LinearLayout, text: Int, action: () -> Unit) {
        parent.addView(Button(this).apply {
            setText(text)
            setOnClickListener { if (!busy) action() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }
    private fun jsonList(array: JSONArray) = (0 until array.length()).map { array.getJSONObject(it) }
    private fun task(work: () -> Any, done: (Any) -> Unit) {
        if (busy) return
        busy = true
        list.isEnabled = false
        status.text = getString(R.string.appops_working)
        val token = ++generation
        AppOpsClient.executor.execute {
            val result = runCatching(work)
            runOnUiThread {
                if (isFinishing || isDestroyed || token != generation) return@runOnUiThread
                busy = false
                list.isEnabled = true
                result.onSuccess(done).onFailure { error ->
                    status.text = getString(R.string.appops_error, error.message ?: error.javaClass.simpleName)
                }
            }
        }
    }
    private fun loadApps() {
        val requestedUser = user
        task({
            val loaded = AppOpsClient.request("apps", requestedUser) as JSONArray
            val saved = AppOpsClient.request("rules", requestedUser) as JSONObject
            loaded to saved
        }) { result ->
            @Suppress("UNCHECKED_CAST")
            val (loaded, saved) = result as Pair<JSONArray, JSONObject>
            apps = jsonList(loaded).sortedBy { it.getString("label").lowercase() }
            rules = saved
            selectedPackage = selectedPackage?.takeIf { pkg -> apps.any { it.getString("package") == pkg } }
            status.text = getString(R.string.appops_apps_loaded, apps.size, user)
            if (selectedPackage != null) loadApp() else selectApp()
        }
    }
    private fun selectUser() {
        task({ AppOpsClient.request("users", user) }) { result ->
            val ids = jsonList(result as JSONArray).map { it.getInt("id") }
            MaterialAlertDialogBuilder(this).setTitle(R.string.appops_user)
                .setItems(ids.map { getString(R.string.appops_user_number, it) }.toTypedArray()) { _, index ->
                    user = ids[index]
                    selectedPackage = null
                    operations = emptyList()
                    chosen.clear()
                    filter()
                    loadApps()
                }.show()
        }
    }
    private fun selectApp() {
        if (apps.isEmpty()) { status.text = getString(R.string.appops_no_apps); return }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, dp(16), 0) }
        val query = EditText(this).apply { hint = getString(R.string.appops_search_apps); setSingleLine() }
        val picker = android.widget.ListView(this)
        var matches = apps
        fun update(value: String) {
            matches = apps.filter { it.getString("label").contains(value, true) || it.getString("package").contains(value, true) }
            picker.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1,
                matches.map { "${it.getString("label")}\n${it.getString("package")}" })
        }
        root.addView(query)
        root.addView(picker, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(360)))
        val dialog = MaterialAlertDialogBuilder(this).setTitle(R.string.appops_choose_app).setView(root)
            .setNegativeButton(android.R.string.cancel, null).create()
        picker.setOnItemClickListener { _, _, index, _ ->
            selectedPackage = matches[index].getString("package")
            group = null
            chosen.clear()
            dialog.dismiss()
            loadApp()
        }
        query.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) { }
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { update(s.toString()) }
            override fun afterTextChanged(s: Editable?) { }
        })
        update("")
        dialog.show()
    }
    private fun loadApp() {
        val pkg = selectedPackage ?: return
        task({ AppOpsClient.request("ops", user, pkg) }) { result ->
            operations = jsonList(result as JSONArray)
            status.text = getString(R.string.appops_selection, pkg, user)
            filter()
        }
    }
    private fun filter() {
        val query = if (::search.isInitialized) search.text.toString() else ""
        shownOps = operations.filter {
            (showAll || it.getBoolean("relevant")) && (group == null || it.getString("group") == group) &&
                listOf(it.getString("name"), it.getString("permission"), it.getString("group")).any { value -> value.contains(query, true) }
        }
        adapter.notifyDataSetChanged()
    }
    private fun selectGroup() {
        val groups = operations.map { it.getString("group") }.distinct().sorted()
        val labels = listOf(getString(R.string.appops_all_groups)) + groups
        MaterialAlertDialogBuilder(this).setTitle(R.string.appops_group).setItems(labels.toTypedArray()) { _, index ->
            group = if (index == 0) null else groups[index - 1]
            filter()
        }.show()
    }
    private fun modeLabel(mode: Int): String = getString(when (mode) {
        0 -> R.string.appops_allow
        1 -> R.string.appops_ignore
        2 -> R.string.appops_deny
        3 -> R.string.appops_default
        4 -> R.string.appops_foreground
        else -> R.string.appops_unknown
    })
    private fun changeMode(op: JSONObject) {
        if (busy) return
        val pkg = selectedPackage ?: return
        val modes = if (Build.VERSION.SDK_INT >= 29) (0..4).toList() else (0..3).toList()
        MaterialAlertDialogBuilder(this).setTitle(op.getString("name"))
            .setSingleChoiceItems(modes.map(::modeLabel).toTypedArray(), modes.indexOf(op.getInt("mode"))) { dialog, index ->
                dialog.dismiss()
                task({ AppOpsClient.request("set", user, pkg) { it.put("op", op.getString("name")).put("mode", modes[index]) } }) { result ->
                    val reply = result as JSONObject
                    val masked = reply.getInt("effective") != reply.getInt("stored")
                    loadApp()
                    if (masked) MaterialAlertDialogBuilder(this).setMessage(R.string.appops_uid_override).setPositiveButton(android.R.string.ok, null).show()
                }
            }.setNegativeButton(android.R.string.cancel, null).show()
    }
    private fun restrictSelected() {
        val pkg = selectedPackage ?: return
        if (chosen.isEmpty()) { status.text = getString(R.string.appops_select_ops); return }
        val selected = JSONArray(chosen.toList())
        MaterialAlertDialogBuilder(this).setTitle(R.string.appops_restrict)
            .setMessage(getString(R.string.appops_restrict_confirm, chosen.size, pkg))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                task({ AppOpsClient.request("restrict", user, pkg) { it.put("ops", selected) } }) { result ->
                    val data = result as JSONObject
                    showResults(getString(R.string.appops_applied, data.getInt("applied")), data.getJSONArray("failures"))
                    loadApp()
                }
            }.setNegativeButton(android.R.string.cancel, null).show()
    }
    private fun editRules() {
        if (operations.isEmpty()) { status.text = getString(R.string.appops_choose_app); return }
        val names = operations.map { it.getString("name") }.distinct()
        val selected = mutableSetOf<String>()
        val saved = rules.getJSONArray("ops")
        for (i in 0 until saved.length()) selected.add(saved.getString(i))
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, dp(16), 0) }
        val enabled = CheckBox(this).apply {
            setText(R.string.appops_auto_new)
            isChecked = rules.optInt("user") == user && rules.getBoolean("enabled")
        }
        root.addView(TextView(this).apply { setText(R.string.appops_rules_help) })
        root.addView(enabled)
        val picker = android.widget.ListView(this).apply {
            choiceMode = android.widget.ListView.CHOICE_MODE_MULTIPLE
            adapter = ArrayAdapter(this@AppOpsActivity, android.R.layout.simple_list_item_multiple_choice, names)
        }
        names.forEachIndexed { i, name -> picker.setItemChecked(i, name in selected) }
        picker.setOnItemClickListener { _, _, i, _ -> if (picker.isItemChecked(i)) selected.add(names[i]) else selected.remove(names[i]) }
        root.addView(picker, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(320)))
        MaterialAlertDialogBuilder(this).setTitle(getString(R.string.appops_rule_user, user)).setView(root)
            .setPositiveButton(R.string.appops_save) { _, _ ->
                val proposed = JSONObject().put("user", user).put("enabled", enabled.isChecked).put("ops", JSONArray(selected.toList()))
                task({ AppOpsClient.request("saveRules", user) { it.put("rules", proposed) } }) {
                    rules = it as JSONObject
                    status.text = getString(R.string.appops_rules_saved)
                }
            }.setNegativeButton(android.R.string.cancel, null).show()
    }
    private fun backupMenu() {
        MaterialAlertDialogBuilder(this).setTitle(R.string.appops_backup)
            .setItems(arrayOf(getString(R.string.appops_export), getString(R.string.appops_import))) { _, index ->
                if (index == 0) startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE); type = "application/json"; putExtra(Intent.EXTRA_TITLE, "androidcontrol-appops-user-$user.json")
                }, 501) else startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
                }, 502)
            }.show()
    }
    @Deprecated("Legacy activity result is used consistently with the existing manager")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return
        if (requestCode == 501) {
            val exportUser = user
            val exportApps = apps.toList()
            task({
                val records = JSONArray()
                exportApps.forEach { app ->
                    val snapshot = AppOpsClient.request("snapshot", exportUser, app.getString("package")) as JSONObject
                    if (snapshot.getJSONArray("ops").length() > 0) records.put(snapshot)
                }
                val backup = JSONObject().put("format", "androidcontrol-appops").put("version", 2)
                    .put("user", exportUser).put("time", System.currentTimeMillis()).put("apps", records)
                val bytes = backup.toString(2).toByteArray(Charsets.UTF_8)
                require(bytes.size <= AppOpsBackup.MAX_BYTES) { "Backup exceeds 4 MiB" }
                (contentResolver.openOutputStream(uri, "wt") ?: error("Cannot open backup destination")).use { it.write(bytes) }
                records.length()
            }) { status.text = getString(R.string.appops_exported, it as Int) }
        } else if (requestCode == 502) {
            val pkg = selectedPackage ?: run { status.text = getString(R.string.appops_choose_app); return }
            task({
                val bytes = (contentResolver.openInputStream(uri) ?: error("Cannot open backup")).use {
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (output.size() <= AppOpsBackup.MAX_BYTES) {
                        val count = it.read(buffer, 0, minOf(buffer.size, AppOpsBackup.MAX_BYTES + 1 - output.size()))
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
                require(bytes.size <= AppOpsBackup.MAX_BYTES) { "Backup exceeds 4 MiB" }
                val root = JSONObject(bytes.toString(Charsets.UTF_8))
                val catalog = AppOpsClient.request("ops", user, pkg) as JSONArray
                val changes = AppOpsBackup.parse(root.toString(), catalog)
                require(Build.VERSION.SDK_INT >= 29 || changes.none { it.mode == 4 }) { "Foreground mode requires Android 10 or later" }
                Triple(changes, root.optInt("user", -1), root.has("opbacks"))
            }) { result ->
                @Suppress("UNCHECKED_CAST")
                val (changes, backupUser, legacy) = result as Triple<List<AppOpsBackup.Change>, Int, Boolean>
                MaterialAlertDialogBuilder(this).setTitle(R.string.appops_import)
                    .setMessage(getString(R.string.appops_import_confirm, changes.size, if (legacy) "AppOpsX" else "user $backupUser", user))
                    .setPositiveButton(android.R.string.ok) { _, _ -> restore(changes) }
                    .setNegativeButton(android.R.string.cancel, null).show()
            }
        }
    }
    private fun restore(changes: List<AppOpsBackup.Change>) {
        val restoreUser = user
        task({
            val installed = jsonList(AppOpsClient.request("apps", restoreUser) as JSONArray).map { it.getString("package") }.toSet()
            require(changes.all { it.pkg in installed }) { "Backup includes packages not installed for the selected user; no changes applied" }
            val failures = JSONArray()
            var applied = 0
            changes.forEach { change ->
                runCatching { AppOpsClient.request("set", restoreUser, change.pkg) { it.put("op", change.name).put("mode", change.mode) } }
                    .onSuccess { applied++ }
                    .onFailure { failures.put(JSONObject().put("package", change.pkg).put("op", change.name).put("error", it.message)) }
            }
            applied to failures
        }) { result ->
            @Suppress("UNCHECKED_CAST")
            val (applied, failures) = result as Pair<Int, JSONArray>
            showResults(getString(R.string.appops_applied, applied), failures)
            loadApp()
        }
    }
    private fun showResults(message: String, failures: JSONArray) {
        MaterialAlertDialogBuilder(this).setMessage(if (failures.length() == 0) message else "$message\n${failures.toString(2)}")
            .setPositiveButton(android.R.string.ok, null).show()
    }
    private inner class OpsAdapter : RecyclerView.Adapter<OpsHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): OpsHolder {
            val row = LinearLayout(this@AppOpsActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(8)) }
            val name = CheckBox(this@AppOpsActivity)
            val detail = TextView(this@AppOpsActivity).apply { setTextIsSelectable(true) }
            val mode = Button(this@AppOpsActivity)
            row.addView(name); row.addView(detail); row.addView(mode)
            return OpsHolder(row, name, detail, mode)
        }
        override fun getItemCount() = shownOps.size
        override fun onBindViewHolder(holder: OpsHolder, position: Int) {
            val op = shownOps[position]
            val name = op.getString("name")
            holder.name.text = name.removePrefix("android:")
            holder.name.setOnCheckedChangeListener(null)
            holder.name.isChecked = name in chosen
            holder.name.setOnCheckedChangeListener { _, selected -> if (selected) chosen.add(name) else chosen.remove(name) }
            val times = listOf("access" to R.string.appops_last_access, "reject" to R.string.appops_last_reject).mapNotNull { (key, label) ->
                op.getLong(key).takeIf { it > 0 }?.let { getString(label, DateFormat.getDateFormat(this@AppOpsActivity).format(Date(it)) + " " + DateFormat.getTimeFormat(this@AppOpsActivity).format(Date(it))) }
            }
            holder.detail.text = (listOf(op.getString("permission"), op.getString("group")) + times +
                getString(R.string.appops_effective_mode, modeLabel(op.getInt("effective")))).filter { it.isNotEmpty() }.joinToString("\n")
            holder.mode.text = modeLabel(op.getInt("mode"))
            holder.mode.setOnClickListener { changeMode(op) }
        }
    }
    private class OpsHolder(view: View, val name: CheckBox, val detail: TextView, val mode: Button) : RecyclerView.ViewHolder(view)
}
