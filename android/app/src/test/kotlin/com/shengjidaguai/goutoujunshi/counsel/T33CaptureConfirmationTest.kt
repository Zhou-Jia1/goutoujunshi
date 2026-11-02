package com.shengjidaguai.goutoujunshi.counsel

import com.shengjidaguai.goutoujunshi.domain.*
import com.shengjidaguai.goutoujunshi.domain.ocr.*
import com.shengjidaguai.goutoujunshi.importing.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class T33CaptureConfirmationTest {
    private val receivers=mutableMapOf<String,String>()
    private suspend fun await(check:()->Boolean)=withTimeout(5000) {while(!check()) yield()}
    private suspend fun capture(f:CounselControllerTest.Fixture,imports:ImportCoordinator,text:String):String {
        val id=imports.beginCapture(33) {true}!!
        imports.captured(id,listOf(OcrLine(text,.99f,.1f,.2f,.4f,.3f)))
        imports.target(ImportTarget(f.controller.state.value.contacts.first {it.id=="a"}));imports.side(id,OcrSide.RIGHT)
        receivers[id]=requireNotNull(f.controller.registerCaptureReceiver(id,33))
        imports.confirm();await {imports.state.value==null && f.controller.state.value.floatingCapture!=null}
        return id
    }
    @Test fun duplicateFormalConfirmationOpensActualDeduplicatedSourceInsteadOfExpectingNewId()=runBlocking {
        val f=CounselControllerTest.Fixture()
        try {
            f.persistence.people+=Contact("a","合成甲");val imports=ImportCoordinator(f.controller,f.scope,{true},{})
            val one=capture(f,imports,"合成重复内容")
            assertTrue(f.controller.consumeCaptureConfirmation(one,33,receivers[one]))
            val savedId=f.controller.state.value.evidence.single().id
            val two=capture(f,imports,"合成重复内容")
            assertEquals(1,f.persistence.imports.size)
            assertEquals(savedId,f.controller.state.value.evidence.single().id)
            assertTrue("A successful duplicate confirmation must acknowledge its actual saved ID",f.controller.consumeCaptureConfirmation(two,33,receivers[two]))
        } finally {f.close()}
    }
    @Test fun successfulConfirmationIsOneShotAndClosingItsActivityDiscardsIt()=runBlocking {
        val f=CounselControllerTest.Fixture()
        try {
            f.persistence.people+=Contact("a","合成甲");val imports=ImportCoordinator(f.controller,f.scope,{true},{})
            val one=capture(f,imports,"一次确认")
            assertTrue(f.controller.consumeCaptureConfirmation(one,33,receivers[one]))
            assertFalse("The same completion cannot reopen a panel",f.controller.consumeCaptureConfirmation(one,33,receivers[one]))
            val two=capture(f,imports,"关闭确认页")
            f.controller.unregisterCaptureReceiver(requireNotNull(receivers[two]))
            assertFalse(f.controller.consumeCaptureConfirmation(two,33,receivers[two]))
        } finally {f.close()}
    }
    @Test fun olderCompletionCannotUseNewReceiptOrChangedOwnerSideOrMainInput()=runBlocking {
        for(change in listOf("new-capture","side","close","main","owner")) {
            val f=CounselControllerTest.Fixture()
            try {
                f.persistence.people+=Contact("a","合成甲");f.persistence.people+=Contact("b","合成乙")
                val c=f.controller;val imports=ImportCoordinator(c,f.scope,{true},{})
                val one=capture(f,imports,"第一段")
                val before=c.state.value
                when(change) {
                    "new-capture" -> capture(f,imports,"新一段")
                    "side" -> {c.switchFloatingSide(before.conversation?.id,before.floatingCapture!!.id);await {c.state.value.floatingCapture?.id!=before.floatingCapture.id}}
                    "close" -> c.closeFloatingCapture(33)
                    "main" -> c.editDraft("Main B")
                    else -> {c.selectContact("b");await {c.state.value.currentContact?.id=="b"}}
                }
                assertFalse("Stale completion after $change",c.consumeCaptureConfirmation(one,33,receivers[one]))
                if(change=="main") {c.analyze();await {f.provider.calls.size==1};assertEquals("Main B",c.state.value.currentRequest?.userText)}
            } finally {f.close()}
        }
    }
}
