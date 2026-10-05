package org.androidcontrol.app.home

import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import org.androidcontrol.app.appops.AppOpsActivity
import org.androidcontrol.app.databinding.HomeAppOpsBinding
import org.androidcontrol.app.databinding.HomeItemContainerBinding
import org.androidcontrol.app.model.ServiceStatus
import rikka.recyclerview.BaseViewHolder
import rikka.recyclerview.BaseViewHolder.Creator

class AppOpsViewHolder(private val binding: HomeAppOpsBinding, root: View) : BaseViewHolder<ServiceStatus>(root) {
    companion object {
        val CREATOR = Creator<ServiceStatus> { inflater: LayoutInflater, parent: ViewGroup? ->
            val outer = HomeItemContainerBinding.inflate(inflater, parent, false)
            val inner = HomeAppOpsBinding.inflate(inflater, outer.root, true)
            AppOpsViewHolder(inner, outer.root)
        }
    }
    init {
        binding.openAppOps.setOnClickListener { context.startActivity(Intent(context, AppOpsActivity::class.java)) }
    }
    override fun onBind() { binding.openAppOps.isEnabled = data.isRunning }
}
