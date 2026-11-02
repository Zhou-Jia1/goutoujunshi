package com.shengjidaguai.goutoujunshi.ui
import android.app.Application
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.shengjidaguai.goutoujunshi.counsel.*
import com.shengjidaguai.goutoujunshi.designsystem.JunshiTheme
import com.shengjidaguai.goutoujunshi.domain.*
import org.junit.*
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.runner.RunWith
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(application=Application::class,sdk=[35],qualifiers="w411dp-h600dp-mdpi")
class T53BreathingFontRegressionTest(private val font:Float) {
 @get:Rule val compose=createComposeRule()
 @Test fun explicitDraftChoiceAfterCountdownReachesRenderedCallback() {
  val answer=StructuredAnswer(id="a",reply=listOf("合成待复制"))
  val request=AnalysisRequest(requestId="r",conversationId="c")
  val state=CounselState(loading=false,conversation=Conversation("c"),answer=answer,safety=SafetyLevel.CAUTION,answerRequest=request,answerConfigRevision="",messages=listOf(Message("m","c",MessageRole.ASSISTANT,requestId="r",status=MessageStatus.COMPLETED,answerJson=kotlinx.serialization.json.Json.encodeToString(StructuredAnswer.serializer(),answer))))
  var saved="";var copied="";var received:SafetyActionReceipt?=null
  compose.setContent {val density=LocalDensity.current;CompositionLocalProvider(LocalDensity provides Density(density.density,font),LocalCounselClipboard provides CounselClipboard({null},{copied=it})) {JunshiTheme {Surface {CounselContent(state,CounselUiActions(saveCoolingDraft={receipt,text->received=receipt;saved=text}))}}}}
  compose.onNodeWithTag("counsel-list").performScrollToNode(hasText("等你平静一点再看"));compose.onNodeWithText("等你平静一点再看").performClick()
  compose.onNodeWithTag("counsel-list").performScrollToNode(hasText("全部复制"));compose.onAllNodesWithText("全部复制").onFirst().performClick()
  compose.mainClock.advanceTimeBy(11000)
  compose.waitForIdle()
  println("T53_TARGET font=$font "+compose.onNodeWithText("先放草稿").printToString())
  compose.onNodeWithText("先放草稿").performScrollTo().assertIsDisplayed().performClick()
  compose.runOnIdle {println("T53_DIAGNOSTIC font=$font saved=$saved copied=$copied receiptMatches=${received==state.safetyActionReceipt()}")}
  Assert.assertEquals("合成待复制",saved);Assert.assertEquals("",copied);Assert.assertEquals(state.safetyActionReceipt(),received)
 }
 companion object {@JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name="font_{0}") fun cases()=listOf(arrayOf(1f),arrayOf(2.6f))}
}
