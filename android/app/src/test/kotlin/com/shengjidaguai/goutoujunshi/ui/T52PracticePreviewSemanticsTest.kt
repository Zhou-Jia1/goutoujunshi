package com.shengjidaguai.goutoujunshi.ui
import android.app.Application
import androidx.compose.runtime.*
import androidx.compose.material3.Surface
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextLayoutResult
import com.shengjidaguai.goutoujunshi.domain.*
import com.shengjidaguai.goutoujunshi.counsel.*
import com.shengjidaguai.goutoujunshi.designsystem.JunshiTheme
import com.shengjidaguai.goutoujunshi.data.network.*
import com.shengjidaguai.goutoujunshi.knowledge.*
import java.io.File
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*
import kotlinx.serialization.json.*
@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[35],qualifiers="zh-rCN-w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class T52PracticePreviewSemanticsTest {
 @get:Rule val compose=createComposeRule()
 private fun fixture():CounselState {
  val root=File(System.getProperty("junshi.repositoryRoot")!!);val bundled=File(System.getProperty("junshi.generatedKnowledge")!!)
  val builder=PromptBuilder(KnowledgeLibrary(KnowledgeSource {path -> File(if(path.startsWith("knowledge/")) bundled else File(root,"android/core/knowledge/src/main/assets"),path.removePrefix("knowledge/")).readBytes()}))
  val request=AnalysisRequest(contactId="a",userText="对练",purpose=OutboundPurpose.PRACTICE,practice=PracticeRequestData("native-practice",20,PracticeStage.SUMMARY,PracticeDifficulty.NORMAL,(0 until 20).map {PracticeRound("合成对方问题${it}","敏".repeat(2000),"你说明了时间，可以再说清确认请求。","明天下午请你确认时间。")}),evidence=listOf(EvidenceBlock("i","PASTE",listOf(ChatLine("current-opponent","i",1,Speaker.THEM,"合成当前对方消息",speakerConfirmed=true,contactId="a")))),speakersConfirmed=true,redactionTerms=listOf("敏"))
  val prepared=builder.prepare(request)
  val settings=SavedModelSettings(true,"https://practice.synthetic.example/v1/","synthetic-model","合成模型服务商","r1")
  return CounselState(loading=false,demo=false,modelStatus=ProviderKind.BYOK,conversation=Conversation("native-conversation"),practice=PracticeView("native-practice",20,turns=request.practice!!.turns,stage=PracticeStage.SUMMARY),practicePreviewReceipt="native-preview-first",preview=prepared,outboundBody=CompletionBody.encode(settings.connection(),prepared),modelSettings=ModelSettingsSnapshot(settings,"masked"))
 }
 @Test fun exactNativeFixtureReportsBodyAndTailChunkBoundaries() {
  val state=fixture();val prepared=checkNotNull(state.preview);val body=checkNotNull(state.outboundBody)
  val chunks=outboundBodyChunks(body);val tail=body.takeLast(150)
  val metadata=buildJsonObject {
   put("bodyCodePoints",body.codePointCount(0,body.length));put("bodyUtf16Length",body.length);put("chunkCount",chunks.size)
   put("lastChunkCodePoints",chunks.last().codePointCount(0,chunks.last().length));put("lastChunkUtf16Length",chunks.last().length)
   put("tail150ContainedInAnyChunk",chunks.any {it.contains(tail)});put("tail150SpansFinalBoundary",!chunks.last().contains(tail))
   put("promptCodePoints",prepared.system.codePointCount(0,prepared.system.length)+prepared.user.codePointCount(0,prepared.user.length))
  }
  File("build/t52-preview-diagnostic.json").writeText(metadata.toString());println(metadata.toString())
  assertEquals(body,chunks.joinToString(""))
 }
 @Test fun actual260PercentRenderedBodyIsCompleteAndFinalLineAndActionsAreVisible() {
  val initial=fixture();var state by mutableStateOf(initial);var closes=0;var sends=0
  val selection=initial.preview!!.practiceSelection!!
  val scope="选中${selection.selectedTurns}轮／省略${selection.omittedTurns}轮；对方样本选中${selection.opponentMessageIds.size}条／省略${selection.omittedOpponentMessages}条；仅基于选中的最近轮次总结。"
  compose.setContent {CompositionLocalProvider(LocalDensity provides Density(1f,2.6f)) {JunshiTheme {Surface {PracticeContent(state,CounselUiActions(stop={closes++;state=state.copy(practicePreviewReceipt=null,preview=null,outboundBody=null)},sendPractice={_,_,receipt->assertEquals(state.practicePreviewReceipt,receipt);sends++;state=state.copy(practicePreviewReceipt=null,preview=null,outboundBody=null)}))}}}}
  compose.onNodeWithText(scope).performScrollTo().assertIsDisplayed()
  compose.onNodeWithText(initial.modelSettings.saved.connection().safeDestination()).performScrollTo().assertIsDisplayed()
  compose.onNodeWithText("补充屏蔽词与昵称代号").performScrollTo().assertIsDisplayed()
  compose.onNodeWithText("完整发送正文（点击展开）").performScrollTo().performClick()
  val textNodes=compose.onAllNodes(hasAnyAncestor(hasTestTag("practice-outbound-body")) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Text),useUnmergedTree=true).fetchSemanticsNodes().sortedBy {it.positionInRoot.y}
  assertTrue(textNodes.size>1)
  val rendered=textNodes.joinToString("") {node->node.config[SemanticsProperties.Text].joinToString("") {it.text}}
  assertTrue("Actual semantic Text must equal the original complete CompletionBody",initial.outboundBody==rendered)
  val last=compose.onNodeWithTag(textNodes.last().config[SemanticsProperties.TestTag],useUnmergedTree=true)
  last.performScrollTo().assertIsDisplayed()
  val scroller=compose.onNode(hasScrollAction() and hasAnyAncestor(isDialog()),useUnmergedTree=true)
  val before=scroller.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
  scroller.performSemanticsAction(SemanticsActions.ScrollBy) {it(0f,before.maxValue()-before.value())};compose.waitForIdle()
  val after=scroller.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
  assertEquals(after.maxValue(),after.value(),1f)
  val layouts=mutableListOf<TextLayoutResult>();last.performSemanticsAction(SemanticsActions.GetTextLayoutResult) {it(layouts)}
  assertEquals(1,layouts.size)
  val layout=layouts.single();val line=layout.getLineForOffset(layout.layoutInput.text.length-1)
  val finalTop=last.fetchSemanticsNode().positionInRoot.y+layout.getLineTop(line)
  val finalBottom=last.fetchSemanticsNode().positionInRoot.y+layout.getLineBottom(line)
  val viewport=scroller.fetchSemanticsNode().boundsInRoot
  assertTrue("Final line top must be visible",finalTop>=viewport.top-1f);assertTrue("Final line bottom must be visible",finalBottom<=viewport.bottom+1f)
  compose.onNodeWithText("确认并发送到真实模型").assertIsDisplayed();compose.onNodeWithText("关闭").assertIsDisplayed().performClick()
  compose.runOnIdle {assertEquals(1,closes);assertEquals(0,sends);state=initial.copy(practicePreviewReceipt="native-preview-next")}
  compose.onNodeWithText(scope).performScrollTo().assertIsDisplayed();compose.onNodeWithText("确认并发送到真实模型").performClick()
  compose.runOnIdle {assertEquals(1,sends)}
 }
}
