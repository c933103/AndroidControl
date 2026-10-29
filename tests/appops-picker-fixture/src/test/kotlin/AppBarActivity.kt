// Test-only app shell: framework Activity/Bundle/Intent/ContentResolver are real Robolectric APIs.
package org.androidcontrol.app.app
import android.app.Activity
import android.content.res.Resources
import android.view.View
import android.view.ViewGroup
import org.androidcontrol.app.FixtureStrings
open class AppBarActivity : Activity() {
    class ActionBar { fun setDisplayHomeAsUpEnabled(enabled: Boolean) { } }
    val supportActionBar: ActionBar? = ActionBar()
    private var fixtureResources: Resources? = null
    override fun getResources(): Resources {
        return fixtureResources ?: super.getResources().let { base ->
            object : Resources(base.assets, base.displayMetrics, base.configuration) {
                override fun getText(id: Int): CharSequence = FixtureStrings.values[id] ?: super.getText(id)
                override fun getString(id: Int): String = FixtureStrings.values[id] ?: super.getString(id)
                override fun getString(id: Int, vararg formatArgs: Any): String =
                    FixtureStrings.values[id]?.let { String.format(it, *formatArgs) } ?: super.getString(id, *formatArgs)
            }.also { fixtureResources = it }
        }
    }
    override fun setContentView(view: View, params: ViewGroup.LayoutParams) { super.setContentView(view) }
}
