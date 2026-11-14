// SPDX-FileCopyrightText: 2026 AndroidControl contributors
// SPDX-License-Identifier: Apache-2.0

package org.androidcontrol.app.home

import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import org.androidcontrol.app.databinding.HomeCallRecordingBinding
import org.androidcontrol.app.databinding.HomeItemContainerBinding
import org.androidcontrol.app.model.ServiceStatus
import org.androidcontrol.app.recording.CallRecordingActivity
import rikka.recyclerview.BaseViewHolder

class CallRecordingViewHolder(
    private val binding: HomeCallRecordingBinding, root: View
) : BaseViewHolder<ServiceStatus>(root) {
    companion object {
        val CREATOR = Creator<ServiceStatus> { inflater: LayoutInflater, parent: ViewGroup? ->
            val container = HomeItemContainerBinding.inflate(inflater, parent, false)
            val content = HomeCallRecordingBinding.inflate(inflater, container.root, true)
            CallRecordingViewHolder(content, container.root)
        }
    }

    init {
        binding.openCallRecording.setOnClickListener {
            context.startActivity(Intent(context, CallRecordingActivity::class.java))
        }
    }

    override fun onBind() {
        // Configuration and legal information remain accessible even if
        // Shizuku has not been started or Android is unsupported.
        binding.openCallRecording.isEnabled = true
    }
}
