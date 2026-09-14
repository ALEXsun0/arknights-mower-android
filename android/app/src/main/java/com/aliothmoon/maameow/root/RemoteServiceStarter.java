package com.aliothmoon.maameow.root;

import android.os.IBinder;
import android.os.Looper;

import com.aliothmoon.maameow.RemoteService;
import com.aliothmoon.maameow.third.Ln;

/** Shizuku / Root 共用的提权服务入口 */
public final class RemoteServiceStarter {

    private static final String TAG = "RemoteServiceStarter";

    // linkToDeath 随 BinderProxy 被 GC 而失效，须持强引用保证死亡通知可送达
    private static IBinder appLifecycleBinder;
    private static IBinder.DeathRecipient appDeathRecipient;

    private RemoteServiceStarter() {
    }

    public static void main(String[] args) {
        System.err.println("[" + TAG + "] main() entry");
        if (Looper.getMainLooper() == null) {
            Looper.prepareMainLooper();
        }

        RootUserService.CreatedService createdService = RootUserService.create(args);
        if (createdService == null) {
            System.err.println("[" + TAG + "] service creation returned null");
            System.exit(1);
            return;
        }
        System.err.println("[" + TAG + "] service created");

        if (!sendBinder(createdService)) {
            System.err.println("[" + TAG + "] sendBinder() failed");
            System.exit(1);
            return;
        }
        System.err.println("[" + TAG + "] sendBinder() ok, entering Looper");

        Looper.loop();
        System.exit(0);
    }

    private static boolean sendBinder(RootUserService.CreatedService createdService) {
        RootServiceBootstrapClient.BootstrapResult result = RootServiceBootstrapClient.attachRemoteService(
                createdService.packageName(),
                createdService.userId(),
                createdService.token(),
                createdService.service()
        );
        if (result == null) {
            return false;
        }


        try {
            IBinder.DeathRecipient recipient = () -> {
                Ln.i(TAG + ": app process died, destroying remote service");
                destroyService(createdService.service());
                Ln.i(TAG + ": remote service destroy signal sent, exiting");
                System.exit(0);
            };
            IBinder lifecycleBinder = result.lifecycleBinder();
            lifecycleBinder.linkToDeath(recipient, 0);
            appLifecycleBinder = lifecycleBinder;
            appDeathRecipient = recipient;
            return true;
        } catch (Throwable tr) {
            Ln.e(TAG + ": failed to link app lifecycle binder", tr);
            return false;
        }
    }

    private static void destroyService(IBinder service) {
        if (service == null || !service.pingBinder()) {
            return;
        }

        try {
            // 等待本地服务完成清理后再退出，不能发出异步销毁信号后立即结束进程。
            RemoteService.Stub.asInterface(service).destroy();
        } catch (Throwable tr) {
            Ln.w(TAG + ": destroy remote service failed", tr);
        }
    }
}
