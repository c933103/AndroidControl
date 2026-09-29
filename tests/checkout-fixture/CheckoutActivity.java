package org.androidcontrol.regression.checkout;

import android.app.Activity;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** A harmless external Activity/result flow; it never contacts a payment service. */
public final class CheckoutActivity extends Activity {
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(16, 16, 16, 16);
        TextView title = new TextView(this);
        title.setText("External transaction test — no payment");
        title.setMinHeight(180);
        content.addView(title);
        Button cancel = new Button(this);
        cancel.setText("Dismiss test dialog");
        cancel.setOnClickListener(v -> finish());
        content.addView(cancel);
        setContentView(content);
        getWindow().setGravity(Gravity.BOTTOM);
        getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cancel.postDelayed(() -> {
            Rect visible = new Rect();
            boolean complete = cancel.getGlobalVisibleRect(visible) && visible.height() == cancel.getHeight();
            try (FileOutputStream out = new FileOutputStream(new File(getFilesDir(), "ready"))) {
                out.write(("display=" + getWindowManager().getDefaultDisplay().getDisplayId()
                    + ";buttonVisible=" + complete).getBytes(StandardCharsets.UTF_8));
            } catch (Exception error) { throw new RuntimeException(error); }
        }, 500);
    }
}
