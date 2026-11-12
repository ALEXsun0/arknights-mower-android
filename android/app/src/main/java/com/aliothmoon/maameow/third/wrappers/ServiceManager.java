package com.aliothmoon.maameow.third.wrappers;

import android.annotation.SuppressLint;
import android.content.Context;
import android.hardware.camera2.CameraManager;
import android.os.IBinder;
import android.os.IInterface;

import com.aliothmoon.maameow.third.FakeContext;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;

@SuppressLint("PrivateApi,DiscouragedPrivateApi")
public final class ServiceManager {

    private static final Method GET_SERVICE_METHOD;
    private static WindowManager windowManager;
    private static DisplayManager displayManager;
    private static InputManager inputManager;
    private static ActivityManager activityManager;
    private static PowerManager powerManager;
    private static VirtualDeviceManager virtualDeviceManager;
    private static CompanionDeviceManager companionDeviceManager;

    static {
        try {
            GET_SERVICE_METHOD = Class.forName("android.os.ServiceManager").getDeclaredMethod("getService", String.class);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private ServiceManager() {
        /* not instantiable */
    }

    static IInterface getService(String service, String type) {
        try {
            IBinder binder = (IBinder) GET_SERVICE_METHOD.invoke(null, service);
            Method asInterfaceMethod = Class.forName(type + "$Stub").getMethod("asInterface", IBinder.class);
            return (IInterface) asInterfaceMethod.invoke(null, binder);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    public static WindowManager getWindowManager() {
        if (windowManager == null) {
            windowManager = WindowManager.create();
        }
        return windowManager;
    }

    // The DisplayManager may be used from both the Controller thread and the video (main) thread
    public static synchronized DisplayManager getDisplayManager() {
        if (displayManager == null) {
            displayManager = DisplayManager.create();
        }
        return displayManager;
    }

    public static InputManager getInputManager() {
        if (inputManager == null) {
            inputManager = InputManager.create();
        }
        return inputManager;
    }

    public static synchronized PowerManager getPowerManager() {
        if (powerManager == null) powerManager = PowerManager.create();
        return powerManager;
    }

    public static ActivityManager getActivityManager() {
        if (activityManager == null) {
            activityManager = ActivityManager.create();
        }
        return activityManager;
    }

    @androidx.annotation.RequiresApi(34)
    public static synchronized VirtualDeviceManager getVirtualDeviceManager() {
        if (virtualDeviceManager == null) virtualDeviceManager = VirtualDeviceManager.create();
        return virtualDeviceManager;
    }

    @androidx.annotation.RequiresApi(33)
    public static synchronized CompanionDeviceManager getCompanionDeviceManager() {
        if (companionDeviceManager == null) companionDeviceManager = CompanionDeviceManager.create();
        return companionDeviceManager;
    }

    static ReflectiveOperationException unwrap(InvocationTargetException exception) {
        Throwable cause = exception.getCause();
        if (cause instanceof RuntimeException) throw (RuntimeException) cause;
        if (cause instanceof Error) throw (Error) cause;
        if (cause instanceof ReflectiveOperationException) return (ReflectiveOperationException) cause;
        return exception;
    }

}
