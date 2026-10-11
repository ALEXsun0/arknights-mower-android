package com.aliothmoon.maameow.mower

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.widget.LinearLayout
import com.aliothmoon.maameow.mower.MowerStyle.action
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class MowerStyleThemeTest {
    @Test fun switchingBothWaysTraversesEmptySystemRipplesAndUpdatesAppControls() {
        val context = RuntimeEnvironment.getApplication()
        MowerStyle.dark = false
        val root = LinearLayout(context)
        val systemView = View(context)
        val empty = RippleDrawable(ColorStateList.valueOf(Color.RED), null, null)
        assertEquals(0, empty.numberOfLayers)
        systemView.background = empty
        val action = context.action("显示模式") {}
        root.addView(systemView)
        root.addView(action)
        try {
            for (dark in listOf(true, false, true, false)) {
                MowerStyle.dark = dark
                MowerStyle.applyTheme(root)
                assertEquals(if (dark) Color.rgb(230, 230, 232) else Color.rgb(51, 54, 57), action.currentTextColor)
                assertSame(empty, systemView.background)
                assertEquals(0, empty.numberOfLayers)
            }
        } finally { MowerStyle.dark = false }
    }
}
