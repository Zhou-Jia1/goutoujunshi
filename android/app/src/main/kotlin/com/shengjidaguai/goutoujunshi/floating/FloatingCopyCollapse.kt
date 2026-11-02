package com.shengjidaguai.goutoujunshi.floating

import kotlinx.coroutines.*

/** UI-only delayed collapse, separate from the controller's model-request authority. */
internal class FloatingCopyCollapse(private val scope:CoroutineScope) {
    private var pending:Job?=null
    private var interactionRevision=0L
    fun copied(stillCurrent:()->Boolean,collapse:()->Unit) {
        pending?.cancel()
        val copyRevision=++interactionRevision
        pending=scope.launch {
            delay(1000)
            if(interactionRevision==copyRevision && stillCurrent()) collapse()
        }
    }
    fun newInteraction() {
        interactionRevision++
        pending?.cancel();pending=null
    }
    fun close() = newInteraction()
}
