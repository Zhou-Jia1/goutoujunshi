package com.shengjidaguai.goutoujunshi.privacy

import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.shengjidaguai.goutoujunshi.MainActivity
import com.shengjidaguai.goutoujunshi.counsel.CounselRuntime
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

class T55WelcomeDeviceTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun actualPrivacyClearReturnsToWelcomeAndExplicitButtonRestartsEmpty()=runBlocking {
        val app=compose.activity.application
        val controller=CounselRuntime.get(app)
        compose.waitUntil(15000) {!controller.state.value.loading}
        if(compose.onAllNodesWithText("重新开始").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithText("重新开始").performClick()
        compose.waitUntil(15000) {controller.state.value.conversation!=null}
        val marker=java.io.File(app.cacheDir,"t55-synthetic-marker").apply {writeText("synthetic")}
        LauncherAliases.select(app,2)
        app.getSharedPreferences("privacy",0).edit().putLong("delay",300_000).commit()
        compose.onNodeWithText("我的").performClick()
        val lockPrivacyTitle=app.getString(com.shengjidaguai.goutoujunshi.R.string.t55_title)
        compose.onNodeWithTag("model-settings").performScrollToNode(hasText(lockPrivacyTitle))
        compose.onAllNodesWithText(lockPrivacyTitle).assertCountEquals(1)
        compose.onNodeWithText(lockPrivacyTitle).performClick()
        compose.onNodeWithText("清除全部本机资料").performScrollTo().performClick()
        compose.onNodeWithText("清除全部资料").performClick()
        compose.waitUntil(15000) {!controller.state.value.loading && controller.state.value.conversation==null}
        compose.onNodeWithText("本机资料已清除").assertIsDisplayed()
        assertFalse(marker.exists());assertTrue(controller.state.value.contacts.isEmpty())
        assertFalse(app.getDatabasePath("junshi.db").exists());assertNull(CounselRuntime.store(app).vault.read("database"))
        assertFalse(app.getSharedPreferences("privacy",0).contains("delay"))
        val launch=android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER).setPackage(app.packageName)
        assertEquals("com.shengjidaguai.goutoujunshi.DefaultLauncher",app.packageManager.queryIntentActivities(launch,0).single().activityInfo.name)
        compose.onNodeWithText("重新开始").performClick()
        compose.waitUntil(15000) {controller.state.value.conversation!=null && !controller.state.value.loading}
        assertNull(controller.state.value.currentContact);assertTrue(controller.state.value.contacts.isEmpty())
        println("T55_NATIVE actual_clear_welcome_and_explicit_restart_pass")
    }
}
