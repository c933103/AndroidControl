package org.androidcontrol.app.adb

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.androidcontrol.app.R
import org.androidcontrol.app.ShizukuSettings

object AdbKeyRecovery {
    fun show(activity: Activity, onReset: (() -> Unit)? = null) {
        MaterialAlertDialogBuilder(activity)
            .setMessage(R.string.adb_error_key_store)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.adb_reset_pairing) { _, _ ->
                MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.adb_reset_pairing)
                    .setMessage(R.string.adb_reset_pairing_confirm)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.adb_reset_pairing) { _, _ ->
                        Thread {
                            val failure = runCatching {
                                AdbKey.resetPairing(ShizukuSettings.getPreferences())
                            }.exceptionOrNull()
                            activity.runOnUiThread {
                                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                                if (failure != null) {
                                    Toast.makeText(activity, R.string.adb_error_key_store, Toast.LENGTH_LONG).show()
                                } else if (onReset != null) {
                                    onReset()
                                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                    activity.startActivity(Intent(activity, AdbPairingTutorialActivity::class.java))
                                }
                            }
                        }.start()
                    }.show()
            }.show()
    }
}
