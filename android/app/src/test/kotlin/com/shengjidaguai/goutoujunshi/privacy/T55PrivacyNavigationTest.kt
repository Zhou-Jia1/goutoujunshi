package com.shengjidaguai.goutoujunshi.privacy

import android.app.Application
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.shengjidaguai.goutoujunshi.counsel.CounselState
import com.shengjidaguai.goutoujunshi.designsystem.JunshiTheme
import com.shengjidaguai.goutoujunshi.ui.ModelSettingsContent
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(application=Application::class,sdk=[35],qualifiers="zh-rCN-w411dp-h1000dp-mdpi")
class T55PrivacyNavigationTest {
    @get:Rule val compose=createComposeRule()
    @Test fun dedicatedLockEntryIsDistinctFromExistingMemoryEntryAndReachesLockControls() {
        var privacy by mutableStateOf(false)
        compose.setContent {JunshiTheme {Surface {
            if(privacy) PrivacyContent(false,0,{privacy=false},{},{},{})
            else ModelSettingsContent(CounselState(),{_,_,_->},{},{},{},openMemory={},openPrivacy={privacy=true})
        }}}
        compose.onNodeWithTag("model-settings").performScrollToNode(hasText("记忆与隐私"))
        compose.onNodeWithText("记忆与隐私").assertExists()
        compose.onNodeWithTag("model-settings").performScrollToNode(hasText("应用锁与隐私"))
        compose.onNodeWithText("应用锁与隐私").performClick()
        compose.onNodeWithText("离开应用后锁定").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("开启应用锁").performScrollTo().assertIsDisplayed()
    }
}
