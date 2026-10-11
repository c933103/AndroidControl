// SPDX-FileCopyrightText: 2026 AndroidControl contributors
// SPDX-License-Identifier: Apache-2.0

package org.androidcontrol.app.recording

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import androidx.documentfile.provider.DocumentFile
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.androidcontrol.app.R
import org.androidcontrol.app.app.AppBarActivity
import org.androidcontrol.app.databinding.CallRecordingActivityBinding
import rikka.shizuku.Shizuku

/** First-party AndroidControl screen; upstream code remains visibly attributed. */
class CallRecordingActivity : AppBarActivity() {
    private lateinit var binding: CallRecordingActivityBinding
    private lateinit var preferences: CallRecordingPreferences

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refresh()
    }

    companion object {
        private const val REQUEST_FOLDER = 529
        private const val REQUEST_PHONE = 530
        private const val REQUEST_NOTIFICATIONS = 531
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.call_record_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding = CallRecordingActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)
        preferences = CallRecordingPreferences(this)

        binding.callRecordCredits.text = HtmlCompat.fromHtml(
            getString(R.string.call_record_upstream_notice), HtmlCompat.FROM_HTML_MODE_LEGACY
        )
        binding.callRecordCredits.movementMethod = LinkMovementMethod.getInstance()

        binding.callRecordTerms.isChecked = preferences.termsAccepted
        binding.callRecordTerms.setOnCheckedChangeListener { _, checked ->
            preferences.termsAccepted = checked
            if (!checked) {
                preferences.automatic = false
                if (CallRecordingService.activeOrPreparing) requestStop()
            }
            refresh()
        }

        binding.callRecordPickFolder.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
            startActivityForResult(intent, REQUEST_FOLDER)
        }

        binding.callRecordCodec.check(if (preferences.codec == "aac") {
            R.id.call_record_codec_aac
        } else R.id.call_record_codec_opus)
        binding.callRecordCodec.setOnCheckedChangeListener { _, checked ->
            preferences.codec = if (checked == R.id.call_record_codec_aac) "aac" else "opus"
        }

        binding.callRecordSource.check(if (preferences.source == "mic-voice-communication") {
            R.id.call_record_source_communication
        } else R.id.call_record_source_call)
        binding.callRecordSource.setOnCheckedChangeListener { _, checked ->
            preferences.source =
                if (checked == R.id.call_record_source_communication) "mic-voice-communication"
                else "voice-call"
        }

        binding.callRecordAutomatic.isChecked = preferences.automatic
        binding.callRecordAutomatic.setOnCheckedChangeListener { _, checked ->
            if (!checked) {
                preferences.automatic = false
            } else if (!checkConfiguration()) {
                preferences.automatic = false
                binding.callRecordAutomatic.isChecked = false
            } else if (!hasRequiredPermissions()) {
                binding.callRecordAutomatic.isChecked = false
                requestMissingPermission()
            } else {
                preferences.automatic = true
            }
            refresh()
        }

        binding.callRecordStart.setOnClickListener {
            if (!checkConfiguration()) return@setOnClickListener
            if (!hasRequiredPermissions()) {
                requestMissingPermission()
                return@setOnClickListener
            }
            if (!Shizuku.pingBinder()) {
                message(R.string.call_record_shizuku_needed)
                return@setOnClickListener
            }
            try {
                CallRecordingService.start(this)
                refresh()
            } catch (e: RuntimeException) {
                message(e.message ?: getString(R.string.call_record_start_failed))
            }
        }
        binding.callRecordStop.setOnClickListener { requestStop() }
        binding.callRecordFiles.setOnClickListener { showFiles() }
        refresh()
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this, stateReceiver, IntentFilter(CallRecordingService.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        refresh()
    }

    override fun onStop() {
        unregisterReceiver(stateReceiver)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    @Deprecated("Framework document-picker result handling")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_FOLDER || resultCode != Activity.RESULT_OK) return
        val folder = data?.data ?: return
        val mode = data.flags and (
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        val required = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        if ((mode and required) != required) {
            message(R.string.call_record_folder_failed)
            return
        }
        try {
            contentResolver.takePersistableUriPermission(folder, mode)
            val document = DocumentFile.fromTreeUri(this, folder)
            check(document?.isDirectory == true && document.canWrite()) { "Folder is not writable" }
            preferences.folder = folder
        } catch (e: Exception) {
            message(e.message ?: getString(R.string.call_record_folder_failed))
        }
        refresh()
    }

    private fun hasPhonePermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasNotificationPermission() =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasRequiredPermissions() = hasPhonePermission() && hasNotificationPermission()

    private fun requestMissingPermission() {
        when {
            !hasPhonePermission() -> requestPermissions(
                arrayOf(Manifest.permission.READ_PHONE_STATE), REQUEST_PHONE
            )
            !hasNotificationPermission() -> requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PHONE || requestCode == REQUEST_NOTIFICATIONS) {
            if (grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
                preferences.automatic = false
                message(if (requestCode == REQUEST_PHONE) R.string.call_record_phone_permission
                    else R.string.call_record_notification_permission)
            } else if (requestCode == REQUEST_PHONE && !hasNotificationPermission()) {
                requestMissingPermission()
            }
            refresh()
        }
    }

    private fun checkConfiguration(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            message(R.string.call_record_android_version)
            return false
        }
        if (!preferences.termsAccepted) {
            message(R.string.call_record_consent_needed)
            return false
        }
        if (preferences.folder == null) {
            message(R.string.call_record_folder_needed)
            return false
        }
        return true
    }

    private fun requestStop() {
        runCatching { CallRecordingService.stop(this) }
            .onFailure { message(it.message ?: getString(R.string.call_record_stop_failed)) }
        refresh()
    }

    private fun refresh() {
        if (!::binding.isInitialized || !::preferences.isInitialized) return
        binding.callRecordFolderLabel.text = preferences.folder?.toString()
            ?: getString(R.string.call_record_no_folder)
        binding.callRecordAutomatic.isChecked = preferences.automatic
        val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        val running = CallRecordingService.activeOrPreparing
        binding.callRecordStart.isEnabled = supported && !running
        binding.callRecordStop.isEnabled = running
        binding.callRecordFiles.isEnabled = preferences.folder != null
        binding.callRecordStatus.text = when (CallRecordingService.state) {
            CallRecordingService.State.STARTING -> getString(R.string.call_record_state_starting)
            CallRecordingService.State.RECORDING -> getString(R.string.call_record_state_recording)
            CallRecordingService.State.STOPPING -> getString(R.string.call_record_state_stopping)
            CallRecordingService.State.ERROR -> getString(
                R.string.call_record_state_error,
                CallRecordingService.lastError ?: getString(R.string.call_record_unknown_error)
            )
            CallRecordingService.State.IDLE -> if (!supported) {
                getString(R.string.call_record_android_version)
            } else if (!Shizuku.pingBinder()) {
                getString(R.string.call_record_shizuku_needed)
            } else getString(R.string.call_record_state_idle)
        }
    }

    private fun showFiles() {
        val folder = preferences.folder ?: return
        Thread({
            val found = runCatching {
                DocumentFile.fromTreeUri(this, folder)?.listFiles()
                    ?.filter { file ->
                        file.isFile && (file.name?.endsWith(".ogg", true) == true ||
                            file.name?.endsWith(".m4a", true) == true)
                    }
                    ?.sortedByDescending { it.lastModified() }
                    ?.take(100) ?: emptyList()
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (found.isFailure) {
                    message(found.exceptionOrNull()?.message ?: getString(R.string.call_record_folder_failed))
                    return@runOnUiThread
                }
                val files = found.getOrDefault(emptyList())
                if (files.isEmpty()) {
                    message(R.string.call_record_no_files)
                    return@runOnUiThread
                }
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.call_record_files_title)
                    .setItems(files.map { it.name ?: getString(R.string.call_record_file_unnamed) }.toTypedArray()) { _, index ->
                        val file = files[index]
                        val mime = if (file.name?.endsWith(".ogg", true) == true) "audio/ogg" else "audio/mp4"
                        val intent = Intent(Intent.ACTION_VIEW)
                            .setDataAndType(file.uri, mime)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        runCatching { startActivity(intent) }.onFailure {
                            message(it.message ?: getString(R.string.call_record_no_player))
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null).show()
            }
        }, "AndroidControl-RecordingFiles").start()
    }

    private fun message(res: Int) = message(getString(res))

    private fun message(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }
}
