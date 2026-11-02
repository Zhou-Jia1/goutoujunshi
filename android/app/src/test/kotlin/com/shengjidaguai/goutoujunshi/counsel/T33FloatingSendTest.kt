package com.shengjidaguai.goutoujunshi.counsel

import com.shengjidaguai.goutoujunshi.domain.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class T33FloatingSendTest {
    private suspend fun await(check:()->Boolean)=withTimeout(5000) { while(!check()) yield() }
    @Test fun noFloatingPreviewCannotFallBackToUnpreviewedDemo()=runBlocking {
        val f=CounselControllerTest.Fixture()
        try {
            val c=f.controller;c.initialize();c.editDraft("合成问题")
            var started=false;c.analyzeFloating(c.state.value.conversation?.id,null) {started=true}
            assertFalse("A missing frozen floating body must never authorize Demo",started)
            assertNull(c.state.value.currentRequest);assertTrue(f.provider.calls.isEmpty())
        } finally {f.close()}
    }
    @Test fun mainPreviewCannotBeConsumedByFloatingSend()=runBlocking {
        val f=CounselControllerTest.Fixture()
        try {
            val c=f.controller;c.initialize();c.editDraft("Main问题")
            var main:AnalysisRequest?=null;c.preparePreview(c.state.value.conversation?.id,false) {main=it};await {main!=null}
            val before=c.state.value
            var started=false;c.analyzeFloating(before.conversation?.id,main) {started=true}
            assertFalse(started);assertFalse(c.state.value.streaming)
            assertEquals(before.preview,c.state.value.preview);assertNull(c.state.value.error)
        } finally {f.close()}
    }
    @Test fun consumedFloatingBodyCannotResendDemoAfterCompletion()=runBlocking {
        val f=CounselControllerTest.Fixture()
        try {
            val c=f.controller;c.initialize();c.editDraft("合成问题")
            var body:AnalysisRequest?=null;c.preparePreview(c.state.value.conversation?.id,true) {body=it};await {body!=null}
            repeat(10) {c.analyzeFloating(c.state.value.conversation?.id,body)}
            await {f.provider.calls.size==1}
            f.provider.outputs[0].send(AnalysisChunk.Completed(StructuredAnswer(id="answer-a",reply=listOf("合成回复"))))
            await {c.state.value.answer!=null}
            var started=false;c.analyzeFloating(c.state.value.conversation?.id,body) {started=true}
            assertFalse("A consumed body is not a new Demo authorization",started)
            assertEquals("answer-a",c.state.value.answer?.id);assertEquals(1,f.provider.calls.size)
        } finally {f.close()}
    }
    @Test fun stalePanelCannotConsumeOrChangeLaterMainPreviewInSameConversation()=runBlocking {
        val f=CounselControllerTest.Fixture()
        try {
            val c=f.controller;c.initialize();c.editDraft("浮窗A")
            var a:AnalysisRequest?=null;c.preparePreview(c.state.value.conversation?.id,true) {a=it};await {a!=null}
            c.editDraft("Main B");c.preparePreview();await {c.state.value.preview!=null}
            val before=c.state.value
            var started=false;c.analyzeFloating(before.conversation?.id,a) {started=true}
            assertFalse(started);assertFalse(c.state.value.streaming)
            assertEquals(before.preview,c.state.value.preview);assertEquals(before.outboundBody,c.state.value.outboundBody)
            assertEquals("Main B",c.state.value.draft);assertNull(c.state.value.error)
            assertTrue(f.provider.calls.isEmpty())
        } finally {f.close()}
    }
    @Test fun oldRenderedButtonCannotSendNewFloatingBodyItDidNotDisplay()=runBlocking {
        val f=CounselControllerTest.Fixture()
        try {
            val c=f.controller;c.initialize();c.editDraft("浮窗A")
            c.preparePreview(c.state.value.conversation?.id,true);await {c.state.value.floatingPreviewRequest!=null}
            val renderedA=c.state.value.floatingPreviewRequest
            val oldButton={c.analyzeFloating(c.state.value.conversation?.id,renderedA)}
            c.editDraft("浮窗B");c.preparePreview(c.state.value.conversation?.id,true);await {c.state.value.floatingPreviewRequest?.userText=="浮窗B"}
            val before=c.state.value
            oldButton()
            assertFalse(c.state.value.streaming);assertEquals(before.preview,c.state.value.preview)
            assertEquals(before.floatingPreviewRequest,c.state.value.floatingPreviewRequest);assertTrue(f.provider.calls.isEmpty())
            c.analyzeFloating(before.conversation?.id,before.floatingPreviewRequest);await {f.provider.calls.size==1}
            assertEquals("浮窗B",f.provider.calls.single().userText)
        } finally {f.close()}
    }

}
