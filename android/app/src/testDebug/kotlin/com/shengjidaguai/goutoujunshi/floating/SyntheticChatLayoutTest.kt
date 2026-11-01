package com.shengjidaguai.goutoujunshi.floating
import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.shengjidaguai.goutoujunshi.capture.SyntheticChatContent
import com.shengjidaguai.goutoujunshi.designsystem.JunshiTheme
import com.shengjidaguai.goutoujunshi.R
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[35],qualifiers="zh-rCN-w411dp-h891dp-mdpi")
class SyntheticChatLayoutTest {
 @get:Rule val compose=createComposeRule()
 @Test fun fixtureActuallyPlacesMyMessageOnTheRightAndPeersOnLeft() {
  compose.setContent { JunshiTheme { SyntheticChatContent() } }
  val app=RuntimeEnvironment.getApplication()
  val width=compose.onRoot().fetchSemanticsNode().boundsInRoot.width
  Assert.assertTrue(compose.onNodeWithText(app.getString(R.string.capture_synthetic_two)).fetchSemanticsNode().boundsInRoot.center.x>width*.54f)
  Assert.assertTrue(compose.onNodeWithText(app.getString(R.string.capture_synthetic_one)).fetchSemanticsNode().boundsInRoot.center.x<width*.46f)
 }
}
