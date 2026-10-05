package org.androidcontrol.app.home

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemProperties
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.RequiresApi
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentActivity
import org.androidcontrol.app.Helps
import org.androidcontrol.app.R
import org.androidcontrol.app.adb.AdbPairingTutorialActivity
import org.androidcontrol.app.databinding.HomeItemContainerBinding
import org.androidcontrol.app.databinding.HomeStartWirelessAdbBinding
import org.androidcontrol.app.ktx.toHtml
import org.androidcontrol.app.starter.StarterActivity
import org.androidcontrol.app.utils.CustomTabsHelper
import org.androidcontrol.app.utils.EnvironmentUtils
import rikka.core.content.asActivity
import rikka.html.text.HtmlCompat
import rikka.recyclerview.BaseViewHolder
import rikka.recyclerview.BaseViewHolder.Creator
import java.net.Inet4Address

class StartWirelessAdbViewHolder(binding: HomeStartWirelessAdbBinding, root: View) :
    BaseViewHolder<Any?>(root) {

    companion object {
        val CREATOR = Creator<Any> { inflater: LayoutInflater, parent: ViewGroup? ->
            val outer = HomeItemContainerBinding.inflate(inflater, parent, false)
            val inner = HomeStartWirelessAdbBinding.inflate(inflater, outer.root, true)
            StartWirelessAdbViewHolder(inner, outer.root)
        }
    }

    init {
        binding.button1.setOnClickListener { v: View ->
            onAdbClicked(v.context)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            binding.button3.setOnClickListener { v: View ->
                CustomTabsHelper.launchUrlOrCopy(v.context, Helps.ADB_ANDROID11.get())
            }
            binding.button2.setOnClickListener { v: View ->
                onPairClicked(v.context)
            }
            binding.text1.movementMethod = LinkMovementMethod.getInstance()
            binding.text1.text = context.getString(R.string.home_wireless_adb_description)
                .toHtml(HtmlCompat.FROM_HTML_OPTION_TRIM_WHITESPACE)
        } else {
            binding.text1.text = context.getString(R.string.home_wireless_adb_description_pre_11)
                .toHtml(HtmlCompat.FROM_HTML_OPTION_TRIM_WHITESPACE)
            binding.button2.isVisible = false
            binding.button3.isVisible = false
        }
    }

    override fun onBind(payloads: MutableList<Any>) {
        super.onBind(payloads)
    }

    private fun onAdbClicked(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            AdbDialogFragment().show(context.asActivity<FragmentActivity>().supportFragmentManager)
            return
        }

        val port = EnvironmentUtils.getAdbTcpPort()
        if (port > 0) {
            val host = "127.0.0.1"
            val intent = Intent(context, StarterActivity::class.java).apply {
                putExtra(StarterActivity.EXTRA_IS_ROOT, false)
                putExtra(StarterActivity.EXTRA_HOST, host)
                putExtra(StarterActivity.EXTRA_PORT, port)
            }
            context.startActivity(intent)
        } else {
            WadbNotEnabledDialogFragment().show(context.asActivity<FragmentActivity>().supportFragmentManager)
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun onPairClicked(context: Context) {
        if ((context.display?.displayId ?: -1) > 0) {
            // Running in a multi-display environment (e.g., Windows Subsystem for Android),
            // pairing dialog can be displayed simultaneously with Shizuku.
            // Input from notification is harder to use under this situation.
            AdbPairDialogFragment().show(context.asActivity<FragmentActivity>().supportFragmentManager)
        } else {
            context.startActivity(Intent(context, AdbPairingTutorialActivity::class.java))
        }
    }
}
