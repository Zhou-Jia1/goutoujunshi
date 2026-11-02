package com.shengjidaguai.goutoujunshi.counsel

import com.shengjidaguai.goutoujunshi.domain.*
import com.shengjidaguai.goutoujunshi.domain.ocr.*
import com.shengjidaguai.goutoujunshi.importing.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class T33UnknownCaptureTest {
    private suspend fun await(check:()->Boolean)=withTimeout(5000) {while(!check()) yield()}
    private fun frame(vararg text:String)=text.mapIndexed { i,t -> OcrLine(t,.99f,.4f,.2f+i*.1f,.6f,.3f+i*.1f,suggestedSide=OcrSide.UNKNOWN) }
    private fun review(imports:ImportCoordinator,id:String) {
        imports.side(id,OcrSide.RIGHT)
        for(row in imports.state.value!!.rows.filter { !it.excluded && it.speaker==null }) imports.editRow(id,row.id) {it.copy(speaker=Speaker.THEM,reviewed=true)}
    }
    private suspend fun exercise(formal:Boolean) {
        val f=CounselControllerTest.Fixture()
        try {
            if(formal) f.persistence.people+=Contact("a","合成甲")
            val c=f.controller;val imports=ImportCoordinator(c,f.scope,{true},{})
            val first=imports.beginCapture(33) {true}!!
            imports.captured(first,frame("重叠原话","较新原话"))
            imports.target(if(formal) ImportTarget(c.state.value.contacts.first {it.id=="a"}) else ImportTarget())
            review(imports,first);assertTrue(imports.state.value!!.canConfirm)
            imports.confirm();await {imports.state.value==null}
            assertNotNull("Manual speaker confirmation is still an authoritative captured source",c.state.value.floatingCapture)
            val receipt=c.state.value.floatingCapture!!
            assertFalse(receipt.canSwapPhysicalSide)
            val source=c.state.value.evidence
            c.preparePreview(c.state.value.conversation?.id,true);await {c.state.value.floatingPreviewReady}
            c.analyzeFloating(c.state.value.conversation?.id,c.state.value.floatingPreviewRequest);await {f.provider.calls.size==1}
            val running=c.state.value.currentRequest
            c.switchFloatingSide(c.state.value.conversation?.id,receipt.id)
            assertEquals(source,c.state.value.evidence);assertEquals(running,c.state.value.currentRequest)
            assertTrue(c.state.value.streaming);assertEquals(1,f.provider.calls.size)
            c.editFloatingDraft(c.state.value.conversation?.id,receipt.id,"逐行确认后的补充")
            await {c.state.value.draft=="逐行确认后的补充" && c.state.value.floatingCapture!=null}
            val supplemented=c.state.value
            assertEquals(source,supplemented.evidence)
            assertTrue(imports.prepareCaptureMore(supplemented.conversation?.id,supplemented.floatingCapture!!.id,33))
            val next=imports.beginCapture(33) {true}!!
            imports.captured(next,frame("更早原话","重叠原话"))
            imports.target(if(formal) ImportTarget(c.state.value.contacts.first {it.id=="a"}) else ImportTarget())
            imports.continueCaptureMore();review(imports,next);assertTrue(imports.state.value!!.canConfirm)
            imports.confirm();await {imports.state.value==null}
            assertEquals(listOf("更早原话","重叠原话","较新原话"),c.state.value.evidence.single().lines.map {it.text})
            assertTrue(c.state.value.evidence.single().lines.all {it.speaker==Speaker.THEM && it.speakerConfirmed})
            assertEquals("逐行确认后的补充",c.state.value.draft)
            assertNotNull(c.state.value.floatingCapture);assertFalse(c.state.value.floatingCapture!!.canSwapPhysicalSide)
            assertEquals(1,f.provider.calls.size)
            if(formal) {assertEquals(1,f.persistence.imports.size);assertEquals(1,f.persistence.capturedReplaceIds.size)}
            val old=c.state.value
            c.editDraft("Main B");c.analyze();await {f.provider.calls.size==2}
            c.editFloatingDraft(old.conversation?.id,old.floatingCapture!!.id,"旧补充")
            c.switchFloatingSide(old.conversation?.id,old.floatingCapture!!.id)
            assertNull(c.prepareFloatingMore(old.conversation?.id,old.floatingCapture!!.id,33))
            assertEquals("Main B",c.state.value.currentRequest?.userText);assertTrue(c.state.value.streaming)
        } finally {f.close()}
    }
    @Test fun allUnknownTemporaryKeepsSupplementAndExplicitMoreSource()=runBlocking {exercise(false)}
    @Test fun allUnknownFormalKeepsSupplementAndAtomicMoreSource()=runBlocking {exercise(true)}
}
