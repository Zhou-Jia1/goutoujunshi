package com.shengjidaguai.goutoujunshi.counsel

import com.shengjidaguai.goutoujunshi.domain.*
import com.shengjidaguai.goutoujunshi.data.TemporarySessionStore
import com.shengjidaguai.goutoujunshi.data.network.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class T26ContinuousTest {
    private suspend fun await(p: () -> Boolean) = withTimeout(3000) { while (!p()) yield() }
    private class LiveFixture(val p: CounselControllerTest.MemoryPersistence = CounselControllerTest.MemoryPersistence()) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val provider = CounselControllerTest.ControlledProvider()
        val settings = object : ModelSettingsRepository {
            override suspend fun load() = ModelSettingsSnapshot(SavedModelSettings(true, "https://example.org/v1/", "synthetic", "Mock", "r1"), "masked")
            override suspend fun save(settings: SavedModelSettings, newKey: String?, removeKey: Boolean) = load()
            override suspend fun clear() {}
        }
        val controller = CounselController(p, TemporarySessionStore(), FrozenPromptAssembler(object : PromptAssembler {
            override fun prepare(request: AnalysisRequest) = PreparedPrompt("synthetic system", "${request.purpose}:${request.networkSelection}:${request.recentSummary}:${request.timelineSummary}", listOf(KnowledgeReference("synthetic", "a".repeat(64), 1)), "v1")
        }), provider, scope, settings, { provider })
        suspend fun setup() {
            controller.initialize(); controller.createContact("虚构甲", RelationType.FRIEND)
            val c = controller.state.value.currentContact!!
            val old = p.saved.getValue(c.id)
            val evidence = listOf(EvidenceBlock("batch", "PASTE", (1..1000).map {
                ChatLine("batch:$it", "batch", it, Speaker.ME, "虚构记录".repeat(20), speakerConfirmed=true, contactId=c.id)
            }))
            p.saved[c.id] = old.copy(evidence=evidence)
            controller.selectContact(c.id)
            controller.editDraft("虚构问题")
        }
        fun close() = scope.cancel()
    }
    @Test fun summaryAndFullHaveSeparateFrozenPreviewsAndOnlyOneUserMessage() = runBlocking {
        val f=LiveFixture(); try {
            f.setup(); f.controller.analyze(); assertTrue(f.provider.calls.isEmpty())
            f.controller.preparePreview(); await { f.controller.state.value.outboundBody != null }
            val summaryBody=f.controller.state.value.outboundBody
            f.controller.analyze(); await { f.provider.calls.size==1 }
            val summary=f.provider.calls[0]
            assertEquals(OutboundPurpose.LONG_RECORD_SUMMARY,summary.purpose)
            assertEquals(1000,summary.evidence.single().lines.size)
            assertTrue(f.controller.state.value.messages.isEmpty())
            val ref=summary.networkSelection!!.first()
            f.provider.outputs[0].send(AnalysisChunk.Completed(StructuredAnswer(freeText="[$ref] 虚构早期事件")))
            await { f.controller.state.value.summaryReady }
            assertEquals(1,f.provider.calls.size);assertNull(f.controller.state.value.answer)
            assertNull(f.controller.state.value.outboundBody)
            f.controller.analyze(); assertEquals(1,f.provider.calls.size)
            f.controller.preparePreview(); await { f.controller.state.value.outboundBody != null }
            assertNotEquals(summaryBody,f.controller.state.value.outboundBody)
            assertTrue(f.controller.state.value.preview!!.user.contains("虚构早期事件"))
            f.controller.analyze(); await { f.provider.calls.size==2 }
            assertEquals(OutboundPurpose.ANALYSIS,f.provider.calls[1].purpose)
            assertEquals(1,f.controller.state.value.messages.count { it.role==MessageRole.USER })
        } finally { f.close() }
    }
    @Test fun completedSummarySurvivesRebuildWithoutAutomaticNetworkOrRepeatedSummary() = runBlocking {
        val first=LiveFixture();first.setup();first.controller.preparePreview();first.controller.analyze()
        await { first.provider.calls.size==1 }
        val ref=first.provider.calls[0].networkSelection!!.first()
        first.provider.outputs[0].send(AnalysisChunk.Completed(StructuredAnswer(freeText="[$ref] 虚构保留事件")))
        await { first.controller.state.value.summaryReady };first.close()
        val second=LiveFixture(first.p)
        try {
            second.controller.initialize();yield();assertTrue(second.provider.calls.isEmpty())
            second.controller.preparePreview();await { second.controller.state.value.outboundBody!=null }
            assertTrue(second.controller.state.value.preview!!.user.contains("虚构保留事件"))
            second.controller.analyze();await { second.provider.calls.size==1 }
            assertEquals(OutboundPurpose.ANALYSIS,second.provider.calls.single().purpose)
            assertEquals(1,second.controller.state.value.messages.count { it.role==MessageRole.USER })
        } finally { second.close() }
    }
    @Test fun cancelledSummaryWriteCannotBeReusedAfterStopSwitchOrClear() = runBlocking {
        for (action in listOf("stop", "switch", "clear")) {
            val f=LiveFixture();try {
                f.setup();val id=f.controller.state.value.currentContact!!.id
                f.controller.preparePreview();f.controller.analyze();await { f.provider.calls.size==1 }
                val ref=f.provider.calls[0].networkSelection!!.first()
                val barrier=CompletableDeferred<Unit>();f.p.beforeSummary=barrier
                f.provider.outputs[0].send(AnalysisChunk.Completed(StructuredAnswer(freeText="[$ref] 不可复用的晚结果")))
                await { f.p.summaryStarted }
                when(action) { "stop"->f.controller.stop();"switch"->f.controller.newTemporary();else->f.controller.clearAll() }
                barrier.complete(Unit)
                await { !f.controller.state.value.streaming }
                assertTrue(f.p.saved[id]?.summaries.orEmpty().isEmpty())
                assertFalse(f.controller.state.value.summaryReady)
                if(action=="clear") assertTrue(f.p.saved.isEmpty())
                else {
                    f.controller.selectContact(id);f.controller.preparePreview();await { f.controller.state.value.outboundBody!=null }
                    assertEquals(OutboundPurpose.LONG_RECORD_SUMMARY,f.controller.state.value.currentRequest?.purpose)
                    assertFalse(f.controller.state.value.preview!!.user.contains("不可复用的晚结果"))
                }
            }finally { f.close() }
        }
    }
    @Test fun earlierFailedSummaryCannotLowerLaterCompletedCrisisOnRebuild() = runBlocking {
        val first=LiveFixture();first.setup();first.controller.preparePreview();first.controller.analyze();await { first.provider.calls.size==1 }
        first.provider.outputs[0].send(AnalysisChunk.Failed(AnalysisError("NETWORK","",true)))
        await { first.controller.state.value.error!=null }
        first.controller.preparePreview();first.controller.analyze();await { first.provider.calls.size==2 }
        first.provider.outputs[1].send(AnalysisChunk.Completed(StructuredAnswer(safety=SafetyLevel.CRISIS)))
        await { !first.controller.state.value.streaming };first.close()
        val second=LiveFixture(first.p)
        try { second.controller.initialize();assertEquals(SafetyLevel.CRISIS,second.controller.state.value.safety) }
        finally { second.close() }
    }
    @Test fun olderFailedRunReusesNewerCompletedCacheOnlyAfterFreshFullPreview() = runBlocking {
        val first=LiveFixture();first.setup();first.controller.preparePreview();first.controller.analyze();await { first.provider.calls.size==1 }
        first.provider.outputs[0].send(AnalysisChunk.Failed(AnalysisError("NETWORK","",true)))
        await { first.controller.state.value.error!=null }
        first.controller.preparePreview();first.controller.analyze();await { first.provider.calls.size==2 }
        val ref=first.provider.calls[1].networkSelection!!.first()
        first.provider.outputs[1].send(AnalysisChunk.Completed(StructuredAnswer(freeText="[$ref] B已经完成的普通摘要")))
        await { first.controller.state.value.summaryReady };first.close()
        val second=LiveFixture(first.p)
        try {
            second.controller.initialize();assertTrue(second.provider.calls.isEmpty())
            second.controller.preparePreview();await { second.controller.state.value.outboundBody!=null }
            assertTrue(second.provider.calls.isEmpty())
            assertTrue(second.controller.state.value.preview!!.user.contains("B已经完成的普通摘要"))
            second.controller.analyze();await { second.provider.calls.size==1 }
            assertEquals(OutboundPurpose.ANALYSIS,second.provider.calls.single().purpose)
            val duringFull=LiveFixture(first.p)
            try { duringFull.controller.initialize();assertEquals(OutboundPurpose.ANALYSIS,duringFull.controller.state.value.currentRequest?.purpose) }
            finally { duringFull.close() }
            second.provider.outputs[0].send(AnalysisChunk.Completed(StructuredAnswer(id="full-after-b",reply=listOf("虚构最终回复"))))
            await { second.controller.state.value.answer!=null };second.close()
            val third=LiveFixture(first.p)
            try {
                third.controller.initialize();assertNull(third.controller.state.value.error)
                assertNull(third.controller.state.value.currentRequest)
                assertEquals("full-after-b",third.controller.state.value.answer?.id)
                assertTrue(third.provider.calls.isEmpty())
            }finally { third.close() }
        }finally { second.close() }
    }
    @Test fun savedCacheWithFailedFinalCheckpointRebuildsWithoutAnotherSummaryCall() = runBlocking {
        val first=LiveFixture();first.setup();first.controller.preparePreview();first.controller.analyze();await { first.provider.calls.size==1 }
        val ref=first.provider.calls[0].networkSelection!!.first();first.p.summaryCompletionFailures=1
        first.provider.outputs[0].send(AnalysisChunk.Completed(StructuredAnswer(freeText="[$ref] 已经付费完成的摘要")))
        await { first.controller.state.value.error!=null };first.close()
        val second=LiveFixture(first.p)
        try {
            second.controller.initialize();second.controller.preparePreview();await { second.controller.state.value.preview!=null }
            second.controller.analyze();await { second.provider.calls.size==1 }
            assertEquals(OutboundPurpose.ANALYSIS,second.provider.calls.single().purpose)
            assertTrue(second.provider.calls.single().timelineSummary.contains("已经付费完成的摘要"))
        }finally { second.close() }
    }
    @Test fun cancelledCacheCleanupFailureIsRetryableAndCannotBeReusedOnRebuild() = runBlocking {
        val first=LiveFixture();first.setup();first.controller.preparePreview();first.controller.analyze();await { first.provider.calls.size==1 }
        val ref=first.provider.calls[0].networkSelection!!.first()
        val barrier=CompletableDeferred<Unit>();first.p.beforeSummary=barrier;first.p.discardSummaryFailures=1
        first.provider.outputs[0].send(AnalysisChunk.Completed(StructuredAnswer(freeText="[$ref] 已取消的摘要")))
        await { first.p.summaryStarted };first.controller.stop();barrier.complete(Unit)
        await { !first.controller.state.value.streaming }
        assertEquals("STORAGE_UNAVAILABLE",first.controller.state.value.error?.code)
        val second=LiveFixture(first.p)
        try {
            second.controller.initialize();second.controller.preparePreview();await { second.controller.state.value.preview!=null }
            assertEquals(OutboundPurpose.LONG_RECORD_SUMMARY,second.controller.state.value.currentRequest?.purpose)
            assertFalse(second.controller.state.value.preview!!.user.contains("已取消的摘要"))
            first.controller.retry();await { first.p.saved.values.single().summaries.isEmpty() }
            assertEquals(1,first.provider.calls.size)
        } finally { first.close();second.close() }
    }
    @Test fun summaryStorageFailureRetriesStorageWithoutCallingModel() = runBlocking {
        val f=LiveFixture();try {
            f.setup();f.controller.preparePreview();f.controller.analyze();await { f.provider.calls.size==1 }
            val ref=f.provider.calls.single().networkSelection!!.first()
            f.p.summaryFailures=1
            f.provider.outputs[0].send(AnalysisChunk.Completed(StructuredAnswer(freeText="[$ref] 虚构时间线")))
            await { f.controller.state.value.error!=null }
            assertEquals("STORAGE_UNAVAILABLE",f.controller.state.value.error?.code)
            f.controller.retry();await { f.controller.state.value.summaryReady }
            assertEquals(1,f.provider.calls.size)
        }finally { f.close() }
    }
    @Test fun summaryCrisisImmediatelyHidesOrdinaryStrategyAndNeverCallsFull() = runBlocking {
        val f=LiveFixture();try {
            f.setup();f.controller.preparePreview();f.controller.analyze();await { f.provider.calls.size==1 }
            f.provider.outputs[0].send(AnalysisChunk.Completed(StructuredAnswer(safety=SafetyLevel.CRISIS)))
            await { !f.controller.state.value.streaming }
            assertEquals(SafetyLevel.CRISIS,f.controller.state.value.safety)
            assertEquals(SafetyLevel.CRISIS,f.p.saved.values.single().runs.single().highestSafety)
            f.controller.preparePreview();f.controller.analyze();yield();assertEquals(1,f.provider.calls.size)
            assertTrue(f.controller.state.value.messages.isEmpty())
            val rebuilt = LiveFixture(f.p)
            try { rebuilt.controller.initialize(); assertEquals(SafetyLevel.CRISIS, rebuilt.controller.state.value.safety); rebuilt.controller.preparePreview(); assertTrue(rebuilt.provider.calls.isEmpty()) }
            finally { rebuilt.close() }
        }finally { f.close() }
    }
    @Test fun threeRoundsKeepBothPriorVerdictsAndChoicesButNoOtherContact() = runBlocking {
        val p=CounselControllerTest.MemoryPersistence()
        val setup=CounselControllerTest.Fixture(p)
        setup.controller.initialize();setup.controller.createContact("虚构甲",RelationType.FRIEND)
        val c=setup.controller.state.value.currentContact!!;setup.close()
        p.people += Contact("other", "虚构乙")
        p.saved["other"] = StoredCounsel(Conversation("other-conv","other",false),"别人的秘密", listOf(Message("other-msg","other-conv",MessageRole.ASSISTANT,"别人的秘密",contactId="other")))
        repeat(3) { round ->
            val old=p.saved.getValue(c.id)
            val id="import-$round"
            val line=ChatLine("$id:1",id,0,Speaker.ME,"虚构第${round+1}轮",sentAt="2026-10-0${3-round}",speakerConfirmed=true,contactId=c.id)
            p.saved[c.id]=old.copy(evidence=old.evidence+EvidenceBlock(id,"PASTE",listOf(line)))
            p.people.replaceAll { if(it.id==c.id) it.copy(sourceRevision=it.sourceRevision+1) else it }
            val f=CounselControllerTest.Fixture(p)
            try {
                f.controller.initialize();f.controller.editDraft("虚构问题$round");f.controller.analyze()
                await { f.provider.calls.size==1 }
                if(round==2) {
                    val text=f.provider.calls[0].recentSummary
                    assertTrue(text,text.contains("判断0"));assertTrue(text,text.contains("判断1"));assertTrue(text,text.contains("SENT"));assertFalse(text.contains("别人的秘密"))
                }
                f.provider.outputs[0].send(AnalysisChunk.Completed(StructuredAnswer(id="a$round",verdict="判断$round",next="下一步$round",reply=listOf("虚构回复$round"))))
                await { f.controller.state.value.answer!=null }
                f.controller.feedback(f.controller.state.value.conversation!!.id,"a$round")
                await { f.controller.state.value.feedbackSaved }
            } finally { f.close() }
        }
    }
}
