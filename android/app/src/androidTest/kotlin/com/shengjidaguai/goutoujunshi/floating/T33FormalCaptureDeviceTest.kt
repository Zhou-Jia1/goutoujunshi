package com.shengjidaguai.goutoujunshi.floating

import android.content.ComponentName
import android.os.SystemClock
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.shengjidaguai.goutoujunshi.R
import com.shengjidaguai.goutoujunshi.counsel.CounselRuntime
import com.shengjidaguai.goutoujunshi.counsel.hasCurrentAnswerSource
import com.shengjidaguai.goutoujunshi.domain.FloatingSourceContext
import com.shengjidaguai.goutoujunshi.domain.RelationType
import org.junit.*
import org.junit.Assert.*
import java.util.UUID

/** Root-owned synthetic device test. Real consent/Paddle/SQLCipher/UI; no real model Key. */
class T33FormalCaptureDeviceTest {
    @get:Rule val compose=createAndroidComposeRule<FloatingCounselActivity>()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val app get()=instrumentation.targetContext.applicationContext as android.app.Application
    private val controller get()=CounselRuntime.get(app)
    private val imports get()=CounselRuntime.imports(app)
    private val fixtureAlias="T33-native-"+UUID.randomUUID().toString().take(8)
    private var fixtureContactId:String?=null
    private val sourceApp="SyntheticChat"
    private val sourceAccount="SyntheticAccount"
    private fun text(id:Int)=app.getString(id)

    private fun removeOwnTasks()=instrumentation.runOnMainSync {
        val own=ComponentName(app,T33SyntheticCaptureActivity::class.java)
        app.getSystemService(android.app.ActivityManager::class.java).appTasks
            .filter {it.taskInfo?.baseIntent?.component==own}.forEach {it.finishAndRemoveTask()}
    }
    @Before fun before() {
        removeOwnTasks()
        T33NativeUi.pump={compose.mainClock.advanceTimeByFrame();compose.waitForIdle()}
    }
    @After fun cleanup() {
        try {
            SystemProjectionConsent.cancelOwnDialog()
            instrumentation.runOnMainSync {imports.cancel();FloatingRuntime.stop(app)}
            val own=fixtureContactId ?: controller.state.value.contacts.singleOrNull {it.alias==fixtureAlias}?.id
            if(own!=null) {
                controller.deleteContact(own)
                compose.waitUntil(15000) {controller.state.value.contacts.none {it.id==own}}
            }
        } finally {
            try {removeOwnTasks()} finally {T33NativeUi.pump={}}
        }
    }

