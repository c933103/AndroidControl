// SPDX-FileCopyrightText: 2026 AndroidControl contributors
// SPDX-License-Identifier: Apache-2.0

package org.androidcontrol.app.licenses

import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.view.MenuItem
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.text.HtmlCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.androidcontrol.app.R
import org.androidcontrol.app.app.AppBarActivity
import org.androidcontrol.app.databinding.ComponentLicensesActivityBinding

/** Offline source-attribution and license text viewer for the four integrated components. */
class ComponentLicensesActivity : AppBarActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.component_licences_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        val binding = ComponentLicensesActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.componentLicenceLinks.text = HtmlCompat.fromHtml(
            getString(R.string.component_licences_links), HtmlCompat.FROM_HTML_MODE_LEGACY
        )
        binding.componentLicenceLinks.movementMethod = LinkMovementMethod.getInstance()
        binding.licenceShizuku.setOnClickListener {
            showLicence(R.string.component_licence_shizuku, "licenses/Shizuku-Apache-2.0.txt")
        }
        binding.licenceAppops.setOnClickListener {
            showLicence(R.string.component_licence_appops, "licenses/AppOpsX-MIT.txt")
        }
        binding.licenceShizu.setOnClickListener {
            showLicence(R.string.component_licence_shizu, "licenses/ShizuCallRecorder-GPL-3.0.txt")
        }
        binding.licenceScrcpy.setOnClickListener {
            showLicence(R.string.component_licence_scrcpy, "licenses/scrcpy-Apache-2.0.txt")
        }
    }

    private fun showLicence(@StringRes titleRes: Int, asset: String) {
        val licence = try {
            assets.open(asset).bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.component_licences_title)
                .setMessage(R.string.component_licence_unavailable)
                .setPositiveButton(android.R.string.ok, null).show()
            return
        }
        val view = TextView(this).apply {
            text = licence
            setTextIsSelectable(true)
            setPadding(24, 24, 24, 24)
            textSize = 13f
        }
        val scroller = ScrollView(this).apply { addView(view) }
        MaterialAlertDialogBuilder(this).setTitle(titleRes)
            .setView(scroller).setPositiveButton(android.R.string.ok, null).show()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
