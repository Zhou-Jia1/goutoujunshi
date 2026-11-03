package com.shengjidaguai.goutoujunshi.ui
import android.app.Application
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.shengjidaguai.goutoujunshi.R
import com.shengjidaguai.goutoujunshi.designsystem.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[35],qualifiers="zh-rCN-w411dp-h1000dp-mdpi")
class T54DialogFontTest {
 @get:Rule val compose=createComposeRule()
 @Test fun noticeDialogTextAndCloseButtonKeepEffective260InsideTheirWindow() {
  var outer=0f
  val context=RuntimeEnvironment.getApplication();val body=context.getString(R.string.t54_denied)
  compose.setContent {CompositionLocalProvider(LocalDensity provides Density(1f,2f)) {
   JunshiTheme(fontScaleLevel=FontScaleLevel.ExtraLarge) {
    val effective=LocalDensity.current.fontScale;SideEffect {outer=effective}
    FollowupDenied {}
   }
  }}
  assertEquals(2.6f,outer,0.01f)
  for(text in listOf(body,context.getString(R.string.close))) {
   val layouts=mutableListOf<TextLayoutResult>()
   compose.onNodeWithText(text).assertIsDisplayed().performSemanticsAction(SemanticsActions.GetTextLayoutResult) {it(layouts)}
   val result=layouts.single()
   println("T54_DIALOG_DENSITY outer=$outer text=${result.layoutInput.density.fontScale} font=${result.layoutInput.style.fontSize} height=${result.size.height}")
   assertEquals("Actual text measurement inside the new Dialog window must inherit effective260",2.6f,result.layoutInput.density.fontScale,0.01f)
  }
 }
}
