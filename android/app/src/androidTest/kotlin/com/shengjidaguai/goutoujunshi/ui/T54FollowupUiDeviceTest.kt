package com.shengjidaguai.goutoujunshi.ui
import androidx.compose.runtime.Composable
import androidx.compose.material3.Surface
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shengjidaguai.goutoujunshi.counsel.*
import com.shengjidaguai.goutoujunshi.designsystem.*
import com.shengjidaguai.goutoujunshi.domain.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
@Composable internal fun T54DeviceTheme(content:@Composable ()->Unit) {
 val mode=InstrumentationRegistry.getArguments().getString("t54Mode","light")
 JunshiTheme(darkTheme=mode=="dark",calm=mode=="calm",fontScaleLevel=if(mode=="font260") FontScaleLevel.ExtraLarge else FontScaleLevel.Standard) {
  val effective=androidx.compose.ui.platform.LocalDensity.current.fontScale
  androidx.compose.runtime.SideEffect {if(mode=="font260") assertEquals("System2.0 times App1.3 must be effective260",2.6f,effective,0.01f);println("T54_EFFECTIVE_FONT mode=$mode scale=$effective")}
  content()
 }
}
@RunWith(AndroidJUnit4::class)
class T54FollowupUiDeviceTest {
 @get:Rule val compose=createComposeRule()
 @Test fun offerInProductionAnswerContainerRequiresChoiceAndOptinAtEveryFontSize() {
  val answer=StructuredAnswer(id="synthetic-answer",reply=listOf("合成回复"))
  val request=AnalysisRequest(requestId="synthetic-request",contactId="owner",conversationId="conversation")
  val offer=FollowupOffer("owner","plan","conversation",0,0)
  val state=CounselState(loading=false,currentContact=Contact("owner","合成"),conversation=Conversation("conversation","owner",false),answer=answer,answerRequest=request,answerConfigRevision="",followupOffer=offer,
   messages=listOf(Message("synthetic-message","conversation",MessageRole.ASSISTANT,contactId="owner",requestId=request.requestId,status=MessageStatus.COMPLETED,answerJson=kotlinx.serialization.json.Json.encodeToString(StructuredAnswer.serializer(),answer))))
  assertTrue(state.hasCurrentAnswerSource())
  var count=0;var selected:FollowupOffer?=null
  compose.setContent {T54DeviceTheme {Surface {CounselContent(state,CounselUiActions(scheduleFollowup={receipt,_->selected=receipt;count++}))}}}
  compose.onNodeWithTag("counsel-list").performScrollToNode(isToggleable());compose.onNode(isToggleable()).assertIsOff()
  compose.onNodeWithTag("counsel-list").performScrollToNode(hasText("明天早上八点"));compose.onNodeWithText("明天早上八点").assertIsDisplayed().performClick()
  compose.onNodeWithTag("counsel-list").performScrollToNode(hasText("确认提醒时间"));compose.onNodeWithText("确认提醒时间").assertIsNotEnabled();assertEquals(0,count)
  compose.onNodeWithTag("counsel-list").performScrollToNode(isToggleable());compose.onNode(isToggleable()).performClick()
  compose.onNodeWithTag("counsel-list").performScrollToNode(hasText("确认提醒时间"));compose.onNodeWithText("确认提醒时间").assertIsDisplayed().performClick()
  assertEquals(1,count);assertEquals(offer,selected)
 }
 @Test fun routedQuestionAndAllThreeActionsRemainReachableWithoutSendingAModelRequest() {
  var uploads=0;var edits=0;var text=""
  val state=CounselState(loading=false,currentContact=Contact("owner","合成"),conversation=Conversation("conversation","owner",false),followupQuestion=true)
  compose.setContent {T54DeviceTheme {Surface {CounselContent(state,CounselUiActions(importImages={uploads++},editDraft={value,_->edits++;text=value}))}}}
  compose.onNodeWithTag("counsel-list").performScrollToNode(hasText("上次你发了那条消息，对方回了吗？"));compose.onNodeWithText("上次你发了那条消息，对方回了吗？").assertIsDisplayed()
  for(label in listOf("上传新截图","还没回","情况变了")) {compose.onNodeWithTag("counsel-list").performScrollToNode(hasText(label));compose.onNodeWithText(label).assertIsDisplayed().performClick()}
  assertEquals(1,uploads);assertEquals(2,edits);assertEquals("情况变了：",text)
 }
 @Test fun explicitAliasSettingIsReachableAndDoesNotCallModel() {
  var model=0
  compose.setContent {T54DeviceTheme {Surface {ModelSettingsContent(CounselState(loading=false),{_,_,_->model++},{model++},{},{})}}}
  compose.onNodeWithTag("model-settings").performScrollToNode(hasText("提醒显示联系人代号（锁定时仍隐藏）"))
  compose.onNodeWithText("提醒显示联系人代号（锁定时仍隐藏）").assertIsDisplayed()
  compose.onAllNodes(isToggleable()).onLast().performClick();assertEquals(0,model)
 }
 @Test fun temporaryFeedbackExplainsSaveThenSentWithZeroPermissionOrScheduleAndFormalDoesNotMislabel() {
  fun feedback(formal:Boolean):CounselState {
   val owner=if(formal) Contact("synthetic-owner","合成联系人") else null
   val origin=if(formal) "t54-native-formal" else "t54-native-temporary"
   val answer=StructuredAnswer(id="synthetic-answer",reply=listOf("合成回复"))
   val request=AnalysisRequest(requestId="synthetic-request",conversationId=origin,contactId=owner?.id)
   return CounselState(loading=false,currentContact=owner,conversation=Conversation(origin,owner?.id,!formal),feedbackSaved=true,answer=answer,answerRequest=request,answerConfigRevision="",
    messages=listOf(Message("synthetic-message",origin,MessageRole.ASSISTANT,contactId=owner?.id,requestId=request.requestId,status=MessageStatus.COMPLETED,answerJson=kotlinx.serialization.json.Json.encodeToString(StructuredAnswer.serializer(),answer))))
  }
  val holder=androidx.compose.runtime.mutableStateOf(feedback(false));assertTrue(holder.value.hasCurrentAnswerSource())
  var permissionAttempts=0;var scheduled=0
  val notice=InstrumentationRegistry.getInstrumentation().targetContext.getString(com.shengjidaguai.goutoujunshi.R.string.t54_temporary_notice)
  compose.setContent {T54DeviceTheme {Surface {
   val permission=rememberFollowupPermission {_,_,allowed->if(allowed) scheduled++}
   CounselContent(holder.value,CounselUiActions(scheduleFollowup={offer,due->permissionAttempts++;permission(offer,due)}))
  }}}
  compose.onNodeWithTag("counsel-list").performScrollToNode(hasText(notice));compose.onNodeWithText(notice).assertIsDisplayed()
  compose.onNodeWithText("确认提醒时间").assertDoesNotExist();compose.onAllNodes(isToggleable()).assertCountEquals(0)
  compose.runOnIdle {assertEquals(0,permissionAttempts);assertEquals(0,scheduled);holder.value=feedback(true);assertTrue(holder.value.hasCurrentAnswerSource())}
  compose.onNodeWithTag("counsel-list").performScrollToNode(hasTestTag("counsel-answer"));compose.onNodeWithText(notice).assertDoesNotExist()
  compose.runOnIdle {assertEquals(0,permissionAttempts);assertEquals(0,scheduled);println("T54_TEMPORARY_REMINDER permissionAttempts=$permissionAttempts scheduled=$scheduled")}
 }

}
