package com.shengjidaguai.goutoujunshi.counsel

import com.shengjidaguai.goutoujunshi.domain.*
import com.shengjidaguai.goutoujunshi.data.*
import com.shengjidaguai.goutoujunshi.knowledge.CachedLocalSafetyClassifier
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class T53LoadSafetyScopeTest {
    @Test fun localCrisisRestorationDoesNotSendAResumableLegacyRequest()=runBlocking {
        val request=AnalysisRequest(requestId="legacy",conversationId="conversation:a",contactId="a",userText="合成危机",
            actualDataContext=MemoryPresentation.context("a",MemorySnapshot()))
        val begun=AnalysisLedger().begin(request,"user:legacy")
        val p=CounselControllerTest.MemoryPersistence().apply {
            people+=Contact("a","合成甲")
            saved["a"]=StoredCounsel(Conversation("conversation:a","a",coolingDraft=CoolingDraft("kept",1000)),request.userText,
                begun.messages,runs=begun.runs.map {it.copy(requestSnapshot=request)})
        }
        val f=CounselControllerTest.Fixture(p,safetyClassifier={if(it.contains("合成危机"))SafetyLevel.CRISIS else SafetyLevel.NONE})
        try {
            f.controller.initialize();assertEquals(SafetyLevel.CRISIS,f.controller.state.value.safety)
            assertEquals(request.requestId,f.controller.state.value.currentRequest?.requestId)
            assertNull(f.controller.state.value.visibleCoolingDraft())
            val checkpoint=p.saved.getValue("a").runs
            for((name,action) in listOf("retry" to f.controller::retry,"preview" to {f.controller.preparePreview()},"analyze" to {f.controller.analyze()})) {
                action()
                val before=f.controller.state.value.tipsReceipt;f.controller.openTips()
                withTimeout(5000) {while(f.controller.state.value.tipsReceipt==before) yield()}
                assertEquals("local crisis $name provider calls=${f.provider.calls.map {it.requestId}}",0,f.provider.calls.size)
            }
            assertEquals("kept",p.saved.getValue("a").conversation.coolingDraft?.text)
            assertEquals(checkpoint,p.saved.getValue("a").runs)
        } finally {f.close()}
    }
    @Test fun targetThemEvidenceIsClassifiedAndCleanOwnerDoesNotReadItAgain() {
        val assets=java.io.File(System.getProperty("junshi.repositoryRoot"),"android/core/knowledge/src/main/assets")
        var crisisReads=0;var calmReads=0
        val classifier=CachedLocalSafetyClassifier({crisisReads++;assets.resolve("crisis-keywords.txt").readText()},{calmReads++;assets.resolve("calm-keywords.txt").readText()})
        val p=CounselControllerTest.MemoryPersistence().apply {
            people+=Contact("a","合成甲");people+=Contact("b","合成乙")
            saved["a"]=StoredCounsel(Conversation("conversation:a","a",coolingDraft=CoolingDraft("合成旧草稿",1000)),"合成普通输入",emptyList(),
                listOf(EvidenceBlock("import:a","PASTE",listOf(ChatLine("line:a","import:a",1,Speaker.THEM,"我想自杀",speakerConfirmed=true,contactId="a")))))
        }
        val f=CounselControllerTest.Fixture(p,safetyClassifier=classifier::classify)
        try {
            f.controller.initialize();assertEquals(SafetyLevel.CRISIS,f.controller.state.value.safety);assertNull(f.controller.state.value.visibleCoolingDraft())
            f.controller.selectContact("b");assertEquals("b",f.controller.state.value.currentContact?.id);assertEquals(SafetyLevel.NONE,f.controller.state.value.safety)
            f.controller.selectContact("a");assertEquals(SafetyLevel.CRISIS,f.controller.state.value.safety)
            assertEquals(1,crisisReads);assertEquals(1,calmReads);assertTrue(f.provider.calls.isEmpty())
        } finally {f.close()}
    }

    @Test fun historicalSummaryCrisisMustMatchTargetInputSourceRulesAndConfiguration() {
        val current=AnalysisRequest(requestId="summary",conversationId="conversation:a",contactId="a",userText="clean",purpose=OutboundPurpose.LONG_RECORD_SUMMARY)
        val requests=listOf(current.copy(contactId="b"),current.copy(userText="old"),current.copy(conversationId="old"),current.copy(conversationRevision=1),current.copy(sourceRevision=1),current.copy(redactionProfile=RedactionProfile(revision=1)))
        val p=CounselControllerTest.MemoryPersistence().apply {
            people+=Contact("a","合成甲")
            val runs=requests.mapIndexed {i,r->AnalysisRun(requestId="old:$i",conversationId=r.conversationId,contactId=r.contactId,requestSnapshot=r,highestSafety=SafetyLevel.CRISIS)}+
                AnalysisRun(requestId="old-config",conversationId=current.conversationId,contactId="a",requestSnapshot=current,configRevision="old",highestSafety=SafetyLevel.CRISIS)
            saved["a"]=StoredCounsel(Conversation("conversation:a","a",coolingDraft=CoolingDraft("kept",1000)),"clean",emptyList(),runs=runs)
        }
        val f=CounselControllerTest.Fixture(p)
        try {
            f.controller.initialize();assertEquals(SafetyLevel.NONE,f.controller.state.value.safety)
            assertEquals("kept",f.controller.state.value.visibleCoolingDraft()?.text)
            assertTrue(p.saved.getValue("a").runs.all {it.highestSafety==SafetyLevel.CRISIS})
        } finally {f.close()}
    }

    @Test fun changedCurrentDraftDoesNotPermanentlyInheritAnOldModelCheckpoint()=runBlocking {
        val p=CounselControllerTest.MemoryPersistence().apply {people+=Contact("a","合成甲");people+=Contact("b","合成乙")}
        val f=CounselControllerTest.Fixture(p)
        suspend fun await(predicate:()->Boolean)=withTimeout(5000) {while(!predicate()) yield()}
        try {
            val c=f.controller;c.initialize();c.editDraft("first");c.analyze();await {f.provider.calls.size==1}
            f.provider.outputs.last().send(AnalysisChunk.Completed(StructuredAnswer(id="answer",reply=listOf("kept"))))
            await {c.state.value.hasCurrentAnswerSource() && !c.state.value.streaming}
            c.saveCoolingDraft(c.state.value.safetyActionReceipt(),"kept")
            c.editDraft("model context");c.analyze();await {f.provider.calls.size==2}
            f.provider.outputs.last().send(AnalysisChunk.Section(SectionId.SAFETY,"CRISIS"));await {c.state.value.safety==SafetyLevel.CRISIS}
            c.editDraft("new clean context");c.selectContact("b");await {c.state.value.currentContact?.id=="b"}
            assertEquals(SafetyLevel.NONE,c.state.value.safety)
            c.selectContact("a");await {c.state.value.currentContact?.id=="a"}
            assertEquals("new clean context",c.state.value.draft);assertEquals(SafetyLevel.NONE,c.state.value.safety)
            assertEquals("kept",c.state.value.visibleCoolingDraft()?.text)
            assertTrue(p.saved.getValue("a").runs.any {it.highestSafety==SafetyLevel.CRISIS})
        } finally {f.close()}
    }
}
