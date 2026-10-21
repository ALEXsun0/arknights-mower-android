package com.aliothmoon.maameow.root;

import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import java.io.IOException;
import java.io.InputStream;

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
            onAppGone("bootstrap failed", createdService.service());
            return;
        }
        System.err.println("[" + TAG + "] sendBinder() ok, entering Looper");

        Looper.loop();
        onAppGone("looper stopped", createdService.service());
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
            IBinder.DeathRecipient recipient = () -> onAppGone("app binder died", createdService.service());
            IBinder lifecycleBinder = result.lifecycleBinder();
            lifecycleBinder.linkToDeath(recipient, 0);
            appLifecycleBinder = lifecycleBinder;
            appDeathRecipient = recipient;
            watchLifeline(result.lifeline(), createdService.service());
            return true;
        } catch (Throwable tr) {
            try { if (result.lifeline() != null) result.lifeline().close(); } catch (IOException ignored) {}
            Ln.e(TAG + ": failed to link app lifecycle binder", tr);
            return false;
        }
    }

    /** 两路死讯先到先走：binder 通知要等线程池，被卡住的调用占满时送不到，lifeline 独占线程不受影响 */
    private static void onAppGone(String reason, IBinder service) {
        ProcessExit.exit(reason, () -> destroyService(service));
    }

    static Thread watchLifeline(ParcelFileDescriptor lifeline, IBinder service) {
        if (lifeline == null) {
            Ln.w(TAG + ": no lifeline, relying on binder death only");
            return null;
        }
        Thread watcher = new Thread(() -> {
            try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(lifeline)) {
                // App 从不写，阻塞到它退出、内核关掉写端时的 EOF
                while (in.read() >= 0) {
                }
            } catch (IOException e) {
                Ln.w(TAG + ": lifeline read failed, relying on binder death only", e);
                return;
            }
            onAppGone("app lifeline closed", service);
        }, "app-lifeline");
        watcher.setDaemon(true);
        watcher.start();
        return watcher;
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
