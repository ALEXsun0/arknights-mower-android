package com.aliothmoon.maameow.third.wrappers

import android.app.Application
import android.companion.AssociationInfo
import android.os.Binder
import android.os.IInterface
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class VdmWrapperFailureTest {
    class CompanionService(private val failure: Throwable) : IInterface {
        override fun asBinder() = Binder()
        fun getAssociations(packageName: String, userId: Int): List<AssociationInfo> = throw failure
    }

    private fun invokeWrapper(failure: Throwable): Throwable? {
        val constructor = CompanionDeviceManager::class.java.getDeclaredConstructor(IInterface::class.java)
            .apply { isAccessible = true }
        val wrapper = constructor.newInstance(CompanionService(failure))
        return runCatching { wrapper.getAssociations("com.android.shell", 0) }.exceptionOrNull()
    }

    @Test fun permissionFailureRemainsVisibleToTierFallback() {
        val failure = SecurityException("ADD_TRUSTED_DISPLAY denied")
        assertSame(failure, invokeWrapper(failure))
    }

    @Test fun reflectionFailureKeepsItsOriginalCause() {
        val failure = NoSuchMethodException("unsupported ROM interface")
        assertSame(failure, invokeWrapper(failure))
    }
}
