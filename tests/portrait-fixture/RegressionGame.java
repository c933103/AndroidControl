package game.qualiarts.hololive.dreams.jp;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Only installed in a fresh CI emulator; never packaged with AndroidControl. */
public final class RegressionGame extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView scene = new TextView(this);
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
    }
}
