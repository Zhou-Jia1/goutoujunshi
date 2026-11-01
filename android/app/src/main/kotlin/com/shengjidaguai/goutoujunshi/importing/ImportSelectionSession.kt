package com.shengjidaguai.goutoujunshi.importing

internal enum class PickerKind { IMAGE, FILE }
internal data class SelectionAuthority(val epoch:Long,val conversation:String,val owner:String?,val preview:String?,val revision:Long)

/** One RAM slot per Activity ViewModel. Ownership changes invalidate its result, but do not
 * release the slot: the fixed AndroidX launcher must deliver that request's terminal callback
 * before another launch is allowed. No URI, registry key or saved state is stored here. */
internal class ImportSelectionSession(private val imports:ImportCoordinator) {
    private data class Slot(val kind:PickerKind,val authority:SelectionAuthority)
    private var slot:Slot?=null
    fun launch(kind:PickerKind,start:()->Unit):Boolean {
        if(slot!=null) return false
        val stamp=imports.beginSelection() ?: return false
        slot=Slot(kind,stamp)
        try { start() } catch(e:RuntimeException) { slot=null;throw e }
        return true
    }
    fun consume(kind:PickerKind):Boolean {
        val pending=slot?.takeIf { it.kind==kind } ?: return false
        slot=null
        return imports.selectionIsCurrent(pending.authority)
    }
}
