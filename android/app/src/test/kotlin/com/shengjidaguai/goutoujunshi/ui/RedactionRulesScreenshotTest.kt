package com.shengjidaguai.goutoujunshi.ui

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.captureRoboImage
import com.shengjidaguai.goutoujunshi.designsystem.*
import com.shengjidaguai.goutoujunshi.domain.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "zh-rCN-w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RedactionRulesScreenshotTest(private val mode: String) {
    @get:Rule val compose = createComposeRule()
    @Test fun rulesAreScopedAndEditable() {
        var saved: List<String>? = null
        compose.setContent {
            JunshiTheme(darkTheme = mode == "dark", fontScaleLevel = FontScaleLevel.Standard) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(JunshiTheme.spacing.l).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(JunshiTheme.spacing.m)) {
                        RedactionRulesContent(RedactionProfile(terms = listOf("虚构蓝叶公司"), aliases = listOf(RedactionAlias("虚构昵称", "小伙伴"))), mode != "temporary", mode == "error") { terms, _ -> saved = terms }
                    }
                }
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/redaction_rules_${mode}.png")
        compose.onNodeWithText("保存规则（发送前需重新预览）").performScrollTo().performClick()
        assert(saved == listOf("虚构蓝叶公司"))
    }
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun cases(): List<Array<String>> = listOf("light", "dark", "temporary", "error").map { arrayOf(it) }
    }
}

