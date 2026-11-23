package com.aynthor.simulator;

import android.view.Surface;

interface IVirtualDisplayService {
    // Shizuku UserService destroy transaction code (16777114 in AIDL -> 16777115)
    void destroy() = 16777114;

    int[] createDisplays(
        in Surface topSurface,
        int topW,
        int topH,
        int topDpi,
        in Surface bottomSurface,
        int bottomW,
        int bottomH,
        int bottomDpi
    ) = 1;

    void swapSurfaces(in Surface topSurface, in Surface bottomSurface) = 2;

    oneway void injectTouchEvent(
        int displayId,
        int action,
        long downTime,
        long eventTime,
        int pointerCount,
        in int[] pointerIds,
        in float[] xs,
        in float[] ys,
        in float[] pressures
    ) = 3;

    oneway void injectKeyEvent(
        int displayId,
        int action,
        int keyCode,
        long downTime,
        long eventTime,
        int repeatCount,
        int metaState,
        int source
    ) = 4;

    oneway void injectJoystickEvent(
        int displayId,
        long downTime,
        long eventTime,
        float axisX,
        float axisY,
        float axisZ,
        float axisRz,
        float axisLTrigger,
        float axisRTrigger,
        float axisHatX,
        float axisHatY
    ) = 5;

    boolean launchAppOnDisplay(String packageName, String activityName, int displayId) = 6;

    void releaseDisplays() = 7;

    String getDebugLog() = 8;

    boolean forceStopPackage(String packageName) = 9;

    boolean swapTasksBetweenDisplays(int topDisplayId, int bottomDisplayId) = 10;

    boolean closeTasksOnDisplay(int displayId) = 11;

    oneway void focusDisplay(int displayId) = 12;

    void registerClientDeathToken(IBinder token) = 13;

    String getPerformanceStats(int displayId) = 14;

    boolean resizeBottomDisplay(int width, int height, int dpi) = 15;

    boolean reclaimEscapedPackage(String packageName, int targetDisplayId, String hostPackageName) = 16;

    String getDisplayTasksSummary() = 17;
}
