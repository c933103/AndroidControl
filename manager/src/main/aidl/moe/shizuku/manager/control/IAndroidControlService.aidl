package moe.shizuku.manager.control;

import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;

interface IAndroidControlService {
    void destroy() = 16777114;

    boolean setForcePortrait(boolean enabled) = 1;
    boolean isForcePortraitEnabled() = 2;
    boolean toggleForcePortrait() = 3;
    String getTargetPortraitStatus() = 4;

    int createPortraitVirtualDisplay(in Surface surface, int width, int height, int densityDpi) = 5;
    boolean launchTargetOnPortraitVirtualDisplay(int displayId, int width, int height) = 6;
    void releasePortraitVirtualDisplay() = 7;
    oneway void injectPortraitMotionEvent(in MotionEvent event, int displayId) = 8;
    oneway void injectPortraitKeyEvent(in KeyEvent event, int displayId) = 9;
}
