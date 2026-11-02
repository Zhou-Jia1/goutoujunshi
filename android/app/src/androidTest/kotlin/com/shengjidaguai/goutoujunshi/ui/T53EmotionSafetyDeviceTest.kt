package com.shengjidaguai.goutoujunshi.ui
import android.app.Application
import androidx.compose.material3.Surface
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.shengjidaguai.goutoujunshi.counsel.*
import com.shengjidaguai.goutoujunshi.designsystem.JunshiTheme
import com.shengjidaguai.goutoujunshi.domain.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
@RunWith(AndroidJUnit4::class)
class T53EmotionSafetyDeviceTest {
 @get:Rule val compose=createComposeRule()
 @Test fun freeTextCalmFoldsAndFollowingChunksRetainExplicitExpansion() {
  val holder=androidx.compose.runtime.mutableStateOf(CounselState(loading=false,conversation=Conversation("stream")))
  compose.setContent {JunshiTheme(calm=holder.value.safetyPresentation().calm) {Surface {CounselContent(holder.value,CounselUiActions())}}}
  for(signal in listOf("emotion","caution","none")) {
   val first="T53_NATIVE_FIRST_$signal";val complete="$first\nT53_NATIVE_CONTINUED_$signal"
   compose.runOnIdle {holder.value=CounselState(loading=false,conversation=Conversation("stream"),streaming=true,
    currentRequest=AnalysisRequest(requestId=signal,conversationId="stream"),emotion=if(signal=="emotion")8 else null,
    safety=if(signal=="caution")SafetyLevel.CAUTION else SafetyLevel.NONE,partial=mapOf(SectionId.FREE_TEXT to first))}
   if(signal!="none") {
    compose.onNodeWithTag("counsel-list").performScrollToNode(hasText("等你平静一点再看"))
    compose.onNodeWithText(first).assertDoesNotExist()
    compose.onNodeWithText("等你平静一点再看").assertIsDisplayed().performClick()
   }
   compose.onNodeWithTag("counsel-list").performScrollToNode(hasText(first));compose.onNodeWithText(first).assertIsDisplayed()
   compose.runOnIdle {holder.value=holder.value.copy(partial=mapOf(SectionId.FREE_TEXT to complete))}
   compose.onNodeWithTag("counsel-list").performScrollToNode(hasText(complete));compose.onNodeWithText(complete).assertIsDisplayed()
  }
 }
 @Test fun alreadySavedDraftBodyFoldsInCalmWithoutLosingTheText() {
  val text="T53_NATIVE_COMPLETE_DRAFT"
  val shown=CounselState(loading=false,conversation=Conversation("draft",coolingDraft=CoolingDraft(text,1000)),safety=SafetyLevel.CAUTION)
  compose.setContent {JunshiTheme(calm=shown.safetyPresentation().calm) {Surface {CounselContent(shown,CounselUiActions())}}}
  compose.onNodeWithTag("counsel-list").performScrollToNode(hasTestTag("cooling-draft"))
  compose.onNodeWithText(text).assertDoesNotExist()
  compose.onNode(hasText("等你平静一点再看") and hasAnyAncestor(hasTestTag("cooling-draft"))).performScrollTo().assertIsDisplayed().performClick()
  compose.onNodeWithText(text).performScrollTo().assertIsDisplayed()
 }
 @Test fun cautionFoldsReplyUntilExplicitChoice() {
  compose.setContent {JunshiTheme {Surface {CounselContent(CounselState(loading=false,conversation=Conversation("synthetic"),safety=SafetyLevel.CAUTION,answer=StructuredAnswer(id="a",empathy="合成承接",next="先喝水",reply=listOf("合成建议"))),CounselUiActions())}}}
  compose.onNodeWithTag("counsel-list").performScrollToNode(hasText("合成承接"))
  compose.onNodeWithText("合成建议").assertDoesNotExist()
  compose.onNodeWithTag("counsel-list").performScrollToNode(hasText("等你平静一点再看"))
  compose.onNodeWithText("等你平静一点再看").performClick()
  compose.onNodeWithTag("counsel-list").performScrollToNode(hasText("先别急着发"))
  compose.onNodeWithText("先别急着发").assertExists()
 }
 private fun answerState():CounselState {
  val answer=StructuredAnswer(id="synthetic-answer",empathy="合成承接",next="先喝水",reply=listOf("合成待复制"))
  val request=AnalysisRequest(requestId="synthetic-request",conversationId="synthetic")
  return CounselState(loading=false,conversation=Conversation("synthetic"),safety=SafetyLevel.CAUTION,answer=answer,answerRequest=request,answerConfigRevision="",
   messages=listOf(Message("synthetic-message","synthetic",MessageRole.ASSISTANT,requestId=request.requestId,status=MessageStatus.COMPLETED,answerJson=kotlinx.serialization.json.Json.encodeToString(StructuredAnswer.serializer(),answer))))
 }
 @Test fun breathingNeverCopiesAutomaticallyAndCanSkipImmediately() {
  val shown=answerState()
  val copied=java.util.concurrent.atomic.AtomicReference("")
  compose.setContent {androidx.compose.runtime.CompositionLocalProvider(LocalCounselClipboard provides CounselClipboard({null},copied::set)) {JunshiTheme {Surface {CounselContent(shown,CounselUiActions())}}}}
  compose.onNodeWithTag("counsel-list").performScrollToNode(hasText("等你平静一点再看"));compose.onNodeWithText("等你平静一点再看").performClick()
  compose.onNodeWithTag("counsel-list").performScrollToNode(hasText("全部复制"));compose.onAllNodesWithText("全部复制").onFirst().performClick()
  compose.runOnIdle {org.junit.Assert.assertEquals("",copied.get())}
  compose.onNodeWithText("还是复制").performScrollTo().assertIsDisplayed().performClick()
  compose.waitUntil(timeoutMillis=5000) {copied.get()=="合成待复制"}
  compose.runOnIdle {org.junit.Assert.assertEquals("合成待复制",copied.get())}
 }
 @Test fun choosingDraftNeverWritesClipboard() {
  val shown=answerState()
  val copied=java.util.concurrent.atomic.AtomicReference("")
  val saved=java.util.concurrent.atomic.AtomicReference("")
  val received=java.util.concurrent.atomic.AtomicReference<SafetyActionReceipt?>()
  compose.setContent {androidx.compose.runtime.CompositionLocalProvider(LocalCounselClipboard provides CounselClipboard({null},copied::set)) {JunshiTheme {Surface {CounselContent(shown,CounselUiActions(saveCoolingDraft={receipt,text->received.set(receipt);saved.set(text)}))}}}}
  compose.onNodeWithTag("counsel-list").performScrollToNode(hasText("等你平静一点再看"));compose.onNodeWithText("等你平静一点再看").performClick()
  compose.onNodeWithTag("counsel-list").performScrollToNode(hasText("全部复制"));compose.onAllNodesWithText("全部复制").onFirst().performClick()
  compose.mainClock.advanceTimeBy(11000)
  compose.waitForIdle()
  compose.runOnIdle {org.junit.Assert.assertEquals("",copied.get());org.junit.Assert.assertEquals("",saved.get())}
  compose.onNodeWithText("先放草稿").performScrollTo().assertIsDisplayed().performClick()
  compose.waitUntil(timeoutMillis=5000) {saved.get()=="合成待复制"}
  compose.runOnIdle {
   org.junit.Assert.assertEquals("合成待复制",saved.get());org.junit.Assert.assertEquals("",copied.get())
   org.junit.Assert.assertEquals(shown.safetyActionReceipt(),received.get())
  }
 }