    private fun importClick(id:Int) {
        compose.onNodeWithTag("import-preview").performScrollToNode(hasText(text(id)))
        compose.onNodeWithText(text(id)).performClick()
    }
    private fun field(id:Int)=compose.onNodeWithText(text(id))
    private fun enterField(id:Int,value:String) {
        compose.onNodeWithTag("import-preview").performScrollToNode(hasText(text(id)))
        field(id).performTextReplacement(value)
    }
    private fun source() {
        enterField(R.string.t33_source_app,sourceApp)
        enterField(R.string.t33_source_account,sourceAccount)
        importClick(R.string.t33_confirm_source)
        compose.waitUntil(5000) {imports.state.value?.floatingSource==FloatingSourceContext(sourceApp,sourceAccount)}
    }
    private fun rows() {
        importClick(R.string.ocr_right_me)
        for(row in checkNotNull(imports.state.value).rows.filter {!it.excluded && it.speaker==null}) {
            val tag="ocr-speaker-"+row.id
            compose.onNodeWithTag("import-preview").performScrollToNode(hasTestTag(tag))
            compose.onNode(hasText(text(R.string.import_speaker)+"："+text(R.string.import_unconfirmed)) and hasAnyAncestor(hasTestTag(tag))).performClick()
            compose.onNodeWithText(text(R.string.import_them)).performClick()
        }
        while(checkNotNull(imports.state.value).rows.any {!it.excluded && (it.ocrConfidence ?: 1f)<.8f && !it.reviewed}) {
            compose.onNodeWithTag("import-preview").performScrollToNode(hasText(text(R.string.ocr_reviewed)))
            compose.onAllNodesWithText(text(R.string.ocr_reviewed)).onFirst().performClick()
        }
    }
    private fun reviewFormal(continuing:Boolean=false) {
        // Recognition finishes before showCapture's Activity has resumed and
        // published its merged semantics. Wait for this exact preview's UI.
        val previewId=checkNotNull(imports.state.value).id
        compose.waitUntil(15000) {
            var resumed=false
            instrumentation.runOnMainSync {
                resumed=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .any {it is FloatingCounselActivity && it.hasWindowFocus() &&
                        it.intent.getStringExtra(FloatingRuntime.CAPTURE_PREVIEW)==previewId}
            }
            resumed && compose.onAllNodesWithTag("import-preview").fetchSemanticsNodes().size==1
        }
        if(!checkNotNull(imports.state.value).rightsAccepted) importClick(R.string.import_rights_accept)
        val target=text(R.string.import_target)+"："+text(R.string.import_choose_target)
        compose.onNodeWithTag("import-preview").performScrollToNode(hasText(target))
        compose.onNodeWithText(target).performClick()
        compose.onNodeWithText(fixtureAlias).performClick()
        source()
        if(continuing) importClick(R.string.t33_continue_capture)
        rows()
        assertTrue(checkNotNull(imports.state.value).canConfirm)
    }
    private fun capture() {
        compose.waitUntil(15000) {T33NativeUi.nodes().any {it.text?.toString()==text(R.string.capture_synthetic_title)}}
        instrumentation.waitForIdleSync();SystemClock.sleep(500)
        var ball:android.view.accessibility.AccessibilityNodeInfo?=null
        compose.waitUntil(15000) {
            ball=T33NativeUi.nodes().singleOrNull {it.contentDescription?.toString()==text(R.string.floating_ball_menu) && T33NativeUi.touchBounds(it)!=null}
            ball!=null
        }
        T33NativeUi.tap(checkNotNull(ball))
        compose.waitUntil(120000) {imports.state.value?.recognized==true}
        assertTrue(checkNotNull(imports.state.value).rows.any {!it.excluded})
    }
    private fun saveAndPanel(formal:Boolean=true):android.view.accessibility.AccessibilityNodeInfo {
        importClick(R.string.import_save)
        compose.waitUntil(15000) {imports.state.value==null && T33NativeUi.visible(text(R.string.t33_resize))!=null}
        assertEquals(if(formal) fixtureContactId else null,controller.state.value.currentContact?.id)
        assertNotNull(controller.state.value.floatingCapture)
        assertEquals(1,controller.state.value.evidence.size)
        return checkNotNull(T33NativeUi.visible(text(R.string.t33_resize)))
    }
    private fun demoAnswer(windowId:Int,forbiddenTexts:List<String> = emptyList()) {
        T33NativeUi.tap(T33NativeUi.scrollTo(text(R.string.preview_request),windowId))
        compose.waitUntil(15000) {controller.state.value.floatingPreviewReady}
        val prepared=checkNotNull(controller.state.value.preview)
        assertTrue(prepared.references.isNotEmpty());assertTrue(prepared.knowledgeVersion.isNotBlank())
        forbiddenTexts.forEach {assertFalse("Temporary preview must omit unselected historical lines",prepared.user.contains(it))}
        val actual=prepared.system+"\n\n"+prepared.user
        var bodySeen=false
        for(attempt in 0 until 12) {
            bodySeen=T33NativeUi.nodes().any {it.windowId==windowId && it.text?.toString()==actual && T33NativeUi.touchBounds(it)!=null}
            if(bodySeen) break
            T33NativeUi.swipe(windowId,true);SystemClock.sleep(100)
        }
        assertTrue("The actual knowledge-prepared body must be visible",bodySeen)
        T33NativeUi.tap(T33NativeUi.scrollTo(text(R.string.live_send),windowId))
        compose.waitUntil(30000) {controller.state.value.answer!=null && !controller.state.value.streaming}
        assertTrue(controller.state.value.answerDemo)
        assertTrue(controller.state.value.hasCurrentAnswerSource())
    }
    private fun clearAndReconfirm(id:Int,value:String) {
        assertNotNull(checkNotNull(imports.state.value).captureContinuationId)
        compose.onNodeWithTag("import-preview").performScrollToNode(hasText(text(id)))
        val node=field(id)
        node.performClick();node.performTextInputSelection(TextRange(0,value.length))
        node.performKeyInput {pressKey(Key.Backspace)}
        compose.waitForIdle()
        node.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText,AnnotatedString("")))
        compose.waitUntil(5000) {imports.state.value?.floatingSource==null && imports.state.value?.captureContinuationId==null}
        assertFalse(checkNotNull(imports.state.value).captureMoreEligible)
        assertFalse(checkNotNull(imports.state.value).canConfirm)
        importClick(R.string.t33_confirm_source)
        assertNull(checkNotNull(imports.state.value).floatingSource)
        enterField(id,value)
        importClick(R.string.t33_confirm_source)
        compose.waitUntil(5000) {imports.state.value?.floatingSource==FloatingSourceContext(sourceApp,sourceAccount)}
        assertNull(checkNotNull(imports.state.value).captureContinuationId)
        assertFalse(checkNotNull(imports.state.value).canConfirm)
        importClick(R.string.t33_continue_capture)
        rows()
        assertNotNull(checkNotNull(imports.state.value).captureContinuationId)
        assertTrue(checkNotNull(imports.state.value).canConfirm)
    }
    private fun recreateConfirmedReview() {
        val before=checkNotNull(imports.state.value)
        var previous:FloatingCounselActivity?=null
        instrumentation.runOnMainSync {
            previous=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<FloatingCounselActivity>().single()
            checkNotNull(previous).recreate()
        }
        compose.waitUntil(15000) {
            var replaced=false
            instrumentation.runOnMainSync {
                replaced=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<FloatingCounselActivity>().any {it!==previous && !it.isFinishing}
            }
            replaced
        }
        compose.onNodeWithTag("import-preview").performScrollToNode(hasText(text(R.string.t33_source_app)))
        field(R.string.t33_source_app).assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText,AnnotatedString(sourceApp)))
        compose.onNodeWithTag("import-preview").performScrollToNode(hasText(text(R.string.t33_source_account)))
        field(R.string.t33_source_account).assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText,AnnotatedString(sourceAccount)))
        assertEquals(before.id,imports.state.value?.id)
        assertEquals(before.floatingSource,imports.state.value?.floatingSource)
        assertEquals(before.captureContinuationId,imports.state.value?.captureContinuationId)
        assertTrue(checkNotNull(imports.state.value).canConfirm)
    }
    private fun refuseHistoricalFrameAndChooseTemporary(expected:List<String>,forbiddenTexts:List<String>) {
        val previewId=checkNotNull(imports.state.value).id
        importClick(R.string.import_save)
        compose.waitUntil(15000) {imports.state.value?.let {!it.busy && it.error!=null}==true}
        val rejected=checkNotNull(imports.state.value)
        assertEquals(previewId,rejected.id)
        assertEquals("CAPTURE_HISTORY_REVIEW",rejected.error)
        compose.onNodeWithTag("import-preview").performScrollToNode(hasText(text(R.string.t33_capture_history_review)))
        compose.onNodeWithText(text(R.string.t33_capture_history_review)).assertIsDisplayed()
        assertNull(controller.state.value.currentContact)
        assertNull(controller.state.value.currentRequest)
        assertNull(controller.state.value.preview)
        assertNull(controller.state.value.answer)
        assertTrue(controller.state.value.evidence.isEmpty())
        val target=text(R.string.import_target)+"："+fixtureAlias
        compose.onNodeWithTag("import-preview").performScrollToNode(hasText(target))
        compose.onNodeWithText(target).performClick()
        compose.onNodeWithText(text(R.string.temporary_title)).performClick()
        compose.waitUntil(5000) {imports.state.value?.error==null}
        assertNotNull(checkNotNull(imports.state.value).target)
        assertNull(checkNotNull(imports.state.value).target?.contact)
        assertNull(checkNotNull(imports.state.value).captureContinuationId)
        rows()
        assertEquals(expected,checkNotNull(imports.state.value).rows.filterNot {it.excluded}.map {it.text})
        assertTrue(checkNotNull(imports.state.value).canConfirm)
        val handle=saveAndPanel(formal=false)
        assertEquals(expected,controller.state.value.evidence.single().lines.map {it.text})
        assertTrue(controller.state.value.evidence.single().lines.none {it.text in forbiddenTexts})
        assertTrue(controller.state.value.draft.isEmpty())
        demoAnswer(handle.windowId,forbiddenTexts)
    }

    @Test fun formalDuplicateShowsPanelAndSourceClearingRevokesActualMoreContinuation() {
        controller.initialize()
        compose.waitUntil(15000) {!controller.state.value.loading && controller.state.value.conversation!=null}
        assertTrue("This synthetic test requires NoKey Demo",controller.state.value.demo)
        controller.createContact(fixtureAlias,RelationType.FRIEND)
        compose.waitUntil(15000) {controller.state.value.contacts.any {it.alias==fixtureAlias}}
        fixtureContactId=controller.state.value.contacts.single {it.alias==fixtureAlias}.id
        compose.onNodeWithText(text(R.string.floating_start)).performClick()
        SystemProjectionConsent.respond(true)
        compose.waitUntil(15000) {FloatingRuntime.active.value}
        if(InstrumentationRegistry.getArguments().getString("t33AwaitFont260")=="true") {
            instrumentation.sendStatus(0,android.os.Bundle().apply {putString("t33.awaitFont","true")})
            compose.waitUntil(120000) {app.resources.configuration.fontScale>=2.59f}
            compose.waitForIdle()
        }
        ActivityScenario.launch(T33SyntheticCaptureActivity::class.java).use { targetScene ->
            capture();reviewFormal()
            val firstPreview=checkNotNull(imports.state.value).id
            var handle=saveAndPanel()
            val savedSource=controller.state.value.evidence.single()
            T33NativeUi.tap(T33NativeUi.scrollTo(text(com.shengjidaguai.goutoujunshi.designsystem.R.string.ds_close),handle.windowId))
            compose.waitUntil(5000) {T33NativeUi.visible(text(R.string.t33_resize))==null}
            capture();reviewFormal()
            val duplicatePreview=checkNotNull(imports.state.value).id
            assertNotEquals(firstPreview,duplicatePreview)
            handle=saveAndPanel()
            assertEquals("A duplicate must reuse its real saved source, yet open this new panel",savedSource.id,controller.state.value.evidence.single().id)
            assertEquals(savedSource.lines,controller.state.value.evidence.single().lines)
            assertNotEquals("No guessed preview UUID may stand in for the actual source", "import:"+duplicatePreview,controller.state.value.evidence.single().id)
            demoAnswer(handle.windowId)
            // Prepare only the synthetic target through its semantics action:
            // a physical click here can hit the still-open production overlay.
            compose.onNodeWithText(text(R.string.capture_synthetic_earlier))
                .performSemanticsAction(SemanticsActions.OnClick)
            // Production More below still uses a physical click and real Paddle;
            // its recognized rows must contain this distinct E after hiding overlay.
            compose.onNodeWithText(text(R.string.capture_synthetic_prior)).assertExists()
            T33NativeUi.tap(T33NativeUi.scrollTo(text(com.shengjidaguai.goutoujunshi.designsystem.R.string.ds_capture_more),handle.windowId))
            compose.waitUntil(120000) {imports.state.value?.recognized==true}
            assertTrue(checkNotNull(imports.state.value).captureMoreAvailable)
            assertNull(checkNotNull(imports.state.value).target)
            reviewFormal(continuing=true)
            recreateConfirmedReview()
            clearAndReconfirm(R.string.t33_source_app,sourceApp)
            clearAndReconfirm(R.string.t33_source_account,sourceAccount)
            val merged=checkNotNull(imports.state.value).rows.filterNot {it.excluded}.map {it.text}
            assertTrue(merged.any {it.contains("松鼠")})
            assertTrue(merged.containsAll(savedSource.lines.map {it.text}))
            handle=saveAndPanel()
            assertEquals(merged,controller.state.value.evidence.single().lines.map {it.text})
            assertNull(controller.state.value.error)
            val full=controller.state.value.floatingCapture!!
            T33NativeUi.tap(T33NativeUi.scrollTo(text(R.string.t33_side_swap),handle.windowId))
            compose.waitUntil(15000) {controller.state.value.floatingCapture?.id!=full.id && controller.state.value.floatingPreviewReady}
            T33NativeUi.tap(T33NativeUi.scrollTo(text(com.shengjidaguai.goutoujunshi.designsystem.R.string.ds_close),handle.windowId))
            compose.waitUntil(5000) {T33NativeUi.visible(text(R.string.t33_resize))==null}
            // Explicit test argument covers the physical More frame as well as
            // the original A; neither path may be mistaken for the full winner.
            val replayEarlier=InstrumentationRegistry.getArguments().getString("t33ReplayEarlierFrame")=="true"
            if(!replayEarlier) targetScene.recreate()
            capture();reviewFormal()
            val fresh=checkNotNull(imports.state.value).rows.filterNot {it.excluded}.map {it.text}
            if(replayEarlier) {
                assertTrue(fresh.any {it.contains("松鼠")})
                assertTrue(fresh.none {it.contains("三点")})
            } else assertEquals(savedSource.lines.map {it.text},fresh)
            // Large-font OCR can wrap a whole message into short rows; every
            // omitted nonblank row must stay out of the temporary request.
            val omitted=merged.filter {it !in fresh && it.isNotBlank()}
            assertTrue("The new capture must be a proper historical frame, not the full merged record: merged=$merged fresh=$fresh",omitted.isNotEmpty())
            refuseHistoricalFrameAndChooseTemporary(fresh,omitted)
        }
    }
}
