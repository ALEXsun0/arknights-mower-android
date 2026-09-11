package com.aliothmoon.maameow;
import android.os.ParcelFileDescriptor;
import android.view.Surface;
interface RemoteService {
    void destroy() = 16777114;
    boolean setVirtualDisplayMode(int mode) = 1;
    void setVirtualDisplayResolution(int width, int height, int dpi) = 2;
    int startVirtualDisplay() = 3;
    void stopVirtualDisplay() = 4;
    void setMonitorSurface(in Surface surface) = 5;
    void touchDown(int x, int y, int contact) = 6;
    void touchMove(int x, int y, int contact) = 7;
    void touchUp(int x, int y, int contact) = 8;
    void touchCancel() = 9;
    ParcelFileDescriptor mowerFrame() = 10;
    boolean mowerKey(int code) = 11;
    boolean mowerGame(String packageName, boolean launch) = 12;
    boolean mowerText(String text) = 13;
    int isAppAlive(String packageName) = 14;
    boolean isAppOnVirtualDisplay(String packageName) = 15;
    boolean installCore(in ParcelFileDescriptor zip, String hash) = 16;
    String maaRpc(String request) = 17;
    String systemRpc(String request) = 18;
}
