package com.shengjidaguai.goutoujunshi.ui
import android.app.Application
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.shengjidaguai.goutoujunshi.counsel.*
import com.shengjidaguai.goutoujunshi.designsystem.*
import com.shengjidaguai.goutoujunshi.domain.*
import com.shengjidaguai.goutoujunshi.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[35],qualifiers="zh-rCN-w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class T25FactSourceBehaviorTest {
 @get:Rule val compose=createComposeRule()
 private fun text(id:Int)=RuntimeEnvironment.getApplication().getString(id)
 @Test fun privateEditorClosesAndDiscardsTextWhenSourcePrivacyInputOrConfigChanges() {
  val answer=StructuredAnswer(id="answer",reply=listOf("原始虚构回复"))
  val request=AnalysisRequest(requestId="request",conversationId="conversation",userText="虚构问题")
  val base=CounselState(loading=false,conversation=Conversation("conversation"),draft=request.userText,answer=answer,answerRequest=request,answerConfigRevision="",
   messages=listOf(Message("assistant","conversation",MessageRole.ASSISTANT,"原始虚构回复",answerJson=kotlinx.serialization.json.Json.encodeToString(StructuredAnswer.serializer(),answer),requestId=request.requestId,status=MessageStatus.COMPLETED)))
  val state=mutableStateOf(base)
  compose.setContent { JunshiTheme { Surface(Modifier.fillMaxSize()) { CounselContent(state.value,CounselUiActions()) } } }
  val mutations=listOf<(CounselState)->CounselState>(
   { it.copy(evidence=listOf(EvidenceBlock("new-import"))) },
   { it.copy(redactionProfile=RedactionProfile(revision=1)) },
   { it.copy(draft="新的虚构问题",conversation=it.conversation!!.copy(revision=1)) },
   { it.copy(modelSettings=it.modelSettings.copy(saved=it.modelSettings.saved.copy(revision="new-config"))) })
  for (mutate in mutations) {
   compose.runOnIdle { state.value=base }
   compose.onNodeWithTag("counsel-list").performScrollToNode(hasText(text(com.shengjidaguai.goutoujunshi.designsystem.R.string.ds_edited)))
   compose.onNodeWithText(text(com.shengjidaguai.goutoujunshi.designsystem.R.string.ds_edited)).performScrollTo().performClick()
   compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performTextReplacement("旧编辑草稿")
   compose.runOnIdle { state.value=mutate(base) }
   compose.onNode(isDialog()).assertDoesNotExist()
   compose.onNodeWithText(text(com.shengjidaguai.goutoujunshi.designsystem.R.string.ds_edited)).assertDoesNotExist()
   compose.runOnIdle { state.value=base }
   compose.onNodeWithTag("counsel-list").performScrollToNode(hasText(text(com.shengjidaguai.goutoujunshi.designsystem.R.string.ds_edited)))
   compose.onNodeWithText(text(com.shengjidaguai.goutoujunshi.designsystem.R.string.ds_edited)).performScrollTo().performClick()
   compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).assertTextContains("原始虚构回复")
   compose.onNodeWithText(text(R.string.close)).performClick()
  }
 }
 @Test fun factsOpenOnlyConfirmedCurrentSourceAndConfidenceIsReadable() {
  val answer=StructuredAnswer(id="answer",reply=listOf("虚构建议"),facts=listOf(Fact("当前事实",listOf("machine-current")),Fact("无引用事实"),Fact("未知事实",listOf("machine-unknown")),Fact("跨联系人事实",listOf("machine-other"))),guesses=listOf(Guess("可能着急",Confidence.HIGH)))
  val state=mutableStateOf(CounselState(loading=false,conversation=Conversation("conversation","a",false),currentContact=Contact("a","虚构甲",sourceRevision=3),answer=answer,answerSourceRevision=3,draft="虚构用户明确说的话",
   evidence=listOf(EvidenceBlock("import",lines=listOf(ChatLine("machine-current","import",text="当前已确认片段",speaker=Speaker.THEM,speakerConfirmed=true,contactId="a"),ChatLine("machine-other","import",text="另一联系人不能出现",speakerConfirmed=true,contactId="b")))),
   messages=listOf(Message("user","conversation",MessageRole.USER,"虚构用户明确说的话",requestId="request",status=MessageStatus.COMPLETED,contactId="a"),Message("assistant","conversation",MessageRole.ASSISTANT,"虚构建议",answerJson=kotlinx.serialization.json.Json.encodeToString(StructuredAnswer.serializer(),answer),requestId="request",status=MessageStatus.COMPLETED,contactId="a"))).let { it.copy(answerRequest=AnalysisRequest(requestId="request",conversationId="conversation",contactId="a",sourceRevision=3,userText=it.draft,evidence=it.evidence),answerConfigRevision="") })
  compose.setContent { JunshiTheme { Surface(Modifier.fillMaxSize()) { CounselContent(state.value,CounselUiActions()) } } }
  val list=compose.onNodeWithTag("counsel-list")
  list.performScrollToNode(hasText(text(R.string.answer_evidence)));compose.onNodeWithText(text(R.string.answer_evidence)).performClick()
  list.performScrollToNode(hasText(text(R.string.answer_source)));compose.onNodeWithText(text(R.string.answer_source)).performScrollTo().performClick()
  compose.onNodeWithText("对方：当前已确认片段").assertIsDisplayed()
  compose.onAllNodesWithText("另一联系人不能出现",substring=true).assertCountEquals(0)
  compose.onAllNodesWithText("machine-",substring=true).assertCountEquals(0)
  compose.onNodeWithText(text(R.string.close)).performClick()
  list.performScrollToNode(hasText(text(R.string.answer_user_statement)));compose.onNodeWithText(text(R.string.answer_user_statement)).performScrollTo().performClick()
  compose.onNodeWithText("虚构用户明确说的话").assertIsDisplayed();compose.onNodeWithText(text(R.string.close)).performClick()
  list.performScrollToNode(hasText("置信度：高"));compose.onNodeWithText("置信度：高").performScrollTo().assertIsDisplayed()
  compose.onAllNodesWithText(text(R.string.answer_source_unavailable)).assertCountEquals(2)
  compose.runOnIdle { state.value=state.value.copy(currentContact=state.value.currentContact!!.copy(sourceRevision=4)) }
  compose.onNodeWithText(text(R.string.answer_source)).assertDoesNotExist()
  compose.onAllNodesWithText("machine-",substring=true).assertCountEquals(0)
 }
}
