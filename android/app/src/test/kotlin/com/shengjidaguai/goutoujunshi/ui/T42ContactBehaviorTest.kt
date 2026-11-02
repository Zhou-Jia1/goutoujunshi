package com.shengjidaguai.goutoujunshi.ui

import android.app.Application
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.shengjidaguai.goutoujunshi.counsel.ContactDetailState
import com.shengjidaguai.goutoujunshi.data.ContactDetails
import com.shengjidaguai.goutoujunshi.designsystem.JunshiTheme
import com.shengjidaguai.goutoujunshi.domain.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class, sdk=[35])
class T42ContactBehaviorTest {
    @get:Rule val compose = createComposeRule()
    private val contact = Contact("fixture", "虚构联系人", notes="仅本机手工资料")
    @Test fun ownerChangeDismissesPrivateEditorAndPracticeDispatchesCurrentOwnerWithoutStartingTalk() {
        var state by mutableStateOf(ContactDetailState(contact.id, data=ContactDetails(contact)))
        var talks = 0
        val practiceOwners = mutableListOf<String>()
        compose.setContent { JunshiTheme { Surface { ContactDetailContent(state, {}, {}, { _, _ -> }, {}, {}, { talks++ }, practice = { practiceOwners += it }) } } }
        compose.onNodeWithText("对练").performClick()
        compose.runOnIdle { assertEquals(listOf(contact.id), practiceOwners); assertEquals(0, talks) }
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.onNodeWithTag("contact-detail-list").performScrollToNode(hasText("编辑档案"))
        compose.onNodeWithText("编辑档案").performClick()
        compose.onNodeWithText("仅本机手工资料").assertExists()
        compose.runOnIdle { val b=Contact("b", "另一位虚构联系人"); state=ContactDetailState(b.id, data=ContactDetails(b)) }
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.onNodeWithText("仅本机手工资料").assertDoesNotExist()
        compose.onNodeWithTag("contact-detail-list").performScrollToNode(hasText("对练"))
        compose.onNodeWithText("对练").performClick()
        compose.runOnIdle { assertEquals(listOf(contact.id, "b"), practiceOwners); assertEquals(0, talks) }
        compose.onNode(isDialog()).assertDoesNotExist()
    }
    @Test fun memoryAndActionLabelsDoNotTurnIntoChatFactsOrLearningConsent() {
        val data=ContactDetails(contact, plans=listOf(ActionPlan("action", contact.id, "虚构修改", true, PlanStatus.SENT)),
            memories=listOf(MemoryEntry("memory", contact.id, field="偏好", value="待确认的推测", sourceType=MemorySource.ASSISTANT_INFERENCE)))
        compose.setContent { JunshiTheme { Surface { ContactDetailContent(ContactDetailState(contact.id,data=data), {}, {}, { _, _ -> }, {}, {}, {}) } } }
        compose.onNodeWithText("你记录：修改后发送").assertExists()
        compose.onNodeWithText("档案：概览").performClick(); compose.onNodeWithText("记忆").performClick()
        compose.onNodeWithText("来源：助手推测（不是事实）").assertExists()
        compose.onNodeWithText("偏好：待确认的推测").assertExists()
    }
    @Test fun archivedContactIsExcludedFromNormalListButCanBeFoundInArchive() {
        compose.setContent { JunshiTheme { Surface { ContactsContent(listOf(contact.copy(archivedAt=1)), {}, {}, {}) } } }
        compose.onNodeWithText(contact.alias).assertDoesNotExist()
        compose.onNodeWithText("联系人：正在联系").performClick(); compose.onNodeWithText("已归档").performClick()
        compose.onNodeWithText(contact.alias).assertExists()
    }
}
