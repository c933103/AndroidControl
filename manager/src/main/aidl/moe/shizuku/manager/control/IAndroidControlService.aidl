package moe.shizuku.manager.control;

import android.os.IBinder;
import android.view.MotionEvent;
import android.view.Surface;

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
    int createTargetPortraitSession(in Surface surface, int width, int height, int densityDpi,
        IBinder hostToken, String packageName, int hostTaskId) = 11;
    void attachTargetPortraitSurface(int displayId, IBinder hostToken, in Surface surface) = 12;
    void sendTargetBack(int displayId, IBinder hostToken) = 13;
    int getTargetPortraitSessionState(int displayId, IBinder hostToken) = 14;
    void handoffTargetToPhone(int displayId, IBinder hostToken) = 15;
    String getTargetPortraitLink(int displayId, IBinder hostToken) = 16;
    void setTargetBrowserVisible(int displayId, IBinder hostToken, boolean visible) = 17;
}
