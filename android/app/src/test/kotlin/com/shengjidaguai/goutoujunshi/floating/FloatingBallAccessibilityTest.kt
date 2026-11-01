package com.shengjidaguai.goutoujunshi.floating

import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.shengjidaguai.goutoujunshi.designsystem.*
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[35],qualifiers="zh-rCN")
class FloatingBallAccessibilityTest {
    @get:Rule val compose=createComposeRule()
    @Test fun ballAnnouncesMenuAndProvidesAccessibleClick() {
        var opened=false
        compose.setContent { JunshiTheme { FloatingMenuBall(Side.RIGHT) { opened=true } } }
        compose.onNodeWithContentDescription("悬浮军师，打开菜单").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        Assert.assertTrue(opened)
        compose.onNodeWithContentDescription("悬浮军师，点击分析").assertDoesNotExist()
    }
}
