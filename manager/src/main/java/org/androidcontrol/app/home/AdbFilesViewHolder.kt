package org.androidcontrol.app.home

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import org.androidcontrol.app.R
import org.androidcontrol.app.databinding.HomeAdbFilesBinding
import org.androidcontrol.app.databinding.HomeItemContainerBinding
import org.androidcontrol.app.files.AdbDocumentsProvider
import org.androidcontrol.app.model.ServiceStatus
import rikka.recyclerview.BaseViewHolder
import rikka.recyclerview.BaseViewHolder.Creator

class AdbFilesViewHolder(
    private val binding: HomeAdbFilesBinding,
    private val root: View
) : BaseViewHolder<ServiceStatus>(root), View.OnClickListener {

    companion object {
        val CREATOR = Creator<ServiceStatus> { inflater: LayoutInflater, parent: ViewGroup? ->
            val outer = HomeItemContainerBinding.inflate(inflater, parent, false)
            val inner = HomeAdbFilesBinding.inflate(inflater, outer.root, true)
            AdbFilesViewHolder(inner, outer.root)
        }
    }

    init {
        root.setOnClickListener(this)
    }

    override fun onBind() {
        root.isEnabled = data.isRunning
        binding.text2.setText(
            if (data.isRunning) {
                R.string.home_adb_files_description
            } else {
                R.string.home_adb_files_unavailable
            }
        )
    }

    override fun onClick(v: View) {
        if (!data.isRunning) return

        val rootUri = DocumentsContract.buildDocumentUri(
            AdbDocumentsProvider.AUTHORITY,
            AdbDocumentsProvider.ROOT_DOCUMENT_ID
        )

        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(rootUri, DocumentsContract.Document.MIME_TYPE_DIR)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        try {
            v.context.startActivity(viewIntent)
        } catch (_: ActivityNotFoundException) {
            val fallback = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, rootUri)
            }
            try {
                v.context.startActivity(fallback)
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(
                    v.context,
                    R.string.home_adb_files_open_failed,
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
