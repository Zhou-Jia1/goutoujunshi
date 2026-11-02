package com.shengjidaguai.goutoujunshi.floating

import android.content.ComponentName
import android.content.ClipboardManager
import android.graphics.Rect
import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.shengjidaguai.goutoujunshi.R
import com.shengjidaguai.goutoujunshi.counsel.CounselRuntime
import com.shengjidaguai.goutoujunshi.counsel.hasCurrentAnswerSource
import org.junit.*
import org.junit.Assert.*

/** One actual NoKey user journey. Root may rerun with system effective fontScale 2.6. */
class T33FloatingJourneyDeviceTest {
    @get:Rule val compose=createAndroidComposeRule<FloatingCounselActivity>()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val app get()=instrumentation.targetContext.applicationContext as android.app.Application
    private fun text(id:Int)=app.getString(id)
    private fun ownTaskCleanup()=instrumentation.runOnMainSync {
        val own=ComponentName(app,T33SyntheticCaptureActivity::class.java)
        app.getSystemService(android.app.ActivityManager::class.java).appTasks.filter { it.taskInfo?.baseIntent?.component==own }.forEach { it.finishAndRemoveTask() }
    }
    @Before fun before() { ownTaskCleanup();T33NativeUi.pump={ compose.mainClock.advanceTimeByFrame();compose.waitForIdle() } }
    @After fun cleanup() {
        SystemProjectionConsent.cancelOwnDialog()
        instrumentation.runOnMainSync { FloatingRuntime.stop(app) }
        ownTaskCleanup();T33NativeUi.pump={}
    }
    private fun importClick(id:Int) {
        compose.onNodeWithTag("import-preview").performScrollToNode(hasText(text(id)))
        compose.onNodeWithText(text(id)).performClick()
    }
    private fun assertPreparedBodyVisible(windowId:Int,actual:String) {
        var bodySeen=false
        for(attempt in 0 until 12) {
            bodySeen=T33NativeUi.nodes().any { it.windowId==windowId && it.text?.toString()==actual && T33NativeUi.touchBounds(it)!=null }
            if(bodySeen) break
            T33NativeUi.swipe(windowId,true)
            SystemClock.sleep(100)
        }
        assertTrue("Real frozen body must be visible before sending",bodySeen)
    }
    private fun reviewCapture(imports:com.shengjidaguai.goutoujunshi.importing.ImportCoordinator,continuing:Boolean=false) {
            if(!imports.state.value!!.rightsAccepted) importClick(R.string.import_rights_accept)
            compose.onNodeWithTag("import-preview").performScrollToNode(hasText(text(R.string.import_target)+"："+text(R.string.import_choose_target)))
            compose.onNodeWithText(text(R.string.import_target)+"："+text(R.string.import_choose_target)).performClick()
            compose.onNodeWithText(text(R.string.temporary_title)).performClick()
            if(continuing) importClick(R.string.t33_continue_capture)
            importClick(R.string.ocr_right_me)
            for(row in imports.state.value!!.rows.filter { !it.excluded && it.speaker==null }) {
                val tag="ocr-speaker-${row.id}"
                compose.onNodeWithTag("import-preview").performScrollToNode(hasTestTag(tag))
                compose.onNode(hasText(text(R.string.import_speaker)+"："+text(R.string.import_unconfirmed)) and hasAnyAncestor(hasTestTag(tag))).performClick()
                compose.onNodeWithText(text(R.string.import_them)).performClick()
            }
            while(imports.state.value!!.rows.any { !it.excluded && (it.ocrConfidence ?: 1f)<.8f && !it.reviewed }) {
                compose.onNodeWithTag("import-preview").performScrollToNode(hasText(text(R.string.ocr_reviewed)))
                compose.onAllNodesWithText(text(R.string.ocr_reviewed)).onFirst().performClick()
            }
            assertTrue(imports.state.value!!.canConfirm)
    }
    @Test fun physicalCaptureReviewFrozenPreviewDemoCopyAndCollapse() {
        val large=InstrumentationRegistry.getArguments().getString("t33Font260")=="true"
        if(large) assertTrue("Root must configure effective system fontScale 2.6 for this variant",app.resources.configuration.fontScale>=2.59f)
        compose.onNodeWithText(text(R.string.floating_start)).performClick()
        SystemProjectionConsent.respond(true)
        compose.waitUntil(15000) { FloatingRuntime.active.value }
        if(InstrumentationRegistry.getArguments().getString("t33AwaitFont260")=="true") {
            instrumentation.sendStatus(0,android.os.Bundle().apply { putString("t33.awaitFont","true") })
            compose.waitUntil(120000) { instrumentation.targetContext.applicationContext.resources.configuration.fontScale>=2.59f }
            compose.waitForIdle()
        }

        ActivityScenario.launch(T33SyntheticCaptureActivity::class.java).use {
            compose.waitUntil(15000) { T33NativeUi.nodes().any { it.text?.toString()==text(R.string.capture_synthetic_title) } }
            instrumentation.waitForIdleSync();SystemClock.sleep(500)
            var ball:android.view.accessibility.AccessibilityNodeInfo?=null
            compose.waitUntil(15000) {
                ball=T33NativeUi.nodes().singleOrNull { it.contentDescription?.toString()==text(R.string.floating_ball_menu) && T33NativeUi.touchBounds(it)!=null }
                ball!=null
            }
            T33NativeUi.tap(checkNotNull(ball)) // Real DOWN/UP; production capture/Paddle owns the rest.
            val imports=CounselRuntime.imports(app)
            val controller=CounselRuntime.get(app)
            compose.waitUntil(120000) { imports.state.value?.recognized==true }
            assertFalse(checkNotNull(imports.state.value).rows.isEmpty())
            reviewCapture(imports)
            val submittedPreviewId=imports.state.value!!.id
            importClick(R.string.import_save)
            try {
                compose.waitUntil(15000) { imports.state.value==null && T33NativeUi.visible(text(R.string.t33_resize))!=null }
            } catch(error:IllegalStateException) {
                val remaining=imports.state.value
                val state=controller.state.value
                instrumentation.sendStatus(0,android.os.Bundle().apply { putString("t33.handoff",
                    "previewPresent=${remaining!=null} busy=${remaining?.busy} error=${remaining?.error} recognized=${remaining?.recognized} sideSet=${remaining?.mySide!=null} targetSet=${remaining?.target!=null} " +
                    "submittedEvidenceMatches=${state.evidence.any { it.id=="import:$submittedPreviewId" }} captureReceipt=${state.floatingCapture!=null} " +
                    "active=${FloatingRuntime.active.value} hook=${FloatingRuntime.showAnswer!=null} ticketMatches=${FloatingRuntime.runtimeTicket==FloatingRuntime.session.epoch()} mainVisible=${!CaptureOwnUi.snapshot().clear} " +
                    "locked=${!state.lockState.canDisplaySensitiveContent} capturePhase=${FloatingRuntime.session.capture.phase}") })
                T33NativeUi.diagnose("import-save-handoff-timeout")
                throw error
            }
            var handle=checkNotNull(T33NativeUi.visible(text(R.string.t33_resize)))
            val bounds=Rect();checkNotNull(handle.window).getBoundsInScreen(bounds)
            assertTrue(bounds.height()<=app.resources.displayMetrics.heightPixels*.55f+1)
            assertTrue(controller.state.value.demo)
            assertTrue(controller.state.value.evidence.isNotEmpty())
            T33NativeUi.tap(T33NativeUi.scrollTo(text(R.string.preview_request),handle.windowId))
            compose.waitUntil(15000) { controller.state.value.preview!=null && controller.state.value.floatingPreviewReady }
            val preview=checkNotNull(controller.state.value.preview)
            assertTrue(preview.references.isNotEmpty())
            assertTrue(preview.knowledgeVersion.isNotBlank())
            assertPreparedBodyVisible(handle.windowId,preview.system+"\n\n"+preview.user)
            T33NativeUi.tap(T33NativeUi.scrollTo(text(R.string.live_send),handle.windowId))
            compose.waitUntil(30000) { controller.state.value.answer!=null && !controller.state.value.streaming }
            assertTrue(controller.state.value.answerDemo)
            assertTrue(controller.state.value.hasCurrentAnswerSource())
            val oldRequest=controller.state.value.answerRequest!!.requestId
            val oldSource=controller.state.value.evidence.single().id
            val oldCapture=controller.state.value.floatingCapture!!
            T33NativeUi.tap(T33NativeUi.scrollTo(text(R.string.t33_side_swap),handle.windowId))
            compose.waitUntil(15000) { controller.state.value.floatingCapture?.id!=oldCapture.id && controller.state.value.floatingPreviewReady }
            val corrected=controller.state.value
            assertNull(corrected.answer);assertNull(corrected.answerRequest);assertNull(corrected.currentRequest)
            assertFalse(corrected.streaming)
            assertEquals(com.shengjidaguai.goutoujunshi.domain.ocr.OcrSide.LEFT,corrected.floatingCapture!!.mySide)
            assertEquals(1,corrected.evidence.size);assertNotEquals(oldSource,corrected.evidence.single().id)
            val newPreview=checkNotNull(corrected.preview)
            assertPreparedBodyVisible(handle.windowId,newPreview.system+"\n\n"+newPreview.user)
            T33NativeUi.tap(T33NativeUi.scrollTo(text(R.string.live_send),handle.windowId))
            compose.waitUntil(30000) { controller.state.value.answer!=null && !controller.state.value.streaming }
            assertTrue(controller.state.value.hasCurrentAnswerSource())
            assertNotEquals(oldRequest,controller.state.value.answerRequest!!.requestId)
            // A second real capture of the controlled screen exercises overlap dedup after an answer.
            // JVM coverage separately verifies earlier/newer chronological order with differing screens.
            val beforeMore=controller.state.value
            val priorLineCount=beforeMore.evidence.single().lines.size
            T33NativeUi.tap(T33NativeUi.scrollTo(app.getString(com.shengjidaguai.goutoujunshi.designsystem.R.string.ds_capture_more),handle.windowId))
            compose.waitUntil(120000) { imports.state.value?.recognized==true }
            assertTrue(imports.state.value!!.captureMoreAvailable)
            assertNull(imports.state.value!!.target)
            assertNull(controller.state.value.currentContact)
            assertNull(controller.state.value.answer);assertNull(controller.state.value.currentRequest)
            val newVisible=imports.state.value!!.rows.count { !it.excluded }
            reviewCapture(imports,true)
            assertNotNull(imports.state.value!!.captureContinuationId)
            val combined=imports.state.value!!.rows.filterNot {it.excluded}.map {it.text}
            assertTrue("Overlapping screen must not duplicate the confirmed source",combined.size<newVisible+priorLineCount)
            val nextSource="import:${imports.state.value!!.id}"
            importClick(R.string.import_save)
            compose.waitUntil(15000) { imports.state.value==null && T33NativeUi.visible(text(R.string.t33_resize))!=null }
            handle=checkNotNull(T33NativeUi.visible(text(R.string.t33_resize)))
            assertEquals(1,controller.state.value.evidence.size)
            assertEquals(nextSource,controller.state.value.evidence.single().id)
            assertEquals(combined,controller.state.value.evidence.single().lines.map {it.text})
            T33NativeUi.tap(T33NativeUi.scrollTo(text(R.string.preview_request),handle.windowId))
            compose.waitUntil(15000) { controller.state.value.floatingPreviewReady }
            val continuedPreview=checkNotNull(controller.state.value.preview)
            assertTrue(continuedPreview.references.isNotEmpty())
            assertPreparedBodyVisible(handle.windowId,continuedPreview.system+"\n\n"+continuedPreview.user)
            T33NativeUi.tap(T33NativeUi.scrollTo(text(R.string.live_send),handle.windowId))
            compose.waitUntil(30000) { controller.state.value.answer!=null && !controller.state.value.streaming }
            assertTrue(controller.state.value.hasCurrentAnswerSource())
            val expected=controller.state.value.answer!!.reply.joinToString("\n")
            assertTrue(expected.isNotBlank())
            T33NativeUi.tap(T33NativeUi.scrollTo(app.getString(com.shengjidaguai.goutoujunshi.designsystem.R.string.ds_copy),handle.windowId))
            var copied:String?=null
            instrumentation.runOnMainSync { copied=app.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString() }
            assertEquals(expected,copied)
            compose.waitUntil(5000) { T33NativeUi.visible(text(R.string.t33_resize))==null }
            assertTrue(FloatingRuntime.active.value)
            assertTrue(T33NativeUi.nodes().any { it.contentDescription?.toString()==text(R.string.floating_ball_menu) && T33NativeUi.touchBounds(it)!=null })
        }
    }
}
