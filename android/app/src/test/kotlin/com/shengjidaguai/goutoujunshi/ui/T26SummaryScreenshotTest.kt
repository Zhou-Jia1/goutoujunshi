package com.shengjidaguai.goutoujunshi.ui

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.shengjidaguai.goutoujunshi.designsystem.FontScaleLevel
import android.provider.Settings
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.captureRoboImage
import com.shengjidaguai.goutoujunshi.counsel.*
import com.shengjidaguai.goutoujunshi.designsystem.JunshiTheme
import com.shengjidaguai.goutoujunshi.domain.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "zh-rCN-w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class T26SummaryScreenshotTest(private val scenario: String, private val expected: String, private val appearance: String) {
    @get:Rule val compose = createComposeRule()

    @Test fun stateIsReadableWithAnimationsRemoved() {
        Settings.Global.putFloat(RuntimeEnvironment.getApplication().contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        val base = syntheticCounselState("empty").let { initial -> initial.copy(demo = false, modelStatus = ProviderKind.BYOK, draft = "虚构问题",
            messages = listOf(Message("synthetic-user", initial.conversation!!.id, MessageRole.USER,
                "虚构问题", requestId = "synthetic-request", status = MessageStatus.COMPLETED))) }
        val formal = base.copy(currentContact = Contact("synthetic", "虚构甲"),
            conversation = Conversation("synthetic-formal", "synthetic", false), draftSaved = true,
            messages = base.messages.map { it.copy(conversationId = "synthetic-formal", contactId = "synthetic") })
        val summary = AnalysisRequest(requestId = "synthetic-summary", purpose = OutboundPurpose.LONG_RECORD_SUMMARY)
        val state = when (scenario) {
            "running" -> base.copy(streaming = true, currentRequest = summary, waiting = WaitingPhase.at(5000))
            "ready" -> base.copy(summaryReady = true)
            "failed" -> base.copy(currentRequest = summary, error = CounselError("SUMMARY_INVALID", true))
            else -> base.copy(currentRequest = summary)
        }
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(1f, if (appearance == "font260") 2f else 1f)) {
            JunshiTheme(darkTheme = appearance == "dark", calm = appearance == "calm", fontScaleLevel = if (appearance == "font260") FontScaleLevel.ExtraLarge else FontScaleLevel.Standard) { Surface(Modifier.fillMaxSize()) { CounselContent(state, CounselUiActions()) } }
        } }
        compose.onNodeWithTag("counsel-list").performScrollToNode(hasText(expected, substring = true))
        compose.onNodeWithText(expected, substring = true).assertIsDisplayed()
        compose.onRoot().captureRoboImage("src/test/screenshots/t26_${scenario}_${appearance}.png")
    }

    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun cases() = listOf(
            arrayOf("step", "第一步：整理早期记录", "light"),
            arrayOf("running", "正在整理早期记录", "light"), arrayOf("running", "正在整理早期记录", "dark"), arrayOf("running", "正在整理早期记录", "calm"), arrayOf("running", "正在整理早期记录", "font260"),
            arrayOf("ready", "早期节选已整理", "light"),
            arrayOf("failed", "这次整理未保留可核对的消息编号", "light")
        )
    }
}
