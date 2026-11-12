package com.aliothmoon.maameow.remote.internal

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class VdmPrivilegeTest {
    @Test fun ordinaryAppIdentityCannotCreateThePrivilegedDisplay() {
        val failure = runCatching { VdmDisplayController.start() }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure!!.message.orEmpty().contains("permanently shell"))
        assertEquals(-1, VdmDisplayController.getDisplayId())
        assertFalse(VdmDisplayController.isDisplayValid())
    }
}
