package com.shengjidaguai.goutoujunshi.ui

import android.app.Application
import androidx.compose.material3.Surface
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.captureRoboImage
import com.shengjidaguai.goutoujunshi.designsystem.JunshiTheme
import com.shengjidaguai.goutoujunshi.importing.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "zh-rCN-w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImportScreenshotTest {
    @get:Rule val compose = createComposeRule()
    @Test fun preview() = capture("preview", ImportPreview(rows = listOf(ImportRow("虚构同事", "虚构测试：明天可以讨论吗？")), rightsAccepted = true))
    @Test fun error() = capture("error", ImportPreview(error = "IMPORT_READ", rightsAccepted = true))
    private fun capture(name: String, preview: ImportPreview) {
        compose.setContent { JunshiTheme { Surface { ImportContent(preview, emptyList()) } } }
        compose.onNodeWithTag("import-preview").assertIsDisplayed()
        compose.onRoot().captureRoboImage("src/test/screenshots/import_$name.png")
    }
}
