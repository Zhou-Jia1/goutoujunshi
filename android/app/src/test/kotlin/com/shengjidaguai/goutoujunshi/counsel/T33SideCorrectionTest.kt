package com.shengjidaguai.goutoujunshi.counsel

import com.shengjidaguai.goutoujunshi.domain.*
import com.shengjidaguai.goutoujunshi.domain.ocr.*
import com.shengjidaguai.goutoujunshi.importing.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors

class T33SideCorrectionTest {
    private suspend fun await(check:()->Boolean)=withTimeout(5000) { while(!check()) yield() }
    private suspend fun capture(f:CounselControllerTest.Fixture,formal:Boolean=false):ImportCoordinator {
        val imports=ImportCoordinator(f.controller,f.scope,{true},{})
        val id=imports.beginCapture(33) {true}!!
        imports.captured(id,listOf(OcrLine("左边原话",.99f,.1f,.3f,.4f,.4f),OcrLine("右边原话",.99f,.6f,.5f,.9f,.6f)))
        imports.side(id,OcrSide.RIGHT)
        imports.target(if(formal) ImportTarget(contact=f.controller.state.value.contacts.first { it.id=="a" }) else ImportTarget())
        imports.confirm();await { imports.state.value==null && f.controller.state.value.floatingCapture!=null }
        return imports
    }
    @Test fun oneTapRebuildsTemporarySourceAndPreviewWithZeroExtraModelCalls()=runBlocking {
        val f=CounselControllerTest.Fixture()
        try {
            capture(f)
            val original=f.controller.state.value
            f.controller.preparePreview(original.conversation?.id,true);await { f.controller.state.value.preview!=null }
            f.controller.analyze(original.conversation?.id);await { f.provider.calls.size==1 }
            val receipt=f.controller.state.value.floatingCapture!!
            f.controller.switchFloatingSide(original.conversation?.id,receipt.id)
            f.controller.switchFloatingSide(original.conversation?.id,receipt.id)
            await { f.controller.state.value.preview!=null && f.controller.state.value.floatingCapture?.id!=receipt.id }
            val corrected=f.controller.state.value
            assertNull(corrected.answer);assertNull(corrected.currentRequest)
            assertEquals(1,f.provider.calls.size)
            assertEquals(OcrSide.LEFT,corrected.floatingCapture!!.mySide)
            assertEquals(1,corrected.evidence.size)
            assertNotEquals(original.evidence.single().id,corrected.evidence.single().id)
            assertEquals(Speaker.ME,corrected.evidence.flatMap { it.lines }.first { it.text=="左边原话" }.speaker)
            assertEquals(Speaker.THEM,corrected.evidence.flatMap { it.lines }.first { it.text=="右边原话" }.speaker)
            assertTrue(f.persistence.imports.isEmpty())
            f.controller.analyze(corrected.conversation?.id);await { f.provider.calls.size==2 }
            assertTrue(f.provider.calls.last().floating)
        } finally { f.close() }
    }
    @Test fun closeAndLockRevokeReceiptWithoutTouchingLaterMainInput()=runBlocking {
        val f=CounselControllerTest.Fixture()
        try {
            capture(f);val before=f.controller.state.value;val receipt=before.floatingCapture!!
            f.controller.closeFloatingCapture(33)
            f.controller.editDraft("Main新输入")
            f.controller.switchFloatingSide(before.conversation?.id,receipt.id)
            assertEquals("Main新输入",f.controller.state.value.draft)
            assertEquals(before.evidence,f.controller.state.value.evidence)
            assertNull(f.controller.state.value.floatingCapture)
        } finally { f.close() }
    }
    @Test fun queuedMainInputWithStaleDisplayedCaptureCannotBeOverwrittenBySideClick()=runBlocking {
        val dispatcher=Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val f=CounselControllerTest.Fixture(scope=CoroutineScope(SupervisorJob()+dispatcher))
        val gate=CompletableDeferred<Unit>()
        try {
            f.persistence.people+=Contact("a","虚构甲")
            capture(f,true)
            val original=f.controller.state.value;val receipt=original.floatingCapture!!
            f.controller.preparePreview(original.conversation?.id,true);await { f.controller.state.value.preview!=null }
            f.persistence.beforeMessage=gate
            val entered=CompletableDeferred<Unit>();f.persistence.messageEntered=entered
            f.controller.analyze(original.conversation?.id);withTimeout(5000) { entered.await() }
            f.controller.editDraft("Main B")
            assertEquals(original.draft,f.controller.state.value.draft)
            f.controller.switchFloatingSide(original.conversation?.id,receipt.id)
            f.persistence.beforeMessage=null;gate.complete(Unit)
            await { f.controller.state.value.draft=="Main B" }
            assertEquals(original.evidence,f.controller.state.value.evidence)
            assertNull(f.controller.state.value.floatingCapture)
            assertTrue(f.provider.calls.isEmpty())
        } finally { gate.complete(Unit);f.close();dispatcher.close() }
    }
    @Test fun formalReplacementRetryReusesTheCommittedOperationAndBlocksSendingWhileFailed()=runBlocking {
        val f=CounselControllerTest.Fixture()
        try {
            f.persistence.people+=Contact("a","虚构甲");capture(f,true)
            val before=f.controller.state.value;val receipt=before.floatingCapture!!
            f.persistence.capturedAfterCommitFailures=1
            f.controller.switchFloatingSide(before.conversation?.id,receipt.id)
            await { f.controller.state.value.floatingCapture?.failed==true }
            assertNull(f.controller.state.value.answer)
            f.controller.analyze(before.conversation?.id);assertTrue(f.provider.calls.isEmpty())
            f.controller.switchFloatingSide(before.conversation?.id,receipt.id)
            await { f.controller.state.value.preview!=null && f.controller.state.value.floatingCapture?.id!=receipt.id }
            assertEquals(1,f.persistence.capturedReplaceIds.distinct().size)
            assertEquals(1,f.persistence.imports.size)
            assertEquals(before.currentContact!!.sourceRevision+1,f.controller.state.value.currentContact!!.sourceRevision)
            assertEquals(1,f.controller.state.value.evidence.size)
            assertTrue(f.provider.calls.isEmpty())
        } finally { f.close() }
    }
    @Test fun closingPendingFormalReplacementCannotChangeLaterMainInput()=runBlocking {
        val dispatcher=Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val f=CounselControllerTest.Fixture(scope=CoroutineScope(SupervisorJob()+dispatcher))
        val gate=CompletableDeferred<Unit>()
        try {
            f.persistence.people+=Contact("a","虚构甲");capture(f,true)
            val before=f.controller.state.value;val receipt=before.floatingCapture!!
            val entered=CompletableDeferred<Unit>();f.persistence.capturedReplaceGate=gate;f.persistence.capturedReplaceEntered=entered
            f.controller.switchFloatingSide(before.conversation?.id,receipt.id);withTimeout(5000) { entered.await() }
            f.controller.closeFloatingCapture(33)
            f.controller.editDraft("后来的Main B")
            gate.complete(Unit);await { f.controller.state.value.draft=="后来的Main B" }
            assertEquals(before.evidence,f.controller.state.value.evidence)
            assertEquals(before.currentContact!!.sourceRevision,f.controller.state.value.currentContact!!.sourceRevision)
            assertTrue(f.provider.calls.isEmpty())
        } finally { gate.complete(Unit);f.close();dispatcher.close() }
    }
    @Test fun groupSwapKeepsUnknownManualRowsAndSeparatesNamedParticipants() {
        val rows=listOf(ImportRow("甲","left",speaker=Speaker.OTHER,side=OcrSide.LEFT),ImportRow("乙","right",speaker=Speaker.ME,side=OcrSide.RIGHT),ImportRow("丙","unknown",speaker=Speaker.OTHER,side=OcrSide.UNKNOWN))
        val switched=FloatingCaptureRows.swap(rows,OcrSide.LEFT)
        assertEquals(listOf(Speaker.ME,Speaker.OTHER,Speaker.OTHER),switched.map { it.speaker })
    }
}
