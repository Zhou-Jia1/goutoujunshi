package com.shengjidaguai.goutoujunshi.floating

import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.*
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.platform.app.InstrumentationRegistry

/** Fixed geometry only: never dumps chat, replies, model bodies or arbitrary node text. */
internal object T33NativeUi {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val automation get()=instrumentation.uiAutomation
    var pump:()->Unit = {}
    fun nodes():List<AccessibilityNodeInfo> {
        pump()
        check(automation.clearCache())
        return SystemProjectionConsent.nodes()
    }
    private val wm get()=instrumentation.targetContext.getSystemService(WindowManager::class.java)
    private fun safe():Rect {
        val metrics=wm.maximumWindowMetrics
        return Rect(metrics.bounds).apply { inset(metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())) }
    }
    private fun subtract(source:Rect,cover:Rect):Rect? {
        val overlap=Rect(source)
        if(!overlap.intersect(cover)) return source
        return listOf(Rect(source.left,source.top,source.right,overlap.top),Rect(source.left,overlap.bottom,source.right,source.bottom),
            Rect(source.left,overlap.top,overlap.left,overlap.bottom),Rect(overlap.right,overlap.top,source.right,overlap.bottom))
            .filterNot { it.isEmpty }.maxByOrNull { it.width().toLong()*it.height() }
    }
    fun touchBounds(node:AccessibilityNodeInfo):Rect? {
        if(!node.isVisibleToUser || !node.isEnabled) return null
        val window=node.window ?: return null
        var bounds=Rect().also(node::getBoundsInScreen)
        val windowBounds=Rect().also(window::getBoundsInScreen)
        if(!bounds.intersect(windowBounds) || !bounds.intersect(safe()) || bounds.isEmpty) return null
        for(ime in automation.windows.filter { it.type==AccessibilityWindowInfo.TYPE_INPUT_METHOD && it.layer>window.layer }) {
            bounds=subtract(bounds,Rect().also(ime::getBoundsInScreen)) ?: return null
        }
        return bounds.takeUnless { it.isEmpty }
    }
    private fun actionNode(label:AccessibilityNodeInfo):AccessibilityNodeInfo? {
        var cursor:AccessibilityNodeInfo?=label
        repeat(10) {
            val candidate=cursor ?: return null
            if(candidate.isClickable && candidate.isEnabled) return candidate
            cursor=candidate.parent
        }
        return null
    }
    private fun completeActionBounds(node:AccessibilityNodeInfo):Rect? {
        val raw=Rect().also(node::getBoundsInScreen)
        val touch=touchBounds(node) ?: return null
        val minimum=(com.shengjidaguai.goutoujunshi.designsystem.JunshiLayout.touch.value * instrumentation.targetContext.resources.displayMetrics.density).toInt()-1
        return touch.takeIf { raw==touch && touch.width()>=minimum && touch.height()>=minimum }
    }
    fun visible(text:String,windowId:Int?=null):AccessibilityNodeInfo? = nodes().asSequence()
        .filter { it.text?.toString()==text && (windowId==null || it.windowId==windowId) }.mapNotNull(::actionNode)
        .firstOrNull { completeActionBounds(it)!=null }
    fun tap(node:AccessibilityNodeInfo) {
        node.refresh()
        val ball=node.contentDescription?.toString()==instrumentation.targetContext.getString(com.shengjidaguai.goutoujunshi.R.string.floating_ball_menu)
        val target=if(ball) node else checkNotNull(actionNode(node)) { "Controlled action has no clickable ancestor" }
        val bounds=checkNotNull(if(ball) touchBounds(target) else completeActionBounds(target)) { "No complete stable action/window/safe/IME intersection" }
        val time=SystemClock.uptimeMillis()
        for(action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)) {
            val event=MotionEvent.obtain(time,SystemClock.uptimeMillis(),action,bounds.centerX().toFloat(),bounds.centerY().toFloat(),0).apply { source=InputDevice.SOURCE_TOUCHSCREEN }
            try { check(automation.injectInputEvent(event,true)) } finally { event.recycle() }
            SystemClock.sleep(60)
        }
        pump()
        check(automation.clearCache())
    }
    fun swipe(windowId:Int,forward:Boolean,targetLabel:String?=null):Boolean {
        val viewport=nodes().filter { it.windowId==windowId && it.isScrollable }.mapNotNull { touchBounds(it) }.maxByOrNull { it.width().toLong()*it.height() } ?: return false
        val margin=(instrumentation.targetContext.resources.displayMetrics.density*16).toInt()
        if(viewport.height()<margin*4) return false
        val before=targetLabel?.let { label -> nodes().firstOrNull { it.windowId==windowId && it.text?.toString()==label }?.let { Rect().also(it::getBoundsInScreen) } }
        val x=viewport.centerX().toFloat()
        val low=(viewport.bottom-margin-viewport.height()/8).toFloat()
        val high=(viewport.top+margin+viewport.height()/8).toFloat()
        val start=if(forward) low else high
        val end=if(forward) high else low
        val down=SystemClock.uptimeMillis()
        for(step in 0..12) {
            val action=when(step) { 0->MotionEvent.ACTION_DOWN;12->MotionEvent.ACTION_UP;else->MotionEvent.ACTION_MOVE }
            val event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,start+(end-start)*step/12f,0).apply { source=InputDevice.SOURCE_TOUCHSCREEN }
            try { check(automation.injectInputEvent(event,true)) } finally { event.recycle() }
            SystemClock.sleep(24)
        }
        pump();check(automation.clearCache())
        val after=targetLabel?.let { label -> nodes().firstOrNull { it.windowId==windowId && it.text?.toString()==label }?.let { Rect().also(it::getBoundsInScreen) } }
        instrumentation.sendStatus(0,Bundle().apply { putString("t33.swipe","window=$windowId forward=$forward viewport=$viewport before=$before after=$after changed=${before!=after}") })
        return true
    }
    fun swipeUnderlyingAbovePanel(panelBounds:Rect) {
        val region=safe()
        region.bottom=minOf(region.bottom,panelBounds.top)
        val margin=(instrumentation.targetContext.resources.displayMetrics.density*16).toInt()
        region.inset(margin,margin)
        check(region.height()>margin*4) { "No underlying safe region above the panel" }
        val x=region.centerX().toFloat()
        val start=region.bottom-region.height()*.15f
        val end=region.top+region.height()*.15f
        val down=SystemClock.uptimeMillis()
        for(step in 0..12) {
            val action=when(step) {0->MotionEvent.ACTION_DOWN;12->MotionEvent.ACTION_UP;else->MotionEvent.ACTION_MOVE}
            val event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,start+(end-start)*step/12f,0).apply { source=InputDevice.SOURCE_TOUCHSCREEN }
            try { check(automation.injectInputEvent(event,true)) } finally { event.recycle() }
            SystemClock.sleep(24)
        }
        pump();check(automation.clearCache())
        instrumentation.sendStatus(0,Bundle().apply { putString("t33.underlyingGesture","safeRegion=$region whollyAbovePanel=${region.bottom<panelBounds.top}") })
    }
    fun diagnose(stage:String) {
        val lines=automation.windows.map { window -> val bounds=Rect();window.getBoundsInScreen(bounds);"id=${window.id} type=${window.type} layer=${window.layer} focused=${window.isFocused} bounds=$bounds" }.toMutableList()
        nodes().filter { it.isScrollable || it.isEditable || it.text?.toString() in setOf("拖动调整面板高度","补充一句","完成补充") }.forEach { node ->
            val bounds=Rect();node.getBoundsInScreen(bounds)
            val role=when(node.text?.toString()) { "拖动调整面板高度"->"handle";"补充一句"->"supplement";"完成补充"->"done";else->node.className?.toString().orEmpty() }
            lines+="window=${node.windowId} role=$role scrollable=${node.isScrollable} editable=${node.isEditable} focused=${node.isFocused} visible=${node.isVisibleToUser} clickable=${node.isClickable} class=${node.className} raw=$bounds touch=${touchBounds(node)} action=${actionNode(node)?.let { Rect().also(it::getBoundsInScreen) }}"
        }
        instrumentation.sendStatus(0,Bundle().apply { putString("t33.geometry","$stage\n"+lines.joinToString("\n")) })
    }
    fun scrollTo(text:String,windowId:Int):AccessibilityNodeInfo {
        diagnose("before-scroll")
        for(direction in listOf(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
            for(attempt in 0 until 80) {
                visible(text,windowId)?.let { candidate ->
                    var previous=Rect().also(candidate::getBoundsInScreen)
                    var stable=0
                    for(frame in 0 until 12) {
                        SystemClock.sleep(100)
                        val refreshed=visible(text,windowId) ?: break
                        val now=Rect().also(refreshed::getBoundsInScreen)
                        if(now==previous) stable++ else stable=0
                        previous=now
                        if(stable>=3) return refreshed
                    }
                }
                val scrollable=nodes().filter { it.windowId==windowId && it.isScrollable }
                if(scrollable.none { node -> node.actionList.any { it.id==direction } }) break
                if(!swipe(windowId,direction==AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,text)) break
                SystemClock.sleep(100)
            }
        }
        diagnose("failed-scroll")
        return checkNotNull(visible(text,windowId)) { "Required controlled panel action is unreachable" }
    }
}
