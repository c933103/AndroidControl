// The Material wrapper is replaced, but dialogs and click delivery use Android AlertDialog.
package com.google.android.material.dialog
import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.view.View
class MaterialAlertDialogBuilder(context: Context) {
    private val delegate = AlertDialog.Builder(context)
    fun setTitle(value: Int) = apply { delegate.setTitle(contextString(value)) }
    fun setTitle(value: CharSequence) = apply { delegate.setTitle(value) }
    fun setMessage(value: Int) = apply { delegate.setMessage(contextString(value)) }
    fun setMessage(value: CharSequence) = apply { delegate.setMessage(value) }
    fun setView(view: View) = apply { delegate.setView(view) }
    fun setItems(items: Array<String>, listener: DialogInterface.OnClickListener) = apply { delegate.setItems(items, listener) }
    fun setSingleChoiceItems(items: Array<String>, selected: Int, listener: DialogInterface.OnClickListener) =
        apply { delegate.setSingleChoiceItems(items, selected, listener) }
    fun setPositiveButton(value: Int, listener: DialogInterface.OnClickListener?) = apply { delegate.setPositiveButton(contextString(value), listener) }
    fun setNegativeButton(value: Int, listener: DialogInterface.OnClickListener?) = apply { delegate.setNegativeButton(contextString(value), listener) }
    private val resources = context.resources
    private fun contextString(value: Int) = resources.getString(value)
    fun create(): AlertDialog = delegate.create().also { dialogs.add(it) }
    fun show(): AlertDialog = create().also { it.show() }
    companion object { val dialogs = mutableListOf<AlertDialog>() }
}