 @Test fun confirmingSafetyCollapsesOnlyCardAndKeepsSmallEntryAndOrdinaryHidden() {
  val holder=androidx.compose.runtime.mutableStateOf(answerState().copy(safety=SafetyLevel.CRISIS))
  compose.setContent {JunshiTheme {Surface {CounselContent(holder.value,CounselUiActions(acknowledgeSafety={_,collapsed->holder.value=holder.value.copy(safetyCollapsed=collapsed)}))}}}
  compose.onNodeWithTag("counsel-list").performScrollToNode(hasText("我现在是安全的"));compose.onNodeWithText("我现在是安全的").performClick()
  compose.onNodeWithTag("counsel-list").performScrollToNode(hasText("安全帮助"));compose.onNodeWithText("安全帮助").assertIsDisplayed()
  compose.onNodeWithTag("counsel-answer").assertDoesNotExist();org.junit.Assert.assertEquals(SafetyLevel.CRISIS,holder.value.safety)
  compose.onNodeWithText("安全帮助").performClick()
  compose.onNodeWithTag("counsel-list").performScrollToNode(hasText("你现在的安全最重要"));compose.onNodeWithText("你现在的安全最重要").assertIsDisplayed()
 }

 @Test fun floatingSafetyUsesSameCollapseAndNeverRestoresOrdinaryReply() {
  val holder=androidx.compose.runtime.mutableStateOf(answerState().copy(safety=SafetyLevel.CRISIS))
  compose.setContent {JunshiTheme {Surface {com.shengjidaguai.goutoujunshi.floating.FloatingPanelContent(holder.value,onSafetyCollapse={_,collapsed->holder.value=holder.value.copy(safetyCollapsed=collapsed)})}}}
  compose.onNodeWithText("我现在是安全的").performScrollTo().performClick()
  compose.onNodeWithText("安全帮助").assertIsDisplayed();compose.onNodeWithText("合成待复制").assertDoesNotExist()
  org.junit.Assert.assertEquals(SafetyLevel.CRISIS,holder.value.safety)
  compose.onNodeWithText("安全帮助").performClick()
  compose.onNodeWithText("你现在的安全最重要").assertExists()
 }

}
