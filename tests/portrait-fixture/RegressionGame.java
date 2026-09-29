package game.qualiarts.hololive.dreams.jp;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Only installed in a fresh CI emulator; never packaged with AndroidControl. */
public final class RegressionGame extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean resumed;
    private boolean focused;
    private TextView scene;
    private final Runnable frame = new Runnable() {
        @Override public void run() {
            if (!resumed || !focused) return;
            // Draw a changing frame, like a game engine. A file heartbeat alone
            // does not exercise the virtual display's rendering after reattachment.
            scene.setText("Frame " + SystemClock.uptimeMillis());
            record("frame", Long.toString(SystemClock.uptimeMillis()), false);
            handler.postDelayed(this, 250);
        }
    };
    private final BroadcastReceiver checkout = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if ("org.androidcontrol.regression.DIALOG".equals(intent.getAction())) {
                new android.app.AlertDialog.Builder(RegressionGame.this)
                    .setMessage("Target app dialog").setPositiveButton("Dismiss", null)
                    .setOnDismissListener(dialog -> record("dialog-dismissed", "dismissed", false)).show();
                return;
            }
            startActivityForResult(new Intent().setComponent(new ComponentName(
                    "org.androidcontrol.regression.checkout", "org.androidcontrol.regression.checkout.CheckoutActivity")), 41);
        }
    };

    private void record(String name, String value, boolean append) {
        try (FileOutputStream out = new FileOutputStream(new File(getFilesDir(), name), append)) {
            out.write((value + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (Exception failure) { throw new RuntimeException(failure); }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        scene = new TextView(this);
        scene.setGravity(Gravity.CENTER);
        scene.setTextColor(0xffffffff);
        scene.setBackgroundColor(0xff164f37);
        scene.setText("Landscape-only test game");
        scene.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                try (FileOutputStream out = new FileOutputStream(new File(getFilesDir(), "touches"), true)) {
                    out.write("touch\n".getBytes(StandardCharsets.UTF_8));
                } catch (Exception failure) { throw new RuntimeException(failure); }
                scene.setText("Touch forwarded");
            }
            return true;
        });
        setContentView(scene);
        IntentFilter filter = new IntentFilter("org.androidcontrol.regression.CHECKOUT");
        filter.addAction("org.androidcontrol.regression.DIALOG");
        registerReceiver(checkout, filter, Context.RECEIVER_EXPORTED);
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        handler.post(frame);
    }

    @Override protected void onPause() {
        resumed = false;
        handler.removeCallbacks(frame);
        super.onPause();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        focused = hasFocus;
        record("window-focus", Boolean.toString(hasFocus), false);
        handler.removeCallbacks(frame);
        if (resumed && focused) handler.post(frame);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 41) record("checkout-returned", "returned", true);
    }

    @Override protected void onDestroy() {
        unregisterReceiver(checkout);
        handler.removeCallbacks(frame);
        super.onDestroy();
    }
}
