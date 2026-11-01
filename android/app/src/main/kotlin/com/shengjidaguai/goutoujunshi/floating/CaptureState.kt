package com.shengjidaguai.goutoujunshi.floating
/** Main-thread session state; old callbacks never advance a replacement session. */
internal class CaptureState {
 enum class Phase { OFF, REQUESTING_PERMISSION, STARTING_SERVICE, READY, CAPTURING, RECOGNIZING, ANALYZING, STOPPING }
 @Volatile var phase=Phase.OFF; private set
 @Volatile var revision=0L; private set
 @Synchronized fun request():Long { revision++;phase=Phase.REQUESTING_PERMISSION;return revision }
 @Synchronized fun advance(epoch:Long,next:Phase):Boolean {
  if(epoch!=revision) return false
  val allowed=when(phase) {
   Phase.REQUESTING_PERMISSION->setOf(Phase.STARTING_SERVICE)
   Phase.STARTING_SERVICE->setOf(Phase.READY)
   Phase.READY->setOf(Phase.CAPTURING)
   Phase.CAPTURING->setOf(Phase.RECOGNIZING,Phase.READY)
   Phase.RECOGNIZING->setOf(Phase.ANALYZING,Phase.READY)
   Phase.ANALYZING->setOf(Phase.READY)
   else->emptySet()
  }
  if(next !in allowed) return false
  phase=next;return true
 }
 @Synchronized fun commit(epoch:Long,action:()->Unit):Boolean {
  if(epoch!=revision || phase==Phase.OFF || phase==Phase.STOPPING) return false
  action();return true
 }
 @Synchronized fun stop() { phase=Phase.STOPPING;revision++;phase=Phase.OFF }
}
