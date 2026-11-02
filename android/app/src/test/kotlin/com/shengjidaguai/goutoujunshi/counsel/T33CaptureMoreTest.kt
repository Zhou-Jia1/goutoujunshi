package com.shengjidaguai.goutoujunshi.counsel

import com.shengjidaguai.goutoujunshi.domain.*
import com.shengjidaguai.goutoujunshi.domain.ocr.*
import com.shengjidaguai.goutoujunshi.importing.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class T33CaptureMoreTest {
    private suspend fun await(check:()->Boolean)=withTimeout(5000) { while(!check()) yield() }
    private fun frame(vararg text:String)=text.mapIndexed { i,t -> OcrLine(t,.99f,.1f,.2f+i*.15f,.4f,.3f+i*.15f) }
    private suspend fun first(f:CounselControllerTest.Fixture,formal:Boolean):ImportCoordinator {
        val imports=ImportCoordinator(f.controller,f.scope,{true},{})
        val id=imports.beginCapture(33) {true}!!
        imports.captured(id,frame("重叠原话","较新原话"));imports.side(id,OcrSide.RIGHT)
        imports.target(if(formal) ImportTarget(f.controller.state.value.contacts.first {it.id=="a"}) else ImportTarget())
        imports.confirm();await { imports.state.value==null && f.controller.state.value.floatingCapture!=null }
        return imports
    }
    private suspend fun next(f:CounselControllerTest.Fixture,imports:ImportCoordinator,formal:Boolean) {
        val before=f.controller.state.value
        assertTrue(imports.prepareCaptureMore(before.conversation?.id,before.floatingCapture!!.id,33))
        val id=imports.beginCapture(33) {true}!!
        imports.captured(id,frame("更早原话","重叠原话"))
        assertNull(imports.state.value!!.target)
        assertEquals(listOf("更早原话","重叠原话"),imports.state.value!!.rows.map {it.text})
        imports.target(if(formal) ImportTarget(f.controller.state.value.contacts.first {it.id=="a"}) else ImportTarget())
        imports.continueCaptureMore()
        imports.side(id,OcrSide.RIGHT);imports.acceptRights()
        assertTrue(imports.state.value!!.canConfirm)
        imports.confirm();await { imports.state.value==null || imports.state.value!!.error!=null }
        assertNull(imports.state.value)
    }
    @Test fun explicitMoreKeepsOneMergedTemporaryImportAndDeduplicatesOverlap()=runBlocking {
        val f=CounselControllerTest.Fixture()
        try {
            val imports=first(f,false)
            val before=f.controller.state.value
            f.controller.editFloatingDraft(before.conversation?.id,before.floatingCapture!!.id,"补充的合成问题")
            assertNotNull("Completing supplement must keep its capture receipt",f.controller.state.value.floatingCapture)
            next(f,imports,false)
            assertEquals("补充的合成问题",f.controller.state.value.draft)
            assertEquals(listOf("更早原话","重叠原话","较新原话"),f.controller.state.value.evidence.single().lines.map {it.text})
            assertTrue(f.provider.calls.isEmpty());assertTrue(f.persistence.imports.isEmpty())
        } finally {f.close()}
    }
    @Test fun explicitMoreReplacesFormalImportInsteadOfAppendingOverlappingSources()=runBlocking {
        val f=CounselControllerTest.Fixture()
        try {
            f.persistence.people+=Contact("a","合成甲");val imports=first(f,true)
            val old=f.controller.state.value.evidence.single().id
            next(f,imports,true)
            assertEquals(1,f.persistence.imports.size);assertFalse(f.persistence.imports.containsKey(old))
            assertEquals(1,f.persistence.capturedReplaceIds.size)
            assertEquals(listOf("更早原话","重叠原话","较新原话"),f.controller.state.value.evidence.single().lines.map {it.text})
            assertTrue(f.provider.calls.isEmpty())
        } finally {f.close()}
    }
    @Test fun choosingTemporaryAgainDoesNotAutomaticallyProveSameConversation()=runBlocking {
        val f=CounselControllerTest.Fixture()
        try {
            val imports=first(f,false);val c=f.controller
            c.state.value.let { c.editFloatingDraft(it.conversation?.id,it.floatingCapture!!.id,"旧临时补充") }
            val before=c.state.value
            assertTrue(imports.prepareCaptureMore(before.conversation?.id,before.floatingCapture!!.id,33))
            val id=imports.beginCapture(33) {true}!!;imports.captured(id,frame("全新临时聊天"))
            imports.target(ImportTarget());imports.side(id,OcrSide.RIGHT)
            assertNull(imports.state.value!!.captureContinuationId)
            imports.confirm();await { imports.state.value==null }
            assertEquals(listOf("全新临时聊天"),c.state.value.evidence.single().lines.map {it.text});assertEquals("",c.state.value.draft)
        } finally {f.close()}
    }
    @Test fun differentOwnerAndSourceRemoveAllPriorPrivateRowsBeforeConfirmation()=runBlocking {
        val f=CounselControllerTest.Fixture()
        try {
            f.persistence.people+=Contact("a","合成甲");f.persistence.people+=Contact("b","合成乙")
            val imports=first(f,true);val c=f.controller;val before=c.state.value
            assertTrue(imports.prepareCaptureMore(before.conversation?.id,before.floatingCapture!!.id,33))
            val id=imports.beginCapture(33) {true}!!;imports.captured(id,frame("全新消息"))
            imports.target(ImportTarget(c.state.value.contacts.first {it.id=="a"}));imports.continueCaptureMore()
            assertTrue(imports.state.value!!.rows.any {it.text=="较新原话"})
            imports.floatingSource(FloatingSourceContext("另一应用","另一账号"))
            assertFalse(imports.state.value!!.captureMoreEligible);assertNull(imports.state.value!!.captureContinuationId)
            assertEquals(listOf("全新消息"),imports.state.value!!.rows.map {it.text})
            imports.floatingSource(null);imports.target(ImportTarget(c.state.value.contacts.first {it.id=="b"}))
            imports.continueCaptureMore();assertNull(imports.state.value!!.captureContinuationId)
            imports.side(id,OcrSide.RIGHT);imports.confirm();await {imports.state.value==null}
            c.preparePreview(c.state.value.conversation?.id,true);await {c.state.value.preview!=null}
            c.analyze();await {f.provider.calls.size==1}
            assertEquals(listOf("全新消息"),f.provider.calls.single().evidence.flatMap {it.lines}.map {it.text})
            assertEquals(2,f.persistence.imports.size) // A retained; B owns only its new source.
        } finally {f.close()}
    }
    @Test fun formalAcknowledgementLossRetriesOneIdenticalReplacement()=runBlocking {
        val f=CounselControllerTest.Fixture()
        try {
            f.persistence.people+=Contact("a","合成甲");val imports=first(f,true);val c=f.controller;val before=c.state.value
            assertTrue(imports.prepareCaptureMore(before.conversation?.id,before.floatingCapture!!.id,33))
            val id=imports.beginCapture(33) {true}!!;imports.captured(id,frame("更早原话","重叠原话"))
            imports.target(ImportTarget(c.state.value.contacts.first {it.id=="a"}));imports.continueCaptureMore();imports.side(id,OcrSide.RIGHT)
            f.persistence.capturedAfterCommitFailures=1;imports.confirm();await {imports.state.value?.error=="IMPORT_SAVE"}
            assertTrue(f.provider.calls.isEmpty());imports.retry();await {imports.state.value==null}
            assertEquals(1,f.persistence.imports.size);assertEquals(1,f.persistence.capturedReplaceIds.distinct().size)
            assertEquals(listOf("更早原话","重叠原话","较新原话"),c.state.value.evidence.single().lines.map {it.text})
        } finally {f.close()}
    }
    @Test fun stopLockAndLaterMainInputRevokeExplicitLeaseWithoutAffectingNewRequest()=runBlocking {
        for(revoke in listOf("stop","lock","main")) {
            val f=CounselControllerTest.Fixture()
            try {
                first(f,false);val c=f.controller;val old=c.state.value
                val lease=c.prepareFloatingMore(old.conversation?.id,old.floatingCapture!!.id,33)!!
                assertNull(c.state.value.answer);assertNull(c.state.value.currentRequest)
                when(revoke) { "stop" -> c.closeFloatingCapture(33);"lock" -> c.updateLockState(LockState(true,false));else -> c.editDraft("Main B") }
                assertNull(c.captureMore(lease.id))
                if(revoke!="lock") {
                    c.editDraft("Main B");c.analyze();await {f.provider.calls.size==1}
                    c.dropFloatingMore(lease.id);c.closeFloatingCapture(33)
                    assertEquals("Main B",c.state.value.currentRequest?.userText);assertTrue(c.state.value.streaming)
                }
            } finally {f.close()}
        }
    }

    @Test fun sameFormalOwnerWithoutContinuationOrWithDifferentSourceCannotReloadOldImport()=runBlocking {
        val f=CounselControllerTest.Fixture()
        try {
            f.persistence.people+=Contact("a","合成甲");val imports=first(f,true);val c=f.controller;val before=c.state.value
            assertTrue(imports.prepareCaptureMore(before.conversation?.id,before.floatingCapture!!.id,33))
            val id=imports.beginCapture(33) {true}!!;imports.captured(id,frame("另一来源新帧"))
            imports.target(ImportTarget(c.state.value.contacts.first {it.id=="a"}));imports.side(id,OcrSide.RIGHT)
            assertFalse("Same formal owner must explicitly continue instead of reloading old source through append",imports.state.value!!.canConfirm)
            imports.floatingSource(FloatingSourceContext("另一应用","另一账号"));imports.continueCaptureMore();imports.side(id,OcrSide.RIGHT)
            assertFalse("Conflicting source must choose temporary/new owner instead of silently loading old import",imports.state.value!!.canConfirm)
            imports.target(ImportTarget());imports.side(id,OcrSide.RIGHT)
            assertTrue(imports.state.value!!.canConfirm);imports.confirm();await {imports.state.value==null}
            assertEquals(listOf("另一来源新帧"),c.state.value.evidence.single().lines.map {it.text})
            assertEquals(1,f.persistence.imports.size)
        } finally {f.close()}
    }

}
