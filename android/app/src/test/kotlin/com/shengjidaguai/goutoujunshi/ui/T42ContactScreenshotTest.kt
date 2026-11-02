package com.shengjidaguai.goutoujunshi.ui

import android.app.Application
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import com.shengjidaguai.goutoujunshi.counsel.ContactDetailState
import com.shengjidaguai.goutoujunshi.data.ContactDetails
import com.shengjidaguai.goutoujunshi.designsystem.*
import com.shengjidaguai.goutoujunshi.domain.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "zh-rCN-w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class T42ContactScreenshotTest(private val page: String, private val mode: String) {
    @get:Rule val compose = createComposeRule()
    @Test fun profileActionsReachableAndSourceLabelsHonest() {
        val contact = Contact("synthetic-contact", "虚构小北", RelationType.MEDIATION, notes="我主动填写：希望先把沟通节奏放慢。")
        val data = ContactDetails(contact, plans=listOf(ActionPlan("synthetic-action", contact.id, "我先了解双方的诉求。", wasEdited=true, status=PlanStatus.SENT)))
        var deleted = false
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(1f, if(mode == "font260") 2f else 1f)) {
            JunshiTheme(darkTheme=mode == "dark", fontScaleLevel=if(mode == "font260") FontScaleLevel.ExtraLarge else FontScaleLevel.Standard) {
                Surface(Modifier.fillMaxSize()) {
                    if(page == "list") ContactsContent(listOf(contact,Contact("synthetic-boss", "虚构领导", RelationType.BOSS)), {}, {}, {})
                    else ContactDetailContent(ContactDetailState(contact.id, data=data), {}, {}, { _, _ -> }, { deleted = true }, {}, {})
                }
            }
        } }
        compose.onRoot().captureRoboImage("src/test/screenshots/t42_${page}_${mode}.png")
        if(page == "detail") {
            compose.onNodeWithTag("contact-detail-list").performScrollToNode(hasText("删除联系人"))
            compose.onNodeWithText("删除联系人").performClick()
            compose.onNodeWithText("删除这些记录").assertIsDisplayed()
            compose.onNode(isDialog()).captureRoboImage("src/test/screenshots/t42_delete_${mode}.png")
            assertFalse(deleted)
            compose.onNodeWithText("删除这些记录").performClick(); assertTrue(deleted)
        }
    }
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name="{0}_{1}")
        fun cases()=listOf("list", "detail").flatMap { page -> listOf("light", "dark", "font260").map { arrayOf(page,it) } }
    }
}
