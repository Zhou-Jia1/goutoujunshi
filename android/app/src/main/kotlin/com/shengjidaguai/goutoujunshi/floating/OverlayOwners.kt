package com.shengjidaguai.goutoujunshi.floating

import androidx.lifecycle.*
import androidx.savedstate.*

/** Each attached ComposeView owns exactly one lifecycle and store. */
internal class OverlayOwners : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    override val lifecycle = LifecycleRegistry(this)
    override val viewModelStore = ViewModelStore()
    private val saved = SavedStateRegistryController.create(this)
    override val savedStateRegistry get() = saved.savedStateRegistry
    init {
        saved.performAttach()
        saved.performRestore(null)
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }
    fun destroy() {
        if (lifecycle.currentState == Lifecycle.State.DESTROYED) return
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        viewModelStore.clear()
    }
}
