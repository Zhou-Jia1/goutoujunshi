package com.shengjidaguai.goutoujunshi.floating
import org.junit.Assert.*
import org.junit.Test
class CaptureStateTest {
 @Test fun consentIsRequiredAndStoppedEpochCannotPublish() {
  val state=CaptureState();val epoch=state.request()
  assertEquals(CaptureState.Phase.REQUESTING_PERMISSION,state.phase)
  assertFalse(state.advance(epoch,CaptureState.Phase.READY))
  assertTrue(state.advance(epoch,CaptureState.Phase.STARTING_SERVICE))
  assertTrue(state.advance(epoch,CaptureState.Phase.READY))
  assertTrue(state.advance(epoch,CaptureState.Phase.CAPTURING))
  state.stop();assertFalse(state.advance(epoch,CaptureState.Phase.RECOGNIZING))
  assertEquals(CaptureState.Phase.OFF,state.phase)
 }
 @Test fun oneClickCannotCaptureAgainWhileRecognizing() {
  val state=CaptureState();val epoch=state.request()
  state.advance(epoch,CaptureState.Phase.STARTING_SERVICE);state.advance(epoch,CaptureState.Phase.READY)
  assertTrue(state.advance(epoch,CaptureState.Phase.CAPTURING))
  assertFalse(state.advance(epoch,CaptureState.Phase.CAPTURING))
  assertTrue(state.advance(epoch,CaptureState.Phase.RECOGNIZING))
  assertFalse(state.advance(epoch,CaptureState.Phase.CAPTURING))
 }
}
