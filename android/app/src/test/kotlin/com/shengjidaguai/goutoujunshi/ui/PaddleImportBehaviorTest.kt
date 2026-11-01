package com.shengjidaguai.goutoujunshi.ui

import android.app.Application
import android.net.Uri
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.shengjidaguai.goutoujunshi.R
import com.shengjidaguai.goutoujunshi.designsystem.JunshiTheme
import com.shengjidaguai.goutoujunshi.domain.*
import com.shengjidaguai.goutoujunshi.importing.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[35])
class PaddleImportBehaviorTest {
    @get:Rule val compose=createComposeRule()
    private fun text(id:Int)=RuntimeEnvironment.getApplication().getString(id)
    @Test fun sideMustBeExplicitEvenWhenEveryLineAlreadyHasSuggestedSpeaker() {
        val state=mutableStateOf(ImportPreview(source=ImportSource.SCREENSHOT,images=listOf(Uri.parse("content://synthetic/1")),
            recognized=true,rightsAccepted=true,target=ImportTarget(),rows=listOf(ImportRow(text="合成消息",speaker=Speaker.ME))))
        compose.setContent { JunshiTheme { Surface { ImportContent(state.value,emptyList(),side={ state.value=state.value.copy(mySide=it) }) } } }
        compose.onNodeWithTag("import-preview").performScrollToNode(hasText(text(R.string.import_save)))
        compose.onNodeWithText(text(R.string.import_save)).assertIsNotEnabled()
        compose.onNodeWithTag("import-preview").performScrollToNode(hasText(text(R.string.ocr_right_me)))
        compose.onNodeWithText(text(R.string.ocr_right_me)).performClick()
        compose.onNodeWithTag("import-preview").performScrollToNode(hasText(text(R.string.import_save)))
        compose.onNodeWithText(text(R.string.import_save)).assertIsEnabled()
    }
    @Test fun unconfirmedGeometryShowsOriginalSidesWithoutConfirmingIdentity() {
        val p=ImportPreview(source=ImportSource.SCREENSHOT,rightsAccepted=true,rows=listOf(
            ImportRow(text="合成左侧",side=com.shengjidaguai.goutoujunshi.domain.ocr.OcrSide.LEFT),
            ImportRow(text="合成右侧",side=com.shengjidaguai.goutoujunshi.domain.ocr.OcrSide.RIGHT)))
        compose.setContent { JunshiTheme { Surface { ImportContent(p,emptyList()) } } }
        compose.onNodeWithTag("import-preview").performScrollToNode(hasText("合成左侧"))
        val left=compose.onNodeWithText("合成左侧").fetchSemanticsNode().boundsInRoot.center.x
        compose.onNodeWithTag("import-preview").performScrollToNode(hasText("合成右侧"))
        val right=compose.onNodeWithText("合成右侧").fetchSemanticsNode().boundsInRoot.center.x
        org.junit.Assert.assertTrue("Expected original sides: left=$left right=$right",right>left)
        org.junit.Assert.assertFalse(p.canConfirm)
    }
    @Test fun replacementPreviewDiscardsProcessLocalAliasEditor() {
        val state=mutableStateOf(ImportPreview(rightsAccepted=true))
        compose.setContent { JunshiTheme { Surface { ImportContent(state.value,emptyList()) } } }
        compose.onNodeWithText(text(R.string.contact_alias_hint)).performTextInput("合成旧别名")
        compose.onNodeWithText("合成旧别名").assertExists()
        compose.runOnIdle { state.value=ImportPreview(rightsAccepted=true) }
        compose.onNodeWithText("合成旧别名").assertDoesNotExist()
    }
}
