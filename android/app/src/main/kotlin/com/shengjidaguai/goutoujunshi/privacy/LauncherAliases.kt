package com.shengjidaguai.goutoujunshi.privacy

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

internal object LauncherAliases {
    val names=listOf("DefaultLauncher","NotesLauncher","CalculatorLauncher")
    fun select(context:Context,index:Int) {
        require(index in names.indices)
        val manager=context.packageManager
        fun component(name:String)=ComponentName(context.packageName,"com.shengjidaguai.goutoujunshi.$name")
        // Enable first: interruption can leave two visible icons, never zero launchers.
        manager.setComponentEnabledSetting(component(names[index]),PackageManager.COMPONENT_ENABLED_STATE_ENABLED,PackageManager.DONT_KILL_APP)
        names.filterIndexed {i,_->i!=index}.forEach {manager.setComponentEnabledSetting(component(it),PackageManager.COMPONENT_ENABLED_STATE_DISABLED,PackageManager.DONT_KILL_APP)}
        check(context.getSharedPreferences("privacy",0).edit().putInt("launcher",index).commit())
    }
}
