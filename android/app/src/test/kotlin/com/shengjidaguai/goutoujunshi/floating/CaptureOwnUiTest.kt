package com.shengjidaguai.goutoujunshi.floating
import org.junit.Assert.*
import org.junit.Test
class CaptureOwnUiTest {
 @Test fun anyVisibleMainBlocksAndOldOwnerCannotHideNewWindow() {
  val old=Any();val current=Any()
  try {
   CaptureOwnUi.started(old);CaptureOwnUi.started(current)
   CaptureOwnUi.hidden(old);CaptureOwnUi.hidden(old)
   assertFalse(CaptureOwnUi.snapshot().clear)
   CaptureOwnUi.hidden(current);assertTrue(CaptureOwnUi.snapshot().clear)
  } finally { CaptureOwnUi.hidden(old);CaptureOwnUi.hidden(current) }
 }
 @Test fun briefReturnInvalidatesEarlierFrameEvenAfterMainLeavesAndCommitIsGuarded() {
  val owner=Any();val before=CaptureOwnUi.snapshot();var commits=0
  assertTrue(CaptureOwnUi.commit(before) { commits++ })
  try {
   CaptureOwnUi.started(owner)
   assertFalse(CaptureOwnUi.commit(before) { commits++ })
   CaptureOwnUi.hidden(owner)
   assertTrue(CaptureOwnUi.snapshot().clear)
   assertFalse(CaptureOwnUi.valid(before));assertFalse(CaptureOwnUi.commit(before) { commits++ })
   assertEquals(1,commits)
  } finally { CaptureOwnUi.hidden(owner) }
 }
}
