package moe.shizuku.manager.control;

interface IAndroidControlService {
    void destroy() = 16777114;

    boolean setForcePortrait(boolean enabled) = 1;
    boolean isForcePortraitEnabled() = 2;
    boolean toggleForcePortrait() = 3;
}
