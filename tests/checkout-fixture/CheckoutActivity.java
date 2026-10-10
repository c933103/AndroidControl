package org.androidcontrol.regression.checkout;

import android.app.Activity;
import android.graphics.Rect;
import android.graphics.Point;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.AtomicFile;
import android.view.Display;
import android.view.View;
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
    private final long generation = SystemClock.uptimeMillis();
    private long sampleSequence;
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
            Display display = getWindowManager().getDefaultDisplay();
            int rotation = display.getRotation();
            Point size = new Point();
            display.getRealSize(size);
            View decor = getWindow().getDecorView();
            Rect visible = new Rect();
            boolean complete = cancel.getGlobalVisibleRect(visible) && visible.height() == cancel.getHeight();
            int[] position = new int[2];
            cancel.getLocationOnScreen(position);
            Rect screen = new Rect();
            cancel.getWindowVisibleDisplayFrame(screen);
            complete = complete && screen.contains(position[0], position[1])
                && screen.contains(position[0] + cancel.getWidth() - 1, position[1] + cancel.getHeight() - 1);
            int[] window = new int[2];
            decor.getLocationOnScreen(window);
            complete = complete && cancel.isLaidOut() && !cancel.isLayoutRequested()
                && !decor.isLayoutRequested() && rotation == display.getRotation();
            String snapshot = "display=" + display.getDisplayId()
                    + ";buttonVisible=" + complete + ";focused=" + hasWindowFocus()
                    + ";x=" + (position[0] + cancel.getWidth() / 2)
                    + ";y=" + (position[1] + cancel.getHeight() / 2)
                    + ";generation=" + generation + ";sequence=" + (++sampleSequence)
                    + ";sampleMs=" + SystemClock.uptimeMillis() + ";rotation=" + rotation
                    + ";width=" + size.x + ";height=" + size.y
                    + ";window=" + window[0] + "," + window[1] + ","
                    + (window[0] + decor.getWidth()) + "," + (window[1] + decor.getHeight())
                    + ";button=" + position[0] + "," + position[1] + ","
                    + (position[0] + cancel.getWidth()) + "," + (position[1] + cancel.getHeight());
            AtomicFile ready = new AtomicFile(new File(getFilesDir(), "ready"));
            FileOutputStream out = null;
            try {
                out = ready.startWrite();
                out.write(snapshot.getBytes(StandardCharsets.UTF_8));
                ready.finishWrite(out);
            } catch (Exception error) {
                if (out != null) ready.failWrite(out);
                throw new RuntimeException(error);
            }
            handler.postDelayed(this, 250);
          }
        }, 500);
    }
    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
