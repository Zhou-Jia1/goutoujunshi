package com.shengjidaguai.goutoujunshi.floating

import android.content.Context
import android.content.Intent
import android.service.quicksettings.TileService
import android.content.ComponentName
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.app.Application
import android.os.Build
import com.shengjidaguai.goutoujunshi.counsel.CounselRuntime
import kotlinx.coroutines.flow.MutableStateFlow

internal data class CaptureDebugSnapshot(val calls:Int,val accepted:Int,val pointerDowns:Int,val pointerUps:Int,val projection:ProjectionDebugSnapshot?)

object FloatingRuntime {
    val session = FloatingSession()
    val active = MutableStateFlow(false)
    /** Debug instrumentation may synchronously inspect a borrowed frame; never retain it. */
    internal var runtimeTicket:Long?=null
    internal var frameObserver:((Long,android.graphics.Bitmap)->Unit)?=null
    internal var captureDiagnostics:(()->CaptureDebugSnapshot?)?=null
    /** Read on Main; this optional debug snapshot never owns a frame or grant. */
    internal fun debugSnapshot():CaptureDebugSnapshot? = if(com.shengjidaguai.goutoujunshi.BuildConfig.DEBUG) captureDiagnostics?.invoke() else null
    internal var captureAction:(()->Unit)?=null
    internal var stopCapture:(()->Unit)?=null
    internal var showAnswer:(()->Unit)?=null
    internal var hideAnswer:(()->Unit)?=null
    internal val windows=mutableMapOf<android.app.Activity,Long>()
    internal fun bindWindow(activity:android.app.Activity,ticket:Long=runtimeTicket ?: session.epoch()) { windows[activity]=ticket }
    internal fun hideWindows(ticket:Long?=null) { if(ticket==null || ticket==runtimeTicket) hideAnswer?.invoke(); windows.toMap().filterValues { ticket==null || it==ticket }.keys.forEach { it.finish() } }
    internal var timeoutChanged:(()->Unit)?=null
    internal val timeout=MutableStateFlow(10)
    var timeoutMinutes:Int
        get()=timeout.value
        set(value) { require(value in listOf(5,10,30));timeout.value=value;timeoutChanged?.invoke() }
    private var watching = false
    /** Registered only after explicit entry; protects permission and credential trips too. */
    @Synchronized fun watchScreen(application: Application) {
        if(watching) return
        watching=true
        val receiver=object: BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                stop(context)
                val controller=CounselRuntime.get(application)
                val lock=controller.state.value.lockState
                if(lock.enabled) controller.updateLockState(lock.copy(unlocked=false))
            }
        }
        if(Build.VERSION.SDK_INT>=33) application.registerReceiver(receiver,IntentFilter(Intent.ACTION_SCREEN_OFF),Context.RECEIVER_NOT_EXPORTED)
        else application.registerReceiver(receiver,IntentFilter(Intent.ACTION_SCREEN_OFF))
    }
    fun stop(context: Context) {
        session.stop()
        hideWindows()
        stopCapture?.invoke()
        ProjectionGrantStore.clear()
        active.value = false
        context.stopService(Intent(context, FloatingCounselService::class.java))
        TileService.requestListeningState(context, ComponentName(context, FloatingCounselTile::class.java))
    }
    fun clearAll(context: Context) {
        stop(context)
        check(context.getSharedPreferences(FloatingCounselService.POSITIONS,0).edit().clear().commit())
    }
    fun open(context: Context, contacts: Boolean = false) {
        context.startActivity(Intent(context, FloatingCounselActivity::class.java)
            .setAction(if (contacts) CONTACTS else null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    internal fun showCapture(context:Context) {
        val preview=CounselRuntime.imports(context.applicationContext as Application).state.value ?: return
        val ticket=preview.captureSession?.takeIf {it==runtimeTicket} ?: return
        context.startActivity(Intent(context,FloatingCounselActivity::class.java).setAction(CAPTURE_CONFIRM)
            .putExtra(CAPTURE_PREVIEW,preview.id).putExtra(CAPTURE_TICKET,ticket)
            // This RAM-only review returns to the external captured app when it finishes.
            // Joining Main's existing task would expose Main and correctly close the panel
            // through CaptureOwnUi. Keep that safety guard; isolate only this review task.
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT or
                Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS))
    }
    fun unlock(context: Context) {
        context.startActivity(Intent(context,FloatingCounselActivity::class.java).setAction(UNLOCK).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    internal const val CAPTURE_PREVIEW="capture-preview"
    internal const val CAPTURE_TICKET="capture-ticket"
    const val CAPTURE_CONFIRM = "com.shengjidaguai.goutoujunshi.CAPTURE_CONFIRM"
    const val UNLOCK = "com.shengjidaguai.goutoujunshi.FLOATING_UNLOCK"
    const val TILE_START = "com.shengjidaguai.goutoujunshi.FLOATING_TILE_START"
    const val CONTACTS = "com.shengjidaguai.goutoujunshi.FLOATING_CONTACTS"
}
