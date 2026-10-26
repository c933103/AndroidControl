package org.androidcontrol.app.home

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import android.text.TextUtils
import android.widget.Toast
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import org.androidcontrol.app.R
import org.androidcontrol.app.databinding.HomeItemContainerBinding
import org.androidcontrol.app.databinding.HomeServerStatusBinding
import org.androidcontrol.app.model.ServiceStatus
import rikka.html.text.HtmlCompat
import rikka.html.text.toHtml
import rikka.recyclerview.BaseViewHolder
import rikka.recyclerview.BaseViewHolder.Creator
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuApiConstants
import rikka.shizuku.server.ServerConstants

class ServerStatusViewHolder(private val binding: HomeServerStatusBinding, root: View) :
    BaseViewHolder<ServiceStatus>(root) {

    companion object {
        val CREATOR = Creator<ServiceStatus> { inflater: LayoutInflater, parent: ViewGroup? ->
            val outer = HomeItemContainerBinding.inflate(inflater, parent, false)
            val inner = HomeServerStatusBinding.inflate(inflater, outer.root, true)
            ServerStatusViewHolder(inner, outer.root)
        }
    }

    private inline val textView get() = binding.text1
    private inline val summaryView get() = binding.text2
    private inline val iconView get() = binding.icon

    init {
        binding.openAdbSettings.setOnClickListener { view ->
            val context = view.context
            // Developer options contains both USB debugging and, on Android 11+,
            // wireless debugging. AndroidControl must not toggle either silently.
            val destinations = listOf(
                Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS,
                Settings.ACTION_SETTINGS
            )
            for ((index, action) in destinations.withIndex()) {
                try {
                    context.startActivity(Intent(action))
                    if (index > 0) {
                        Toast.makeText(context, R.string.home_adb_settings_fallback, Toast.LENGTH_LONG).show()
                    }
                    return@setOnClickListener
                } catch (_: ActivityNotFoundException) {
                    // Some OEMs remove the standard Developer options activity.
                } catch (_: SecurityException) {
                    // An OEM may restrict direct entry to this settings screen.
                }
            }
            Toast.makeText(context, R.string.home_adb_settings_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    override fun onBind() {
        val context = itemView.context
        val status = data
        val ok = status.isRunning
        val isRoot = status.uid == 0
        val apiVersion = status.apiVersion
        val patchVersion = status.patchVersion
        if (ok) {
            iconView.setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_server_ok_24dp))
        } else {
            iconView.setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_server_error_24dp))
        }
        val user = if (isRoot) "root" else "adb"
        val title = if (ok) {
            context.getString(R.string.home_status_service_is_running, context.getString(R.string.app_name))
        } else {
            context.getString(R.string.home_status_service_not_running, context.getString(R.string.app_name))
        }
        val summary = if (ok) {
            if (apiVersion != Shizuku.getLatestServiceVersion() || status.patchVersion != ShizukuApiConstants.SERVER_PATCH_VERSION) {
                context.getString(
                    R.string.home_status_service_version_update, user,
                    "${apiVersion}.${patchVersion}",
                    "${Shizuku.getLatestServiceVersion()}.${ShizukuApiConstants.SERVER_PATCH_VERSION}"
                )
            } else {
                context.getString(R.string.home_status_service_version, user, "${apiVersion}.${patchVersion}")
            }
        } else {
            ""
        }
        textView.text = title.toHtml(HtmlCompat.FROM_HTML_OPTION_TRIM_WHITESPACE)
        summaryView.text = summary.toHtml(HtmlCompat.FROM_HTML_OPTION_TRIM_WHITESPACE)
        if (TextUtils.isEmpty(summaryView.text)) {
            summaryView.visibility = View.GONE
        } else {
            summaryView.visibility = View.VISIBLE
        }
    }
}
