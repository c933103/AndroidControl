package org.androidcontrol.regression.browser;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

/** A separate-UID browser launch, not the manager's own WebView. */
public final class BrowserActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView text = new TextView(this);
        text.setText("External browser: " + getIntent().getDataString());
        setContentView(text);
    }
}
