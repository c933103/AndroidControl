package moe.shizuku.manager.control;

import android.os.IBinder;
import android.view.MotionEvent;

interface IAndroidControlService {
    void destroy() = 16777114;

    boolean setForcePortrait(boolean enabled) = 1;
    boolean isForcePortraitEnabled() = 2;
    boolean toggleForcePortrait() = 3;
    String getTargetPortraitStatus() = 4;

    boolean launchTargetOnPortraitDisplay(int displayId, int width, int height, IBinder hostToken, String packageName) = 5;
    oneway void injectTargetMotionEvent(int displayId, in MotionEvent event) = 6;
    void stopTargetPortraitDisplay(int displayId, boolean relaunchOnDefaultDisplay) = 7;
    boolean isSystemPortraitSupported() = 8;
    boolean hasSystemPortraitOverride() = 9;
    void validateTargetPackage(String packageName) = 10;
}
