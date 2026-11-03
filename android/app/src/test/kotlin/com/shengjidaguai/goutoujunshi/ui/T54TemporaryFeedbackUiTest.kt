package com.shengjidaguai.goutoujunshi.ui
import android.app.Application
import androidx.compose.material3.Surface
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.shengjidaguai.goutoujunshi.R
import com.shengjidaguai.goutoujunshi.counsel.*
import com.shengjidaguai.goutoujunshi.designsystem.JunshiTheme
import com.shengjidaguai.goutoujunshi.domain.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
internal fun t54FeedbackState(formal:Boolean):CounselState {
 val owner=if(formal) Contact("synthetic-owner","合成联系人") else null
 val origin=if(formal) "synthetic-formal" else "synthetic-temporary"
 val answer=StructuredAnswer(id="synthetic-answer",reply=listOf("合成回复"))
 val request=AnalysisRequest(requestId="synthetic-request",conversationId=origin,contactId=owner?.id)
 return CounselState(loading=false,currentContact=owner,conversation=Conversation(origin,owner?.id,!formal),feedbackSaved=true,answer=answer,answerRequest=request,answerConfigRevision="",
  messages=listOf(Message("synthetic-message",origin,MessageRole.ASSISTANT,contactId=owner?.id,requestId=request.requestId,status=MessageStatus.COMPLETED,answerJson=kotlinx.serialization.json.Json.encodeToString(StructuredAnswer.serializer(),answer))))
}
@RunWith(RobolectricTestRunner::class) @Config(application=Application::class,sdk=[35],qualifiers="zh-rCN-w411dp-h1000dp-mdpi")
class T54TemporaryFeedbackUiTest {
 @get:Rule val compose=createComposeRule()
 @Test fun temporaryFeedbackExplainsExplicitSaveAndNeverRequestsPermissionOrSchedules() {
  val state=t54FeedbackState(false);assertTrue(state.hasCurrentAnswerSource())
  val notice=RuntimeEnvironment.getApplication().getString(R.string.t54_temporary_notice)
  var permissionAttempts=0;var scheduled=0
  compose.setContent {JunshiTheme {Surface {
   val permission=rememberFollowupPermission {_,_,granted->if(granted) scheduled++}
   CounselContent(state,CounselUiActions(scheduleFollowup={offer,due->permissionAttempts++;permission(offer,due)}))
  }}}
  compose.onNodeWithTag("counsel-list").performScrollToNode(hasTestTag("counsel-answer"))
  compose.onNodeWithText(notice).assertExists()
  compose.onNodeWithTag("counsel-list").performScrollToNode(hasText(notice));compose.onNodeWithText(notice).assertIsDisplayed()
  compose.onNodeWithText("确认提醒时间").assertDoesNotExist();compose.onAllNodes(isToggleable()).assertCountEquals(0)
  compose.runOnIdle {assertEquals(0,permissionAttempts);assertEquals(0,scheduled)}
 }
 @Test fun formalFeedbackDoesNotMislabelItselfAsTemporary() {
  val state=t54FeedbackState(true);assertTrue(state.hasCurrentAnswerSource())
  compose.setContent {JunshiTheme {Surface {CounselContent(state,CounselUiActions())}}}
  compose.onNodeWithTag("counsel-list").performScrollToNode(hasTestTag("counsel-answer"))
  compose.onNodeWithText(RuntimeEnvironment.getApplication().getString(R.string.t54_temporary_notice)).assertDoesNotExist()
 }
}
