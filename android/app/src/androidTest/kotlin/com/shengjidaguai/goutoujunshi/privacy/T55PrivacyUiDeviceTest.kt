package com.shengjidaguai.goutoujunshi.privacy

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.shengjidaguai.goutoujunshi.designsystem.*
import org.junit.*
import org.junit.Assert.*

class T55PrivacyUiDeviceTest {
    @get:Rule val compose=createComposeRule()
    private val mode get()=InstrumentationRegistry.getArguments().getString("t55Mode","light")
    @androidx.compose.runtime.Composable private fun Theme(content:@androidx.compose.runtime.Composable ()->Unit) {
        JunshiTheme(darkTheme=mode=="dark",calm=mode=="calm",fontScaleLevel=if(mode=="font260")FontScaleLevel.ExtraLarge else FontScaleLevel.Standard,content=content)
    }
    @Test fun allPrivacyChoicesAndClearRemainReachableAtActualSystemDensity() {
        val choices=mutableListOf<Long>();val aliases=mutableListOf<Int>();var clears=0
        compose.setContent {Theme {Surface(Modifier.fillMaxSize()) {PrivacyContent(false,0,{},{},{choices+=it},{aliases+=it},{clears++})}}}
        for(text in listOf("1 分钟","5 分钟")) compose.onNodeWithText(text).performScrollTo().performClick()
        compose.onNodeWithText("计算记录").performScrollTo().performClick()
        compose.onNodeWithText(InstrumentationRegistry.getInstrumentation().targetContext.getString(com.shengjidaguai.goutoujunshi.R.string.clear_all)).performScrollTo().performClick()
        assertEquals(listOf(60_000L,300_000L),choices);assertEquals(listOf(2),aliases);assertEquals(1,clears)
    }
    @Test fun aliasConfirmationUsesActualInheritedDensityAndExplicitConfirmation() {
        var confirmed=0
        compose.setContent {Theme {AliasNotice({confirmed++},{})}}
        val layouts=mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithText("切换").assertIsDisplayed().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) {it(layouts)}
        assertEquals(if(mode=="font260")2.6f else 1f,layouts.single().layoutInput.density.fontScale,.001f)
        assertEquals(0,confirmed);compose.onNodeWithText("切换").performClick();assertEquals(1,confirmed)
    }
    @Test fun clearConfirmationKeepsActualDensityAndExplicitConfirmation() {
        var confirmed=0
        compose.setContent {Theme {ClearNotice({confirmed++},{})}}
        val layouts=mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithText("清除全部资料").assertIsDisplayed().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) {it(layouts)}
        compose.onNodeWithText("关闭").assertIsDisplayed()
        assertEquals(if(mode=="font260")2.6f else 1f,layouts.single().layoutInput.density.fontScale,.001f)
        assertEquals(0,confirmed);compose.onNodeWithText("清除全部资料").performClick();assertEquals(1,confirmed)
    }
}
