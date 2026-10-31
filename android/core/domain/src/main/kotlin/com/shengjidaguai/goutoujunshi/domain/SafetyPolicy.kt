package com.shengjidaguai.goutoujunshi.domain

import kotlinx.serialization.Serializable

@Serializable data class LockState(val enabled:Boolean=false,val unlocked:Boolean=false) {
    val canDisplaySensitiveContent:Boolean get() = !enabled || unlocked
}
@Serializable data class SafetyPresentation(val level:SafetyLevel=SafetyLevel.NONE,val calm:Boolean=false,val showOrdinaryStrategy:Boolean=true,val showSafetyCard:Boolean=false,val canDisplaySensitiveContent:Boolean=true)
object SafetyPolicy {
    fun evaluate(emotionLevel:Int?,modelSafety:SafetyLevel,localSafety:SafetyLevel=SafetyLevel.NONE,lockState:LockState=LockState()):SafetyPresentation {
        val safety=modelSafety.upgrade(localSafety)
        val visible=lockState.canDisplaySensitiveContent
        return SafetyPresentation(safety,(emotionLevel ?: 0)>=8 || safety!=SafetyLevel.NONE,
            visible && safety!=SafetyLevel.CRISIS,visible && safety==SafetyLevel.CRISIS,visible)
    }
}
