package com.shengjidaguai.goutoujunshi.privacy

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import android.view.View
import com.shengjidaguai.goutoujunshi.counsel.CounselRuntime

/** Settings only; the sole display and authentication authority remains CounselController. */
internal object PrivacyRuntime : Application.ActivityLifecycleCallbacks {
    private lateinit var app:Application
    private val visible=mutableSetOf<Activity>()
    private val handler=Handler(Looper.getMainLooper())
    private val windows=mutableSetOf<Activity>()
    private var initialized=false
    private var temporaryOwner:Activity?=null
    private val returned=mutableMapOf<Activity,com.shengjidaguai.goutoujunshi.counsel.CounselController.PrivateUiHandoff>()
    private val privatePanels=mutableSetOf<View>()
    private val handoffs=java.util.WeakHashMap<Any,com.shengjidaguai.goutoujunshi.counsel.CounselController.PrivateUiHandoff>()
    private val preferences get()=app.getSharedPreferences("privacy",0)
    val enabled get()=CounselRuntime.applicationLockEnabled() ?: true
    internal fun readEnabled(preferences:android.content.SharedPreferences,hasFile:()->Boolean):Boolean = runCatching {
        // Android can turn an unreadable/malformed existing XML into an empty map.
        val values=preferences.all
        if(values.isEmpty() && hasFile()) true else preferences.getBoolean("enabled",false)
    }.getOrDefault(true)
    val delayMillis get()=runCatching {preferences.getLong("delay",0).takeIf {it in listOf(0L,60_000L,300_000L)} ?: 0L}.getOrDefault(0L)
    fun install(application:Application) {
        if(initialized) return
        app=application;initialized=true
        // Synchronous read precedes every private Activity's first frame. Never persist unlocked.
        val configured=readEnabled(preferences) {java.io.File(app.applicationInfo.dataDir,"shared_prefs/privacy.xml").exists()}
        CounselRuntime.get(app).updateLockState(com.shengjidaguai.goutoujunshi.domain.LockState(configured))
        app.registerActivityLifecycleCallbacks(this)
        com.shengjidaguai.goutoujunshi.floating.FloatingRuntime.watchScreen(app)
    }
    fun secure(activity:Activity) {
        if(enabled) activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
    fun setEnabled(value:Boolean):Boolean {
        if(value) {
            com.shengjidaguai.goutoujunshi.floating.FloatingRuntime.hideAnswer?.invoke()
            windows.toList().forEach {it.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)}
        }
        if(!preferences.edit().putBoolean("enabled",value).commit()) return false
        if(!value) CounselRuntime.get(app).updateLockState(com.shengjidaguai.goutoujunshi.domain.LockState())
        if(!value) windows.toList().forEach(::secure)
        return true
    }
    fun setDelay(value:Long) {require(value in listOf(0L,60_000L,300_000L));check(preferences.edit().putLong("delay",value).commit())}
    fun systemTrip(activity:Activity):com.shengjidaguai.goutoujunshi.counsel.CounselController.PrivateUiHandoff? {
        temporaryOwner=activity
        val controller=CounselRuntime.get(activity.application)
        controller.checkBackgroundLock(SystemClock.elapsedRealtime(),delayMillis)
        return controller.beginPrivateUiHandoff(SystemClock.elapsedRealtime())?.also {handoffs[activity]=it}
    }
    fun systemReturn(activity:Activity) {
        if(temporaryOwner===activity) temporaryOwner=null
        // ActivityResult can run before Application.onActivityStarted. Revoke an
        // expired ticket before its picker/auth/permission callback consumes it.
        CounselRuntime.get(activity.application).checkBackgroundLock(SystemClock.elapsedRealtime(),delayMillis)
    }
    fun explicitLeave() {depart()}
    /** Only the current user's capture action may continue/create this bounded transaction. */
    fun captureHandoff(application:Application,owner:Any):com.shengjidaguai.goutoujunshi.counsel.CounselController.PrivateUiHandoff? {
        val controller=CounselRuntime.get(application)
        controller.checkBackgroundLock(SystemClock.elapsedRealtime(),delayMillis)
        if(!controller.state.value.lockState.enabled || !controller.state.value.lockState.unlocked) return null
        val token=controller.beginPrivateUiHandoff(SystemClock.elapsedRealtime())?.also {handoffs[owner]=it}
        if(!privateUiVisible()) depart(application)
        return token
    }
    private fun privateUiVisible()=visible.isNotEmpty() || privatePanels.any {it.isAttachedToWindow && it.isShown}
    /** Call only from a real visible private WM panel, never from the control ball/service. */
    fun watchPrivatePanel(view:View) {
        view.addOnAttachStateChangeListener(object:View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v:View) {
                if(!v.isShown) return
                val controller=CounselRuntime.get(v.context.applicationContext as Application)
                if(!controller.state.value.lockState.enabled) return
                privatePanels+=v;handler.removeCallbacks(timeout)
                controller.enteredPrivateUi(SystemClock.elapsedRealtime(),delayMillis)
                controller.endPrivateUiHandoff(controller.currentPrivateUiHandoff(),SystemClock.elapsedRealtime(),delayMillis)
            }
            override fun onViewDetachedFromWindow(v:View) {
                if(privatePanels.remove(v) && !privateUiVisible()) depart(v.context.applicationContext as Application)
            }
        })
    }
    /** A returned picker/capture is complete only once its actual private Activity is started. */
    fun completeSystemTrip(activity:Activity,token:com.shengjidaguai.goutoujunshi.counsel.CounselController.PrivateUiHandoff?=handoffs[activity]) {
        systemReturn(activity)
        if(token==null || token!=CounselRuntime.get(activity.application).currentPrivateUiHandoff()) return
        handoffs[activity]=token
        if(activity in visible) finishHandoff(activity.application,activity,token) else returned[activity]=token
    }
    fun capturePrivateUiReturned(activity:Activity) {
        CounselRuntime.get(activity.application).currentPrivateUiHandoff()?.let {handoffs[activity]=it}
        completeSystemTrip(activity)
    }
    const val HANDOFF_EXTRA="com.shengjidaguai.goutoujunshi.PRIVATE_UI_RETURN"
    fun fullPrivateUiReturned(activity:Activity,intent:android.content.Intent) {
        val id=intent.getStringExtra(HANDOFF_EXTRA)
        intent.removeExtra(HANDOFF_EXTRA)
        val token=CounselRuntime.get(activity.application).currentPrivateUiHandoff()?.takeIf {it.id==id} ?: return
        completeSystemTrip(activity,token)
    }
    fun finishHandoff(application:Application,owner:Any,expected:com.shengjidaguai.goutoujunshi.counsel.CounselController.PrivateUiHandoff?=handoffs[owner]) {
        val controller=CounselRuntime.get(application)
        val token=handoffs[owner]?.takeIf {it==expected} ?: return
        handoffs.remove(owner)
        if(!controller.endPrivateUiHandoff(token,SystemClock.elapsedRealtime(),delayMillis)) return
        if(!controller.state.value.lockState.enabled || !controller.state.value.lockState.unlocked) return
        if(!privateUiVisible()) depart(application)
    }
    private val timeout=Runnable {CounselRuntime.get(app).checkBackgroundLock(SystemClock.elapsedRealtime(),delayMillis)}
    private fun depart(application:Application=app) {
        val controller=CounselRuntime.get(application)
        val remaining=controller.leftPrivateUi(SystemClock.elapsedRealtime(),delayMillis)
        handler.removeCallbacks(timeout)
        if(controller.state.value.lockState.enabled && controller.state.value.lockState.unlocked) handler.postDelayed(timeout,remaining)
    }
    override fun onActivityCreated(activity:Activity,state:Bundle?) {windows+=activity;secure(activity)}
    override fun onActivityStarted(activity:Activity) {
        visible+=activity;handler.removeCallbacks(timeout)
        CounselRuntime.get(app).enteredPrivateUi(SystemClock.elapsedRealtime(),delayMillis)
        returned.remove(activity)?.let {finishHandoff(activity.application,activity,it)}
        secure(activity)
    }
    override fun onActivityStopped(activity:Activity) {
        visible-=activity
        if(activity.isChangingConfigurations || privateUiVisible()) return
        // Started private Activities protect ordinary same-app handoff. Once none
        // remain, bind the deadline to this actual stop, even for system trips;
        // a frozen main Looper must not defer recording the departure itself.
        depart()
    }
    override fun onActivityDestroyed(activity:Activity) {visible-=activity;windows-=activity;returned-=activity;if(temporaryOwner===activity) temporaryOwner=null}
    override fun onActivityResumed(activity:Activity) {systemReturn(activity)}
    override fun onActivityPaused(activity:Activity) {}
    override fun onActivitySaveInstanceState(activity:Activity,state:Bundle) {}
}
