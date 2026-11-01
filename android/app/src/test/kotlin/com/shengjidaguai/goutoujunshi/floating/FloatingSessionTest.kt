package com.shengjidaguai.goutoujunshi.floating
import org.junit.Assert.*
import org.junit.Test
import com.shengjidaguai.goutoujunshi.domain.LockState
class FloatingSessionTest {
 @Test fun stoppedTicketCannotResurrect() {
  val session = FloatingSession()
  val ticket = session.request()
  session.stop()
  assertFalse(session.accept(ticket))
  assertTrue(session.accept(session.request()))
 }
 @Test fun snapKeepsReachableTouchAndClampsVerticalPosition() {
  val bounds = FloatingBounds(0, 24, 1080, 2300, 96, 48)
  assertEquals(FloatingPoint(-48, 24), bounds.dock(10, -900))
  assertEquals(FloatingPoint(1032, 2204), bounds.dock(900, 4000))
 }
 @Test fun lockUsesExistingExecutablePolicy() {
  assertFalse(floatingContentAllowed(LockState(enabled=true, unlocked=false)))
  assertTrue(floatingContentAllowed(LockState(enabled=true, unlocked=true)))
  assertTrue(floatingContentAllowed(LockState()))
 }
 @Test fun freshRamAuthorityCannotAcceptTicketFromDiscardedProcessAfterNewRequest() {
  val discarded=FloatingSession();val old=discarded.request()
  val fresh=FloatingSession();val current=fresh.request()
  assertTrue(fresh.valid(current))
  assertFalse("A new request must not revive a ticket retained by an old task Intent",fresh.valid(old))
 }

}
