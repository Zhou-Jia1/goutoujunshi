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

object FloatingRuntime {
    val session = FloatingSession()
    val active = MutableStateFlow(false)
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
        active.value = false
        context.stopService(Intent(context, FloatingCounselService::class.java))
        TileService.requestListeningState(context, ComponentName(context, FloatingCounselTile::class.java))
    }
    fun clearAll(context: Context) {
        stop(context)
        context.getSharedPreferences(FloatingCounselService.POSITIONS,0).edit().clear().apply()
    }
    fun open(context: Context, contacts: Boolean = false) {
        context.startActivity(Intent(context, FloatingCounselActivity::class.java)
            .setAction(if (contacts) CONTACTS else null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    fun unlock(context: Context) {
        context.startActivity(Intent(context,FloatingCounselActivity::class.java).setAction(UNLOCK).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    const val UNLOCK = "com.shengjidaguai.goutoujunshi.FLOATING_UNLOCK"
    const val TILE_START = "com.shengjidaguai.goutoujunshi.FLOATING_TILE_START"
    const val CONTACTS = "com.shengjidaguai.goutoujunshi.FLOATING_CONTACTS"
}
