package com.shengjidaguai.goutoujunshi.floating

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Only MainActivity registers opaque RAM owners. Debug synthetic chat is not private Main UI. */
internal object CaptureOwnUi {
 data class Stamp(val revision:Long,val clear:Boolean)
 private val lock=Any()
 private val visible=mutableSetOf<Any>()
 private var revision=0L
 private val mutable=MutableStateFlow(Stamp(0,true))
 val changes=mutable.asStateFlow()
 private val warning=MutableStateFlow(false)
 val notice=warning.asStateFlow()
 fun started(owner:Any) {
  val stamp=synchronized(lock) { if(!visible.add(owner)) null else Stamp(++revision,false) }
  stamp?.let { mutable.value=it }
 }
 fun hidden(owner:Any) {
  val stamp=synchronized(lock) { if(!visible.remove(owner)) null else Stamp(++revision,visible.isEmpty()) }
  stamp?.let { mutable.value=it;if(it.clear) warning.value=false }
 }
 fun snapshot():Stamp=synchronized(lock) { Stamp(revision,visible.isEmpty()) }
 fun valid(stamp:Stamp):Boolean=synchronized(lock) { stamp.clear && visible.isEmpty() && revision==stamp.revision }
 fun commit(stamp:Stamp,action:()->Unit):Boolean=synchronized(lock) {
  if(!stamp.clear || visible.isNotEmpty() || revision!=stamp.revision) false
  else { action();true }
 }
 fun warn() { warning.value=true }
}
