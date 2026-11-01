package com.shengjidaguai.goutoujunshi.ui

import android.app.Application
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.github.takahirom.roborazzi.captureRoboImage
import com.shengjidaguai.goutoujunshi.counsel.CounselState
import com.shengjidaguai.goutoujunshi.data.network.*
import com.shengjidaguai.goutoujunshi.designsystem.JunshiTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "zh-rCN-w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ModelSettingsUiTest {
    @get:Rule val compose = createComposeRule()
    private val saved = ModelSettingsSnapshot(SavedModelSettings(true, "https://synthetic.example/v1", "synthetic-model", "Synthetic"), "••••1234")

    @Test fun secretEditorMasksInputAndSaveClearsEditor() {
        var received: String? = null
        compose.setContent { JunshiTheme { Surface(Modifier.fillMaxSize()) {
            ModelSettingsContent(CounselState(loading = false, modelSettings = saved),
                { _, key, _ -> received = key }, {}, {}, {})
        } } }
        val password = SemanticsMatcher.keyIsDefined(SemanticsProperties.Password)
        compose.onNodeWithTag("model-settings").performScrollToNode(password)
        compose.onNode(password).performTextInput("synthetic-only-secret")
        compose.onNode(password).assert(hasText("•".repeat(21)))
        compose.onAllNodes(hasText("synthetic-only-secret"), useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithTag("model-settings").performScrollToNode(hasText("保存模型设置"))
        compose.onNodeWithText("保存模型设置").performClick()
        compose.runOnIdle { assertEquals("synthetic-only-secret", received) }
        compose.onNodeWithTag("model-settings").performScrollToNode(password)
        compose.onNode(password).assertTextEquals("")
    }

    @Test fun cancelTestingIsExplicitAndSpecificErrorVisible() {
        var cancelled = 0
        compose.setContent { JunshiTheme { Surface(Modifier.fillMaxSize()) {
            ModelSettingsContent(CounselState(loading = false, modelSettings = saved, testingConnection = true,
                connectionResult = "HTTP_429"), { _, _, _ -> }, {}, { cancelled++ }, {})
        } } }
        compose.onNodeWithTag("model-settings").performScrollToNode(hasText("取消连接测试"))
        compose.onNodeWithText("取消连接测试").performClick()
        compose.runOnIdle { assertEquals(1, cancelled) }
        compose.onNodeWithTag("model-settings").performScrollToNode(hasText("服务商限制了请求频率（429），请稍后手动重试。"))
        compose.onNodeWithText("服务商限制了请求频率（429），请稍后手动重试。").assertIsDisplayed()
    }

    @Test fun captureNewModelSettings() {
        compose.setContent { JunshiTheme { Surface(Modifier.fillMaxSize()) {
            ModelSettingsContent(CounselState(loading = false, modelSettings = saved), { _, _, _ -> }, {}, {}, {})
        } } }
        compose.onRoot().captureRoboImage("src/test/screenshots/model_settings_light.png")
    }

    @Test fun fullClearAlsoErasesUnsavedPrivateEndpointAndKeyWhenSnapshotDoesNotChange() {
        var clears = 0
        compose.setContent { JunshiTheme { Surface(Modifier.fillMaxSize()) {
            ModelSettingsContent(CounselState(loading = false), { _, _, _ -> }, {}, {}, { clears++ })
        } } }
        compose.onNodeWithTag("model-settings").performScrollToNode(hasText("HTTPS Base URL"))
        compose.onNode(hasSetTextAction() and hasText("HTTPS Base URL")).performTextReplacement("https://private-synthetic.example/v1")
        val password = SemanticsMatcher.keyIsDefined(SemanticsProperties.Password)
        compose.onNodeWithTag("model-settings").performScrollToNode(password)
        compose.onNode(password).performTextInput("synthetic-unsaved-key")
        compose.onNodeWithTag("model-settings").performScrollToNode(hasText("清除全部本机资料"))
        compose.onNodeWithText("清除全部本机资料").performClick()
        compose.onNodeWithText("清除全部资料").performClick()
        compose.runOnIdle { assertEquals(1, clears) }
        compose.onNodeWithTag("model-settings").performScrollToNode(hasText("HTTPS Base URL"))
        compose.onNodeWithText("https://private-synthetic.example/v1").assertDoesNotExist()
        compose.onNodeWithTag("model-settings").performScrollToNode(password)
        compose.onNode(password).assertTextEquals("")
    }

    @Test fun modeSelectionRequiresExplicitSave() {
        var received: SavedModelSettings? = null
        compose.setContent { JunshiTheme { Surface(Modifier.fillMaxSize()) {
            ModelSettingsContent(CounselState(loading = false), { settings, _, _ -> received = settings }, {}, {}, {})
        } } }
        compose.onNodeWithText("模式：演示（不联网）").performClick()
        compose.onNodeWithText("真实模型（自备 Key）").performClick()
        compose.runOnIdle { assertNull(received) }
        compose.onNodeWithTag("model-settings").performScrollToNode(hasText("保存模型设置"))
        compose.onNodeWithText("保存模型设置").performClick()
        compose.runOnIdle { assertEquals(true, received?.live) }
    }
}
