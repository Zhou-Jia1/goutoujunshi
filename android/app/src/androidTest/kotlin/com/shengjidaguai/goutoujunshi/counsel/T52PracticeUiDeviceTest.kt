package com.shengjidaguai.goutoujunshi.counsel
import androidx.compose.runtime.*
import androidx.compose.material3.Surface
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shengjidaguai.goutoujunshi.designsystem.JunshiTheme
import com.shengjidaguai.goutoujunshi.domain.*
import com.shengjidaguai.goutoujunshi.ui.*
import com.shengjidaguai.goutoujunshi.data.ContactDetails
import com.shengjidaguai.goutoujunshi.data.network.safeDestination
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
@RunWith(AndroidJUnit4::class)
class T52PracticeUiDeviceTest {
 @get:Rule val compose=createComposeRule()
 @Test fun contactDetailsEntryIsReachableAndCarriesCorrectOwner() {
  var owner:String?=null
  val contact=Contact("a","合成朋友")
  compose.setContent {JunshiTheme {Surface {ContactDetailContent(ContactDetailState("a",data=ContactDetails(contact)),{},{},{_,_->},{},{},{},practice={owner=it})}}}
  compose.onNodeWithTag("contact-detail-list").performScrollToNode(hasText("对练"))
  compose.onNodeWithText("对练").performScrollTo().performClick()
  compose.runOnIdle {assertEquals("a",owner)}
 }
 @Test fun answerNextStepsExpandToRealPracticeEntry() {
  var entered=false
  val answer=StructuredAnswer(id="native-answer",reply=listOf("明天再聊。"),next="先确认时间。")
  val request=AnalysisRequest(requestId="native-request",conversationId="native-conversation")
  val state=CounselState(loading=false,conversation=Conversation(request.conversationId),answer=answer,answerRequest=request,answerConfigRevision="",answerDemo=false,messages=listOf(Message("native-message",request.conversationId,MessageRole.ASSISTANT,answerJson=kotlinx.serialization.json.Json.encodeToString(StructuredAnswer.serializer(),answer),requestId=request.requestId,status=MessageStatus.COMPLETED)))
  compose.setContent {JunshiTheme {Surface {CounselContent(state,CounselUiActions(enterPractice={assertEquals(request.conversationId,it);entered=true}))}}}
  compose.onNodeWithTag("counsel-list").performScrollToNode(hasText("接下来"))
  compose.onNodeWithText("接下来").performClick()
  compose.onNodeWithTag("counsel-list").performScrollToNode(hasText("对练"))
  compose.onNodeWithText("对练").performScrollTo().performClick()
  compose.runOnIdle {assertTrue(entered)}
 }
 @Test fun summaryOnlySavesOnExplicitClickAndExitIsReachable() {
  var saved=false;var exited=false
  val view=PracticeView("native-practice",0,turns=listOf(PracticeRound("你怎么安排？","我明天先发方案。","你先说明时间，可以再提出确认请求。","我明天下午发方案，请你确认。")),summary=Tip("tip","确认下一步","安排后附确认请求。","明天下午请你确认时间。"),summaryReceipt="receipt")
  compose.setContent {JunshiTheme {Surface {PracticeContent(CounselState(loading=false,conversation=Conversation("native-conversation"),practice=view),CounselUiActions(savePracticeSummary={_,id,receipt->assertEquals(view.id,id);assertEquals("receipt",receipt);saved=true},exitPractice={_,id->assertEquals(view.id,id);exited=true}))}}}
  compose.runOnIdle {assertFalse(saved)}
  compose.onNodeWithTag("practice-screen").performScrollToNode(hasText("只收藏这条技巧"))
  compose.onNodeWithText("只收藏这条技巧").performScrollTo().performClick()
  compose.onNodeWithTag("practice-screen").performScrollToNode(hasText("退出对练"))
  compose.onNodeWithText("退出对练").performScrollTo().performClick()
  compose.runOnIdle {assertTrue(saved);assertTrue(exited)}
 }
 @Test fun budgetedPreviewShowsExactScopeAndRequiresExplicitSend() {
  var sends=0;var closes=0
  val assets=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.assets
  val builder=com.shengjidaguai.goutoujunshi.knowledge.PromptBuilder(com.shengjidaguai.goutoujunshi.knowledge.KnowledgeLibrary(com.shengjidaguai.goutoujunshi.knowledge.AssetKnowledgeSource(assets)))
  val request=AnalysisRequest(contactId="a",userText="对练",purpose=OutboundPurpose.PRACTICE,practice=PracticeRequestData("native-practice",20,PracticeStage.SUMMARY,PracticeDifficulty.NORMAL,(0 until 20).map {PracticeRound("合成对方问题${it}","敏".repeat(2000),"你说明了时间，可以再说清确认请求。","明天下午请你确认时间。")}),evidence=listOf(EvidenceBlock("i","PASTE",listOf(ChatLine("current-opponent","i",1,Speaker.THEM,"合成当前对方消息",speakerConfirmed=true,contactId="a")))),speakersConfirmed=true,redactionTerms=listOf("敏"))
  val prepared=builder.prepare(request);val selection=prepared.practiceSelection!!
  assertTrue(prepared.system.codePointCount(0,prepared.system.length)+prepared.user.codePointCount(0,prepared.user.length) in 30000..48000)
  val scope="选中${selection.selectedTurns}轮／省略${selection.omittedTurns}轮；对方样本选中${selection.opponentMessageIds.size}条／省略${selection.omittedOpponentMessages}条；仅基于选中的最近轮次总结。"
  val settings=com.shengjidaguai.goutoujunshi.data.network.SavedModelSettings(true,"https://practice.synthetic.example/v1/","synthetic-model","合成模型服务商","r1")
  val view=PracticeView("native-practice",20,turns=request.practice!!.turns,stage=PracticeStage.SUMMARY)
  val initial=CounselState(loading=false,demo=false,modelStatus=ProviderKind.BYOK,conversation=Conversation("native-conversation"),practice=view,practicePreviewReceipt="native-preview-first",preview=prepared,outboundBody=com.shengjidaguai.goutoujunshi.data.network.CompletionBody.encode(settings.connection(),prepared),modelSettings=com.shengjidaguai.goutoujunshi.data.network.ModelSettingsSnapshot(settings,"masked"))
  var current by mutableStateOf(initial)
  compose.setContent {JunshiTheme {Surface {PracticeContent(current,CounselUiActions(stop={closes++;current=current.copy(practicePreviewReceipt=null,preview=null,outboundBody=null)},sendPractice={origin,id,receipt ->assertEquals(current.practicePreviewReceipt,receipt);assertEquals("native-conversation",origin);assertEquals(view.id,id);sends++;current=current.copy(practicePreviewReceipt=null,preview=null,outboundBody=null)}))}}}
  compose.runOnIdle {assertEquals(0,sends)}
  compose.onNodeWithText(scope).performScrollTo().assertIsDisplayed()
  compose.onNodeWithText(settings.connection().safeDestination()).performScrollTo().assertIsDisplayed()
  compose.onNodeWithText("补充屏蔽词与昵称代号").performScrollTo().assertIsDisplayed()
  compose.onNodeWithText("完整发送正文（点击展开）").performScrollTo().performClick()
  val textNodes=compose.onAllNodes(hasAnyAncestor(hasTestTag("practice-outbound-body")) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Text),useUnmergedTree=true).fetchSemanticsNodes().sortedBy {it.positionInRoot.y}
  assertTrue(textNodes.size>1)
  val rendered=textNodes.joinToString("") {node -> node.config[SemanticsProperties.Text].joinToString("") {it.text}}
  assertTrue("Rendered body must equal the complete original CompletionBody",initial.outboundBody==rendered)
  val lastTag=textNodes.last().config[SemanticsProperties.TestTag]
  val last=compose.onNodeWithTag(lastTag,useUnmergedTree=true)
  last.performScrollTo().assertIsDisplayed()
  val scroller=compose.onNode(hasScrollAction() and hasAnyAncestor(isDialog()),useUnmergedTree=true)
  val before=scroller.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
  scroller.performSemanticsAction(SemanticsActions.ScrollBy) {it(0f,before.maxValue()-before.value())}
  compose.waitForIdle()
  val after=scroller.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
  assertEquals(after.maxValue(),after.value(),1f)
  val layouts=mutableListOf<TextLayoutResult>()
  last.performSemanticsAction(SemanticsActions.GetTextLayoutResult) {it(layouts)}
  assertEquals(1,layouts.size)
  val layout=layouts.single();val line=layout.getLineForOffset(layout.layoutInput.text.length-1)
  val tailTop=last.fetchSemanticsNode().positionInRoot.y+layout.getLineTop(line)
  val tailBottom=last.fetchSemanticsNode().positionInRoot.y+layout.getLineBottom(line)
  val viewport=scroller.fetchSemanticsNode().boundsInRoot
  assertTrue("Final line top must be inside viewport",tailTop>=viewport.top-1f)
  assertTrue("Final line bottom must be inside viewport",tailBottom<=viewport.bottom+1f)
  compose.onNodeWithText("确认并发送到真实模型").assertIsDisplayed()
  compose.onNodeWithText("关闭").assertIsDisplayed().performClick()
  compose.runOnIdle {assertEquals(1,closes);assertEquals(0,sends);current=initial.copy(practicePreviewReceipt="native-preview-next")}
  compose.onNodeWithText(scope).performScrollTo().assertIsDisplayed()
  compose.onNodeWithText("确认并发送到真实模型").performClick()
  compose.runOnIdle {assertEquals(1,sends)}
 }

}
