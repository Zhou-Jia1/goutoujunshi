package com.shengjidaguai.goutoujunshi.ui
import android.app.Application
import com.github.takahirom.roborazzi.captureRoboImage
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.shengjidaguai.goutoujunshi.counsel.*
import com.shengjidaguai.goutoujunshi.designsystem.*
import com.shengjidaguai.goutoujunshi.domain.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.RuntimeEnvironment
@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[35],qualifiers="zh-rCN-w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class T25AnswerBehaviorTest {
 @get:Rule val compose=createComposeRule()
 private fun text(id:Int)=RuntimeEnvironment.getApplication().getString(id)
 @Test fun allActionsRemainReachableAtEffective260AndFeedbackNeedsConfirmation() {
  val answer=StructuredAnswer(id="synthetic-answer",verdict="虚构：先确认安排，再说自己的想法。",reply=listOf("虚构第一条", "虚构第二条"),facts=listOf(Fact("已确认安排")),guesses=listOf(Guess("可能着急")),unknowns=listOf("具体期限"))
  val state=CounselState(loading=false,conversation=Conversation("synthetic-conversation"),draft="虚构输入",currentRequest=AnalysisRequest(requestId="request",conversationId="synthetic-conversation",userText="虚构输入"),answer=answer,
   messages=listOf(Message("user","synthetic-conversation",MessageRole.USER,"虚构输入",requestId="request",status=MessageStatus.COMPLETED), Message("assistant","synthetic-conversation",MessageRole.ASSISTANT,"虚构第一条",answerJson=kotlinx.serialization.json.Json.encodeToString(StructuredAnswer.serializer(),answer),requestId="request",status=MessageStatus.COMPLETED)))
  var feedback=0;var copied="";var rewrites=0
  compose.setContent { CompositionLocalProvider(LocalDensity provides Density(1f,2f),LocalCounselClipboard provides CounselClipboard({null},{copied=it})) {
   JunshiTheme(fontScaleLevel=FontScaleLevel.ExtraLarge) { Surface(Modifier.fillMaxSize()) { CounselContent(state.copy(answerRequest=state.currentRequest,answerConfigRevision=""),CounselUiActions(feedback={_,_,_->feedback++},rewrite={_,_,_,_->rewrites++})) } }
  } }
  fun reach(label:String) { compose.onNodeWithTag("counsel-list").performScrollToNode(hasText(label));compose.onAllNodesWithText(label).onFirst().performScrollTo().assertIsDisplayed().assertHasClickAction() }
  reach(text(com.shengjidaguai.goutoujunshi.designsystem.R.string.ds_copy_all));compose.onAllNodesWithText(text(com.shengjidaguai.goutoujunshi.designsystem.R.string.ds_copy_all)).onFirst().performClick()
  compose.onNodeWithText(text(com.shengjidaguai.goutoujunshi.R.string.close)).performClick()
  compose.runOnIdle { assertEquals("虚构第一条\n虚构第二条",copied) }
  reach(text(com.shengjidaguai.goutoujunshi.designsystem.R.string.ds_tone))
  reach(text(com.shengjidaguai.goutoujunshi.R.string.answer_apply_style));compose.onNodeWithText(text(com.shengjidaguai.goutoujunshi.R.string.answer_apply_style)).performClick()
  compose.runOnIdle { assertEquals(1,rewrites) }
  reach(text(com.shengjidaguai.goutoujunshi.designsystem.R.string.ds_sent));compose.onNodeWithText(text(com.shengjidaguai.goutoujunshi.designsystem.R.string.ds_sent)).performClick()
  compose.runOnIdle { assertEquals(0,feedback) }
  compose.onNodeWithText(text(com.shengjidaguai.goutoujunshi.R.string.answer_save_feedback)).assertIsDisplayed().performClick()
  compose.runOnIdle { assertEquals(1,feedback) }
  reach(text(com.shengjidaguai.goutoujunshi.designsystem.R.string.ds_edited));compose.onNodeWithText(text(com.shengjidaguai.goutoujunshi.designsystem.R.string.ds_edited)).performClick()
  compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performTextReplacement("虚构用户修改")
  compose.onNode(isDialog()).captureRoboImage("src/test/screenshots/t25_editor_font260.png")
  compose.onNodeWithText(text(com.shengjidaguai.goutoujunshi.R.string.answer_save_feedback)).assertIsDisplayed().performClick()
  compose.runOnIdle { assertEquals(2,feedback) }
 }
}
