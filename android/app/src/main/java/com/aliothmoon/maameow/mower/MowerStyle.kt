package com.aliothmoon.maameow.mower

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView

/** Native chrome uses the same tokens as runtime/ui/src/theme/mower.js. */
internal object MowerStyle {
    var dark = false
    val paper get() = if (dark) Color.rgb(24, 24, 28) else Color.WHITE
    val control get() = if (dark) Color.rgb(38, 38, 42) else Color.rgb(250, 250, 252)
    val ink get() = if (dark) Color.rgb(230, 230, 232) else Color.rgb(51, 54, 57)
    val muted get() = if (dark) Color.rgb(150, 150, 156) else Color.rgb(118, 124, 130)
    val green get() = Color.parseColor(if (dark) "#63e2b7" else "#18a058")
    val onPrimary get() = if (dark) Color.rgb(16, 16, 20) else Color.WHITE
    val border get() = if (dark) Color.rgb(64, 64, 70) else Color.rgb(224, 224, 230)
    val iconBackground get() = if (dark) Color.rgb(24, 24, 28) else Color.WHITE
    fun Context.dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun role(color: Int) = when (color) {
        green -> "green"; ink -> "ink"; muted -> "muted"; control -> "control"; paper -> "paper"; else -> "fixed"
    }
    private fun color(role: String, fallback: Int) = when (role) {
        "green" -> green; "ink" -> ink; "muted" -> muted; "paper" -> paper
        "control" -> control; "primaryText" -> onPrimary; "icon" -> iconBackground; else -> fallback
    }
    private class Surface(val kind: String, val original: Int, radius: Float, val stroke: Int = 0) : GradientDrawable() {
        init { cornerRadius = radius; refresh() }
        fun refresh() { setColor(color(kind, original)); if (stroke > 0) setStroke(stroke, border) }
    }
    fun Context.surface(color: Int, radius: Int = 12): GradientDrawable = Surface(role(color), color, dp(radius).toFloat())
    fun Context.iconSurface(): GradientDrawable = Surface("icon", iconBackground, dp(6).toFloat())
    fun Context.label(value: String, size: Float, color: Int = ink, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); tag = "mower:" + role(color)
        typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        gravity = Gravity.CENTER_VERTICAL
    }
    fun Context.action(value: String, primary: Boolean = false, run: () -> Unit) = Button(this).apply {
        text = value; textSize = 14f; isAllCaps = false
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        setTextColor(if (primary) onPrimary else ink); tag = if (primary) "mower:primaryText" else "mower:ink"
        background = RippleDrawable(ColorStateList.valueOf(0x18000000),
            Surface(if (primary) "green" else "control", 0, dp(6).toFloat(), if (primary) 0 else dp(1)), null)
        minimumWidth = 0; minWidth = 0; minimumHeight = dp(44); minHeight = dp(44)
        setPadding(dp(16), 0, dp(16), 0); stateListAnimator = null
        setOnTouchListener { _, event ->
            val scale = if (event.actionMasked == MotionEvent.ACTION_DOWN) .96f else 1f
            animate().scaleX(scale).scaleY(scale).setDuration(120).start(); false
        }
        setOnClickListener { run() }
    }
    fun applyTheme(view: View) {
        if (view is TextView && (view.tag as? String)?.startsWith("mower:") == true) {
            view.setTextColor(color((view.tag as String).removePrefix("mower:"), view.currentTextColor))
        }
        when (val bg = view.background) {
            is Surface -> bg.refresh()
            is RippleDrawable -> (bg.getDrawable(0) as? Surface)?.refresh()
        }
        if (view is ViewGroup) for (i in 0 until view.childCount) applyTheme(view.getChildAt(i))
    }
    fun Activity.chrome() {
        window.statusBarColor = paper; window.navigationBarColor = paper
        window.decorView.systemUiVisibility = if (dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
    }
}
