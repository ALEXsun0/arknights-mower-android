package com.aliothmoon.maameow.mower

import com.aliothmoon.maameow.constant.DefaultDisplayConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BackgroundDisplayConfigurationTest {
    @Test fun displaySettingsAreFixedInsteadOfUserConfigurable() {
        assertFalse(AndroidSystemSettings.defaults.containsKey("force_fullscreen"))
        assertFalse(AndroidSystemSettings.defaults.containsKey("resolution_720p"))
        assertEquals(1920, DefaultDisplayConfig.WIDTH)
        assertEquals(1080, DefaultDisplayConfig.HEIGHT)
        assertEquals(320, DefaultDisplayConfig.DPI)
    }
}
