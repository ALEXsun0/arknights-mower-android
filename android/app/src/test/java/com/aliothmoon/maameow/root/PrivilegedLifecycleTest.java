package com.aliothmoon.maameow.root;
import android.app.Application;
import android.os.Bundle;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import com.aliothmoon.maameow.RemoteService;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowBinder;
import java.lang.reflect.Proxy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class PrivilegedLifecycleTest {
    @Before public void setup() { ProcessExit.resetForTest(); ProcessExit.deadlineMs = 1000; ProcessExit.halt = code -> {}; }
    @After public void reset() { ProcessExit.resetForTest(); ProcessExit.deadlineMs = ProcessExit.DEADLINE_MS; }
    @Test public void closingAppLifelineDestroysLocalServiceBeforeExit() throws Exception {
        ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
        AtomicInteger destroyed = new AtomicInteger(); CountDownLatch halted = new CountDownLatch(1);
        Binder binder = new Binder();
        RemoteService service = (RemoteService) Proxy.newProxyInstance(RemoteService.class.getClassLoader(), new Class<?>[]{RemoteService.class}, (proxy, method, args) -> {
            if (method.getName().equals("asBinder")) return binder;
            if (method.getName().equals("destroy")) { destroyed.incrementAndGet(); return null; }
            throw new AssertionError(method.getName());
        });
        binder.attachInterface(service, "com.aliothmoon.maameow.RemoteService");
        ProcessExit.halt = code -> { assertEquals(1, destroyed.get()); assertEquals(0, code); halted.countDown(); };
        Thread watcher = RemoteServiceStarter.watchLifeline(pipe[0], binder);
        pipe[1].close();
        try { assertTrue(halted.await(2, TimeUnit.SECONDS)); }
        finally { pipe[0].close(); watcher.join(2000); }
        assertFalse(watcher.isAlive()); assertEquals(1, destroyed.get());
    }
    @Test public void cancelledBootstrapCannotAcceptLateService() {
        var waiting = RootServiceBootstrapRegistry.INSTANCE.register("cancelled");
        RootServiceBootstrapRegistry.INSTANCE.unregister("cancelled");
        assertTrue(waiting.isCancelled()); assertNull(RootServiceBootstrapRegistry.INSTANCE.attach("cancelled", new Binder()));
    }
    @Test public void bootstrapTokenIsConsumedOnce() {
        var waiting = RootServiceBootstrapRegistry.INSTANCE.register("once"); Binder service = new Binder();
        assertNotNull(RootServiceBootstrapRegistry.INSTANCE.attach("once", service)); assertTrue(waiting.isCompleted());
        assertNull(RootServiceBootstrapRegistry.INSTANCE.attach("once", service));
    }
    @Test public void expiredBootstrapDestroysReturnedService() throws Exception {
        ShadowBinder.setCallingUid(2000);
        CountDownLatch destroyed = new CountDownLatch(1);
        Binder binder = new Binder();
        RemoteService service = (RemoteService) Proxy.newProxyInstance(RemoteService.class.getClassLoader(), new Class<?>[]{RemoteService.class}, (proxy, method, args) -> {
            if (method.getName().equals("asBinder")) return binder;
            if (method.getName().equals("destroy")) { destroyed.countDown(); return null; }
            throw new AssertionError(method.getName());
        });
        binder.attachInterface(service, "com.aliothmoon.maameow.RemoteService");
        Bundle request = new Bundle();
        request.putString(RootServiceBootstrapRegistry.KEY_TOKEN, "expired");
        request.putBinder(RootServiceBootstrapRegistry.KEY_SERVICE_BINDER, binder);
        assertNull(new RootServiceBootstrapProvider().call(RootServiceBootstrapRegistry.METHOD_ATTACH_REMOTE_SERVICE, null, request));
        assertTrue(destroyed.await(2, TimeUnit.SECONDS));
    }
    @Test public void bootstrapRejectsRootIdentityBeforeConsumingToken() {
        ShadowBinder.setCallingUid(0);
        var waiting = RootServiceBootstrapRegistry.INSTANCE.register("wrong-uid");
        try {
            Bundle request = new Bundle();
            request.putString(RootServiceBootstrapRegistry.KEY_TOKEN, "wrong-uid");
            request.putBinder(RootServiceBootstrapRegistry.KEY_SERVICE_BINDER, new Binder());
            assertNull(new RootServiceBootstrapProvider().call(RootServiceBootstrapRegistry.METHOD_ATTACH_REMOTE_SERVICE, null, request));
            assertFalse(waiting.isCompleted());
        } finally { RootServiceBootstrapRegistry.INSTANCE.unregister("wrong-uid"); }
    }
    @Test public void previewSurfaceProxyUsesOnewayTransaction() throws Exception {
        AtomicInteger flags = new AtomicInteger(-1), transaction = new AtomicInteger(-1);
        Binder remote = new Binder() {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int value) {
                transaction.set(code); flags.set(value); assertNull(reply); return true;
            }
        };
        RemoteService.Stub.asInterface(remote).setMonitorSurface(null);
        assertEquals(IBinder.FLAG_ONEWAY, flags.get()); assertEquals(IBinder.FIRST_CALL_TRANSACTION + 5, transaction.get());
    }
}
