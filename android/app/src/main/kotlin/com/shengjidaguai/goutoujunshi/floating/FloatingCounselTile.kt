package com.shengjidaguai.goutoujunshi.floating

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.*

class FloatingCounselTile : TileService() {
    override fun onStartListening() {
        qsTile?.apply { state=if(FloatingRuntime.active.value) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE; updateTile() }
    }
    override fun onClick() {
        if(isLocked) unlockAndRun { toggle() } else toggle()
    }
    // The PendingIntent overload does not exist below API 34; this branch never runs on 34+.
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    private fun toggle() {
        if(FloatingRuntime.active.value) { FloatingRuntime.stop(this); onStartListening(); return }
        val ticket=FloatingRuntime.session.request()
        val intent=Intent(this,FloatingCounselActivity::class.java).setAction(FloatingRuntime.TILE_START)
            .putExtra(FloatingCounselService.TICKET,ticket).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if(Build.VERSION.SDK_INT>=34) startActivityAndCollapse(PendingIntent.getActivity(this,31,intent,PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        else { @Suppress("DEPRECATION") startActivityAndCollapse(intent) }
    }
}
