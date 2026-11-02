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
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[35])
class T52PracticeSafetyUiTest {
 @get:Rule val compose=createComposeRule()
 @Test fun safetyPresentationAlwaysWinsEvenIfPracticeStateIsStillPresent() {
  compose.setContent {JunshiTheme {Surface {CounselContent(CounselState(loading=false,conversation=Conversation("synthetic"),practice=PracticeView("synthetic-practice",0,turns=listOf(PracticeRound("普通模拟问题"))),safety=SafetyLevel.CRISIS),CounselUiActions())}}}
  compose.onNodeWithTag("practice-screen").assertDoesNotExist()
  compose.onNodeWithText("普通模拟问题").assertDoesNotExist()
  compose.onNodeWithTag("counsel-list").performScrollToNode(hasText("你现在的安全最重要"))
  compose.onNodeWithText("你现在的安全最重要").assertIsDisplayed()
 }
 @Test fun previewChunksPreserveEveryCodePointAndOrdering() {
  val body=("合成🙂\n\"JSON\"\\".repeat(1300))+"END_合成末尾"
  val chunks=outboundBodyChunks(body)
  org.junit.Assert.assertEquals(body,chunks.joinToString(""))
  org.junit.Assert.assertTrue(chunks.size>1)
  org.junit.Assert.assertTrue(chunks.all {it.codePointCount(0,it.length)<=2000 && !it.first().isLowSurrogate() && !it.last().isHighSurrogate()})
 }

}
