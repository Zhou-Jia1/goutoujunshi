package com.shengjidaguai.goutoujunshi.floating

import android.content.Context
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.compose.ui.platform.ComposeView

/** This window has one physical gesture target. Route before any Compose child can consume it. */
internal class FloatingBallWindow(context: Context) : FrameLayout(context) {
    val composeView=ComposeView(context).also {
        addView(it,LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.MATCH_PARENT))
    }
    var handleMotion: (MotionEvent)->Boolean = { false }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean = handleMotion(event)
}
