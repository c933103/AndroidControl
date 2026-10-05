package org.androidcontrol.app.settings

import android.content.res.Resources
import android.os.Bundle
import org.androidcontrol.app.R
import org.androidcontrol.app.app.AppBarFragmentActivity

class SettingsActivity : AppBarFragmentActivity() {

    override fun onApplyUserThemeResource(theme: Resources.Theme, isDecorView: Boolean) {
        super.onApplyUserThemeResource(theme, isDecorView)
        theme.applyStyle(R.style.ThemeOverlay_Rikka_Material3_Preference, true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                    .replace(R.id.fragment_container, SettingsFragment())
                    .commit()
        }
    }
}
