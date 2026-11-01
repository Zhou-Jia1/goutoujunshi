package com.shengjidaguai.goutoujunshi.data.ocr
import com.shengjidaguai.goutoujunshi.domain.ocr.*
import org.junit.Assert.*
import org.junit.Test
class OcrFrameHintsTest {
 @Test fun bothImageEntrypointsSuggestToolbarAndInputAreasWithoutDeletingText() {
  val header=OcrLine("虚构标题",.99f,.2f,.01f,.8f,.04f)
  val footer=OcrLine("虚构输入栏",.99f,.2f,.97f,.8f,.99f)
  assertEquals(header.copy(suggestedUi=true),frameUiHint(header,1000,2000))
  assertEquals(footer.copy(suggestedUi=true),frameUiHint(footer,1000,2000))
 }
 @Test fun middleChatKeepsSideIdentityAndConfidenceAndExistingSuggestions() {
  val line=OcrLine("虚构聊天",.83f,.65f,.4f,.95f,.45f,suggestedSide=OcrSide.RIGHT)
  assertEquals(line,frameUiHint(line,1000,2000))
  assertTrue(frameUiHint(line.copy(suggestedUi=true),1000,2000).suggestedUi)
 }
 @Test fun rotatingToWideFrameDoesNotExpandToolbarMaskOverRealChat() {
  val body=OcrLine("虚构首条聊天",.96f,.04f,.15f,.35f,.22f)
  assertFalse(frameUiHint(body,2400,1000).suggestedUi)
  assertTrue(frameUiHint(body.copy(top=.01f,bottom=.04f),2400,1000).suggestedUi)
  assertTrue(frameUiHint(body.copy(top=.96f,bottom=.99f),2400,1000).suggestedUi)
 }

}
