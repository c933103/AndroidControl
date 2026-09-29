package org.androidcontrol.regression.checkout;

import android.app.Activity;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
    private final Handler handler = new Handler(Looper.getMainLooper());
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        // A transaction screen may forbid capture. The host must use the native
        // display, never bypass this flag to make the test visible.
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
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
        handler.postDelayed(new Runnable() {
          @Override public void run() {
            Rect visible = new Rect();
            boolean complete = cancel.getGlobalVisibleRect(visible) && visible.height() == cancel.getHeight();
            int[] position = new int[2];
            cancel.getLocationOnScreen(position);
            Rect screen = new Rect();
            cancel.getWindowVisibleDisplayFrame(screen);
            complete = complete && screen.contains(position[0], position[1])
                && screen.contains(position[0] + cancel.getWidth() - 1, position[1] + cancel.getHeight() - 1);
            try (FileOutputStream out = new FileOutputStream(new File(getFilesDir(), "ready"))) {
                out.write(("display=" + getWindowManager().getDefaultDisplay().getDisplayId()
                    + ";buttonVisible=" + complete + ";x=" + (position[0] + cancel.getWidth() / 2)
                    + ";y=" + (position[1] + cancel.getHeight() / 2)).getBytes(StandardCharsets.UTF_8));
            } catch (Exception error) { throw new RuntimeException(error); }
            handler.postDelayed(this, 250);
          }
        }, 500);
    }
    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
