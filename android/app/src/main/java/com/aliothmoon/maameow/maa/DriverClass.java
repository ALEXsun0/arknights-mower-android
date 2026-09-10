package com.aliothmoon.maameow.maa;
import com.aliothmoon.maameow.mower.BackgroundGameService;
/** Android MAA callbacks target only the app-owned background display. */
public final class DriverClass {
    public static boolean startApp(String pkg, int displayId, boolean forceStop) {
        BackgroundGameService service = BackgroundGameService.getCurrent();
        if (service == null) return false;
        if (forceStop) service.mowerGame(pkg, false);
        return service.mowerGame(pkg, true);
    }
    public static boolean touchDown(int x,int y,int contact,int display) { return InputControlUtils.down(x,y,contact,display); }
    public static boolean touchMove(int x,int y,int contact,int display) { return InputControlUtils.move(x,y,contact,display); }
    public static boolean touchUp(int x,int y,int contact,int display) { return InputControlUtils.up(x,y,contact,display); }
    public static boolean keyDown(int key,int display) { return InputControlUtils.keyDown(key,display); }
    public static boolean keyUp(int key,int display) { return InputControlUtils.keyUp(key,display); }
}
