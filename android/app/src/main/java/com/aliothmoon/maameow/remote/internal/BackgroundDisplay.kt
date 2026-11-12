package com.aliothmoon.maameow.remote.internal

import android.os.Build
import com.aliothmoon.maameow.constant.DefaultDisplayConfig
import com.aliothmoon.maameow.third.Ln

/** 两种显示后端共用 Mower 的显示身份、截图尺寸与保活。 */
object BackgroundDisplay {
    const val LEGACY = 2
    const val VDM = 3
    private val session = BackgroundDisplaySession(
        sdk = Build.VERSION.SDK_INT,
        legacy = object : DisplayBackend {
            override fun start() = VirtualDisplayManager.start()
            override fun stop() = VirtualDisplayManager.stop()
            override fun setResolution(width: Int, height: Int, dpi: Int) = VirtualDisplayManager.setResolution(width, height, dpi)
            override fun displayId() = VirtualDisplayManager.getDisplayId()
            override fun flagTier() = VirtualDisplayManager.getFlagTier()
            override fun isValid() = VirtualDisplayManager.isDisplayValid()
            override fun state() = VirtualDisplayManager.getDisplayState()
        },
        vdm = {
            if (Build.VERSION.SDK_INT >= 34) vdmBackend()
            else error("VDM 需要 Android 14 或更高版本")
        },
    )

    @androidx.annotation.RequiresApi(34)
    private fun vdmBackend(): DisplayBackend {
        check(Build.VERSION.SDK_INT >= 34) { "VDM 需要 Android 14 或更高版本" }
        return object : DisplayBackend {
            override fun start() = VdmDisplayController.start()
            override fun stop() = VdmDisplayController.stop()
            override fun setResolution(width: Int, height: Int, dpi: Int) = VdmDisplayController.setResolution(width, height, dpi)
            override fun displayId() = VdmDisplayController.getDisplayId()
            override fun flagTier() = VdmDisplayController.getFlagTier()
            override fun isValid() = VdmDisplayController.isDisplayValid()
            override fun state() = VdmDisplayController.getDisplayState()
        }
    }

    @Synchronized fun setMode(mode: Int) = session.setMode(mode).also { if (it) updateKeepAlive() }
    @Synchronized fun setResolution(width: Int, height: Int, dpi: Int) {
        try { session.setResolution(width, height, dpi) }
        finally { updateKeepAlive() }
    }
    private fun updateKeepAlive() {
        val id = session.displayId()
        if (id > 0) PowerController.startVirtualDisplayKeepAlive(id)
        else PowerController.stopVirtualDisplayKeepAlive()
    }
    @Synchronized fun start(): Int = try {
        session.start().also { updateKeepAlive() }
    } catch (failure: Throwable) {
        PowerController.stopVirtualDisplayKeepAlive()
        Ln.e("Background display start failed (mode=${session.mode})", failure)
        DefaultDisplayConfig.DISPLAY_NONE
    }
    @Synchronized fun stop() {
        PowerController.stopVirtualDisplayKeepAlive()
        session.stop()
    }
    fun getDisplayId() = session.displayId()
    fun getFlagTier() = session.flagTier()
    fun isDisplayValid() = session.isValid()
    fun getDisplayState() = session.state()
    fun backendName() = if (session.mode == VDM) "vdm" else "legacy"
    fun warning(): String = when {
        session.mode == VDM && session.flagTier() >= 2 -> "VDM 未获得始终解锁权限，手机锁屏时后台游戏可能暂停。"
        session.mode == LEGACY && session.flagTier() > 0 -> "系统未授予独立后台显示权限：锁屏或熄屏会暂停后台游戏，操作游戏可能切换手机焦点。请保持亮屏运行。"
        else -> ""
    }
}

internal interface DisplayBackend {
    fun start(): Int
    fun stop()
    fun setResolution(width: Int, height: Int, dpi: Int)
    fun displayId(): Int
    fun flagTier(): Int
    fun isValid(): Boolean
    fun state(): Int
}

/** 参考 MAA-Meow 的 Legacy/VDM 分流；已创建的显示不能在运行中更换后端。 */
internal class BackgroundDisplaySession(
    private val sdk: Int,
    private val legacy: DisplayBackend,
    private val vdm: () -> DisplayBackend,
) {
    @Volatile var mode = BackgroundDisplay.LEGACY
        private set
    private val virtualDevice by lazy(vdm)
    private fun backend() = if (mode == BackgroundDisplay.VDM) virtualDevice else legacy
    fun setMode(value: Int): Boolean {
        if (value != BackgroundDisplay.LEGACY && value != BackgroundDisplay.VDM) return false
        if (value == BackgroundDisplay.VDM && sdk < 34) return false
        if (value != mode && displayId() > 0) return false
        if (value != mode) backend().stop()
        mode = value
        return true
    }
    fun start() = backend().start()
    fun stop() = backend().stop()
    fun setResolution(width: Int, height: Int, dpi: Int) {
        require(width == 1920 && height == 1080 && dpi > 0) { "后台显示固定使用 1920×1080" }
        backend().setResolution(width, height, dpi)
    }
    fun displayId() = backend().displayId()
    fun flagTier() = backend().flagTier()
    fun isValid() = backend().isValid()
    fun state() = backend().state()
}
