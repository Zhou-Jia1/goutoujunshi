package com.shengjidaguai.goutoujunshi.counsel

import com.shengjidaguai.goutoujunshi.domain.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors

class T33RequestOwnershipTest {
    private suspend fun await(check:()->Boolean)=withTimeout(5000) { while(!check()) yield() }
    @Test fun closeRevokesImmediatelyWhileActorIsSuspendedInStorage()=runBlocking {
        val dispatcher=Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val f=CounselControllerTest.Fixture(scope=CoroutineScope(SupervisorJob()+dispatcher))
        val gate=CompletableDeferred<Unit>()
        try {
            f.persistence.people+=Contact("a","虚构甲")
            f.controller.initialize();await { f.controller.state.value.currentContact?.id=="a" && f.controller.state.value.conversation!=null }
            f.controller.editDraft("虚构问题");await { f.controller.state.value.draft=="虚构问题" }
            f.persistence.beforeMessage=gate
            val entered=CompletableDeferred<Unit>();f.persistence.messageEntered=entered
            val started=CompletableDeferred<AnalysisRequest>()
            val origin=f.controller.state.value.conversation?.id
            f.controller.analyze(origin) { started.complete(it) }
            val request=started.await();withTimeout(5000) { entered.await() }
            f.controller.stopFloating(origin,request)
            assertFalse("close must revoke before the storage actor resumes",f.controller.state.value.streaming)
        } finally { gate.complete(Unit);f.close();dispatcher.close() }
    }
    @Test fun queuedMainInputCannotBeRevokedThroughStalePanelState()=runBlocking {
        val dispatcher=Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val f=CounselControllerTest.Fixture(scope=CoroutineScope(SupervisorJob()+dispatcher))
        val gate=CompletableDeferred<Unit>()
        try {
            f.persistence.people+=Contact("a","虚构甲")
            f.controller.initialize();await { f.controller.state.value.currentContact?.id=="a" && f.controller.state.value.conversation!=null }
            f.controller.editDraft("虚构输入A");await { f.controller.state.value.draft=="虚构输入A" }
            f.controller.preparePreview(f.controller.state.value.conversation?.id,true);await { f.controller.state.value.preview!=null }
            f.persistence.beforeMessage=gate
            val entered=CompletableDeferred<Unit>();f.persistence.messageEntered=entered
            val started=CompletableDeferred<AnalysisRequest>()
            val origin=f.controller.state.value.conversation?.id
            f.controller.analyze(origin) { started.complete(it) }
            val request=started.await();withTimeout(5000) { entered.await() }
            f.controller.editDraft("虚构输入B");f.controller.analyze(origin)
            assertEquals("虚构输入A",f.controller.state.value.draft)
            assertFalse(f.controller.reuseFloatingRequest())
            f.controller.stopFloating(origin,request)
            f.persistence.beforeMessage=null;gate.complete(Unit)
            await { f.provider.calls.any { it.userText=="虚构输入B" } }
            assertEquals("虚构输入B",f.controller.state.value.currentRequest?.userText)
        } finally { gate.complete(Unit);f.close();dispatcher.close() }
    }
    @Test fun stalePanelCannotCancelLaterMainRequestInSameConversation()=runBlocking {
        val f=CounselControllerTest.Fixture()
        try {
            f.controller.initialize();f.controller.editDraft("虚构提问甲")
            f.controller.preparePreview(f.controller.state.value.conversation?.id,true)
            await { f.controller.state.value.preview!=null }
            var first:AnalysisRequest?=null
            f.controller.analyze(f.controller.state.value.conversation?.id) { first=it }
            await { f.provider.calls.size==1 }
            f.controller.editDraft("虚构提问乙");f.controller.analyze()
            await { f.provider.calls.size==2 }
            f.controller.stopFloating(f.controller.state.value.conversation?.id,first)
            assertTrue(f.controller.state.value.streaming)
            assertEquals("虚构提问乙",f.controller.state.value.currentRequest?.userText)
        } finally { f.close() }
    }
    @Test fun closeDuringPreviewRevokesLatePreviewWithoutSending()=runBlocking {
        val f=CounselControllerTest.Fixture()
        try {
            f.controller.initialize();f.controller.editDraft("虚构补充")
            val origin=f.controller.state.value.conversation?.id
            var observed=false
            f.controller.preparePreview(origin,true) { request -> observed=true;f.controller.stopFloating(origin,request) }
            await { observed && f.controller.state.value.preview==null }
            assertTrue(f.provider.calls.isEmpty())
        } finally { f.close() }
    }
    @Test fun currentPanelCancellationRevokesItsOwnLateAnswer()=runBlocking {
        val f=CounselControllerTest.Fixture()
        try {
            f.controller.initialize();f.controller.editDraft("虚构问题")
            var request:AnalysisRequest?=null
            val origin=f.controller.state.value.conversation?.id
            f.controller.analyze(origin) { request=it };await { f.provider.calls.size==1 }
            f.controller.stopFloating(origin,request)
            await { !f.controller.state.value.streaming }
            assertNull(f.controller.state.value.currentRequest)
            assertNull(f.controller.state.value.answer)
        } finally { f.close() }
    }
    @Test fun reuseExpiresAtTenSecondsAndInputEditRevokesIt()=runBlocking {
        var now=0L
        val f=CounselControllerTest.Fixture(clock={now})
        try {
            f.controller.initialize();f.controller.editDraft("虚构问题")
            val origin=f.controller.state.value.conversation?.id
            f.controller.preparePreview(origin,true);await { f.controller.state.value.preview!=null }
            f.controller.analyze(origin);await { f.provider.calls.size==1 }
            now=9999;assertTrue(f.controller.reuseFloatingRequest())
            now=10001;assertFalse(f.controller.reuseFloatingRequest())
            now=1;f.controller.editDraft("新的输入");assertFalse(f.controller.reuseFloatingRequest())
        } finally { f.close() }
    }
    @Test fun rapidClicksReuseRunningRequestAndFloatingNeverAddsSummary()=runBlocking {
        val f=CounselControllerTest.Fixture()
        try {
            f.controller.initialize();f.controller.editDraft("虚构问题")
            val origin=f.controller.state.value.conversation?.id
            f.controller.preparePreview(origin,true);await { f.controller.state.value.preview!=null }
            repeat(10) { f.controller.analyze(origin) }
            await { f.provider.calls.size==1 }
            assertEquals(1,f.provider.calls.size)
            assertTrue(f.provider.calls.single().floating)
            assertEquals(OutboundPurpose.ANALYSIS,f.provider.calls.single().purpose)
        } finally { f.close() }
    }
}
