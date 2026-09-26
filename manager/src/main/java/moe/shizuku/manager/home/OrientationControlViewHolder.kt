package moe.shizuku.manager.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import moe.shizuku.manager.R
import moe.shizuku.manager.control.OrientationControlClient
import moe.shizuku.manager.databinding.HomeItemContainerBinding
import moe.shizuku.manager.databinding.HomeOrientationControlBinding
import moe.shizuku.manager.model.ServiceStatus
import rikka.recyclerview.BaseViewHolder
import rikka.recyclerview.BaseViewHolder.Creator

class OrientationControlViewHolder(
    private val binding: HomeOrientationControlBinding,
    root: View
) : BaseViewHolder<ServiceStatus>(root) {

    companion object {
        val CREATOR = Creator<ServiceStatus> { inflater: LayoutInflater, parent: ViewGroup? ->
            val outer = HomeItemContainerBinding.inflate(inflater, parent, false)
            val inner = HomeOrientationControlBinding.inflate(inflater, outer.root, true)
            OrientationControlViewHolder(inner, outer.root)
        }
    }

    private val stateListener: (OrientationControlClient.State) -> Unit = { state ->
        render(state)
    }

    init {
        binding.button1.setOnClickListener {
            binding.button1.isEnabled = false
            binding.text1.setText(R.string.home_orientation_description_working)
            OrientationControlClient.toggle()
        }

        binding.button2.setOnClickListener {
            binding.button2.isEnabled = false
            binding.text2.setText(R.string.home_orientation_recovery_running)
            OrientationControlClient.recoverLegacyState()
        }
    }

    override fun onBind() {
        OrientationControlClient.removeListener(stateListener)
        OrientationControlClient.addListener(stateListener)

        if (data.isRunning) {
            OrientationControlClient.connect()
        } else {
            render(OrientationControlClient.State())
        }
    }

    override fun onRecycle() {
        OrientationControlClient.removeListener(stateListener)
        super.onRecycle()
    }

    private fun render(state: OrientationControlClient.State) {
        if (!data.isRunning) {
            binding.button1.isEnabled = false
            binding.button1.setText(R.string.home_orientation_force)
            binding.button2.isEnabled = false
            binding.text1.text = context.getString(
                R.string.home_status_service_not_running,
                context.getString(R.string.app_name)
            )
            binding.text2.setText(R.string.home_orientation_recovery_description)
            return
        }

        if (!state.available) {
            if (state.error != null) {
                binding.button1.isEnabled = true
                binding.button1.setText(R.string.home_orientation_retry)
                binding.button2.isEnabled = false
                binding.text1.text = context.getString(
                    R.string.home_orientation_description_error,
                    state.error
                )
            } else {
                binding.button1.isEnabled = false
                binding.button1.setText(R.string.home_orientation_force)
                binding.button2.isEnabled = false
                binding.text1.setText(R.string.home_orientation_description_connecting)
            }
            return
        }

        val forced = state.forcedPortrait == true
        binding.button1.isEnabled = !state.recoveryRunning
        binding.button1.setText(
            if (forced) R.string.home_orientation_restore else R.string.home_orientation_force
        )
        binding.text1.setText(
            when {
                state.error != null -> R.string.home_orientation_description_error_short
                forced -> R.string.home_orientation_description_forced
                else -> R.string.home_orientation_description_normal
            }
        )

        binding.button2.isEnabled = !state.recoveryRunning && !forced
        binding.text2.text =
            when {
                state.recoveryRunning ->
                    context.getString(R.string.home_orientation_recovery_running)

                state.recoveryError != null ->
                    context.getString(
                        R.string.home_orientation_recovery_error,
                        state.recoveryError
                    )

                state.recoveredPackages != null ->
                    context.getString(
                        R.string.home_orientation_recovery_done,
                        state.recoveredPackages
                    )

                else ->
                    context.getString(R.string.home_orientation_recovery_description)
            }
    }
}
