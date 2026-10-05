package org.androidcontrol.app.management

import android.content.pm.PackageInfo
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.content.res.AppCompatResources
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Job
import org.androidcontrol.app.Helps
import org.androidcontrol.app.R
import org.androidcontrol.app.authorization.AuthorizationManager
import org.androidcontrol.app.databinding.AppListEmptyBinding
import org.androidcontrol.app.databinding.AppListItemBinding
import org.androidcontrol.app.ktx.toHtml
import org.androidcontrol.app.utils.AppIconCache
import org.androidcontrol.app.utils.ShizukuSystemApis
import org.androidcontrol.app.utils.UserHandleCompat
import rikka.html.text.HtmlCompat
import rikka.recyclerview.BaseViewHolder
import rikka.recyclerview.BaseViewHolder.Creator
import rikka.shizuku.Shizuku

class EmptyViewHolder(private val binding: AppListEmptyBinding) : BaseViewHolder<Any>(binding.root) {

    companion object {
        @JvmField
        val CREATOR = Creator<Any> { inflater: LayoutInflater, parent: ViewGroup? -> EmptyViewHolder(AppListEmptyBinding.inflate(inflater, parent, false)) }
    }

}
