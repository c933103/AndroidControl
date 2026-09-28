package moe.shizuku.manager.home

import android.content.Intent
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import moe.shizuku.manager.R
import moe.shizuku.manager.control.OrientationControlClient
import moe.shizuku.manager.control.PortraitTarget
import moe.shizuku.manager.control.TargetPortraitDisplayActivity
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

    private val stateListener: (OrientationControlClient.State) -> Unit = { render(it) }

    init {
        binding.button1.setOnClickListener { view ->
            view.context.startActivity(Intent(view.context, TargetPortraitDisplayActivity::class.java)
                .putExtra("portrait_target_package", PortraitTarget.get()))
        }
        binding.changeTarget.setOnClickListener { editTarget() }
        binding.systemPortrait.setOnClickListener { OrientationControlClient.toggle() }
    }

    private fun editTarget() {
        val field = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine()
            setText(PortraitTarget.get())
            hint = context.getString(R.string.orientation_target_hint)
            selectAll()
        }
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.orientation_change_target)
            .setMessage(R.string.orientation_target_help)
            .setView(field)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.orientation_target_save, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = field.text.toString()
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                OrientationControlClient.saveTargetPackage(value) { error ->
                    if (!dialog.isShowing) return@saveTargetPackage
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                    if (error != null) field.error = error else {
                        render(OrientationControlClient.state)
                        dialog.dismiss()
                    }
                }
            }
        }
        dialog.show()
    }

    override fun onBind() {
        OrientationControlClient.removeListener(stateListener)
        OrientationControlClient.addListener(stateListener)
        if (data.isRunning) OrientationControlClient.connect()
        else render(OrientationControlClient.State())
    }

    override fun onRecycle() {
        OrientationControlClient.removeListener(stateListener)
        super.onRecycle()
    }

    private fun render(state: OrientationControlClient.State) {
        val ready = data.isRunning && state.available && !state.busy
        binding.targetPackage.text = context.getString(R.string.orientation_target_package, PortraitTarget.get())
        binding.button1.isEnabled = ready
        binding.changeTarget.isEnabled = ready
        binding.systemPortrait.isEnabled = ready && (state.systemSupported || state.systemOverride)
        binding.systemPortrait.setText(when {
            state.systemOverride -> R.string.orientation_system_restore
            state.forcedPortrait == true -> R.string.orientation_system_clear_existing
            else -> R.string.orientation_system_enable
        })
        binding.text1.setText(R.string.home_orientation_description_normal)
        binding.text2.text = when {
            !data.isRunning -> context.getString(R.string.home_status_service_not_running, context.getString(R.string.app_name))
            !state.available -> state.error ?: context.getString(R.string.home_orientation_description_connecting)
            else -> state.targetStatus ?: context.getString(R.string.home_orientation_target_waiting)
        }
        binding.systemStatus.text = when {
            !data.isRunning -> context.getString(R.string.home_status_service_not_running, context.getString(R.string.app_name))
            state.busy -> context.getString(R.string.home_orientation_description_working)
            state.error != null -> state.error
            !state.available -> context.getString(R.string.home_orientation_description_connecting)
            !state.systemSupported -> context.getString(R.string.orientation_system_unsupported)
            state.systemOverride -> context.getString(if (state.forcedPortrait == true) R.string.orientation_system_active else R.string.orientation_system_pending)
            state.forcedPortrait == true -> context.getString(R.string.orientation_system_existing)
            else -> context.getString(R.string.orientation_system_inactive)
        }
    }
}
