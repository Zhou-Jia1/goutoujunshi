package com.shengjidaguai.goutoujunshi

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.shengjidaguai.goutoujunshi.counsel.CounselViewModel
import com.shengjidaguai.goutoujunshi.domain.Speaker
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Explicitly invoke on the task-owned, no-GMS emulator. All pixels are invented fixtures. */
class T22PaddleUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private lateinit var vm:CounselViewModel
    private fun main(action:()->Unit)=InstrumentationRegistry.getInstrumentation().runOnMainSync(action)
    private fun text(id:Int)=compose.activity.getString(id)
    private fun scrollClick(id:Int) {
        compose.onNodeWithTag("import-preview").performScrollToNode(hasText(text(id)))
        compose.onNodeWithText(text(id)).performClick()
    }
    @Test fun sixRealFixturesThroughSideConfirmationImportPreviewAndDemoAnalysis() {
        compose.activityRule.scenario.onActivity { vm=ViewModelProvider(it)[CounselViewModel::class.java] }
        compose.waitUntil(20_000) { !vm.state.value.loading }
        for(name in listOf("light_1","light_2","dark_1","dark_2","long","group")) {
            val before=vm.state.value.conversation?.id
            main { vm.newTemporary() }
            compose.waitUntil(20_000) { vm.state.value.conversation?.id!=before && vm.state.value.evidence.isEmpty() }
            main { vm.receiveShare(Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM,
                Uri.parse("content://${compose.activity.packageName}.synthetic-ocr/$name"))) }
            compose.waitUntil { vm.imports.state.value!=null }
            scrollClick(R.string.ocr_start)
            compose.waitUntil(180_000) { vm.imports.state.value?.busy==false }
            val p=vm.imports.state.value!!
            assertNull("Fixture error: $name",p.error)
            assertTrue(p.recognized)
            assertTrue(p.rows.any { !it.excluded && it.text.contains("讨论") })
            compose.onNodeWithTag("import-preview").performScrollToNode(hasText(text(R.string.import_save)))
            compose.onNodeWithText(text(R.string.import_save)).assertIsNotEnabled()
            assertTrue(vm.state.value.evidence.isEmpty())
            if(!p.rightsAccepted) scrollClick(R.string.import_rights_accept)
            compose.onNodeWithTag("import-preview").performScrollToNode(hasText(text(R.string.import_target)+"："+text(R.string.import_choose_target)))
            compose.onNodeWithText(text(R.string.import_target)+"："+text(R.string.import_choose_target)).performClick()
            compose.onNodeWithText(text(R.string.temporary_title)).performClick()
            scrollClick(R.string.ocr_right_me)
            // Centered text is intentionally not assigned by the side suggestion.
            for(row in vm.imports.state.value!!.rows.filter { !it.excluded && it.speaker==null }) {
                val tag="ocr-speaker-${row.id}"
                compose.onNodeWithTag("import-preview").performScrollToNode(hasTestTag(tag))
                compose.onNode(hasText(text(R.string.import_speaker)+"："+text(R.string.import_unconfirmed)) and hasAnyAncestor(hasTestTag(tag))).performClick()
                compose.onNodeWithText(text(R.string.import_them)).performClick()
            }
            while(vm.imports.state.value!!.rows.any { !it.excluded && (it.ocrConfidence ?: 1f)<.8f && !it.reviewed }) {
                compose.onNodeWithTag("import-preview").performScrollToNode(hasText(text(R.string.ocr_reviewed)))
                compose.onAllNodesWithText(text(R.string.ocr_reviewed)).onFirst().performClick()
            }
            assertTrue(vm.imports.state.value!!.canConfirm)
            scrollClick(R.string.import_save)
            compose.waitUntil(20_000) { vm.imports.state.value==null }
            assertTrue(vm.state.value.evidence.isNotEmpty())
            assertTrue(vm.state.value.evidence.flatMap { it.lines }.all { it.speakerConfirmed && it.ocrConfidence!=null })
            val imported=vm.state.value.evidence.flatMap { it.lines }
            if(name=="long") assertEquals("Long screenshot must retain real repeated messages",24,imported.size)
            if(name=="group") {
                assertTrue("Group nicknames must remain attached to their messages",imported.mapNotNull { it.speakerName }.distinct().size>=2)
                assertTrue("Group participants must be distinct from the confirmed me side",imported.any { it.speaker==Speaker.OTHER })
            }
            val origin=vm.state.value.conversation!!.id
            main { vm.preparePreview(origin) }
            compose.waitUntil(20_000) { vm.state.value.preview!=null }
            assertNotNull(vm.state.value.preview)
            main { vm.analyze(origin) }
            compose.waitUntil(30_000) { vm.state.value.answer!=null && !vm.state.value.streaming }
            assertTrue(vm.state.value.answerDemo)
            assertTrue(vm.state.value.evidence.flatMap { it.lines }.any { it.speaker==Speaker.ME })
        }
    }
}
