package com.aliothmoon.maameow.mower

import android.content.Context
import android.widget.LinearLayout
import com.aliothmoon.maameow.mower.MowerStyle.dp

/** Reflow existing views without recreating WebView, dialogs or pending actions. */
internal class ResponsiveRow(context: Context) : LinearLayout(context) {
    private var compact: Boolean? = null
    private var wideParams = emptyList<LayoutParams>()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val narrow = MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED &&
            MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight < context.dp(560)
        if (compact != narrow) {
            if (compact == null) wideParams = (0 until childCount).map {
                LayoutParams(getChildAt(it).layoutParams as LayoutParams)
            }
            compact = narrow
            orientation = if (narrow) VERTICAL else HORIZONTAL
            for (index in 0 until childCount) {
                getChildAt(index).layoutParams = if (narrow) {
                    LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                        if (index < childCount - 1) bottomMargin = context.dp(10)
                    }
                } else LayoutParams(wideParams[index])
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
