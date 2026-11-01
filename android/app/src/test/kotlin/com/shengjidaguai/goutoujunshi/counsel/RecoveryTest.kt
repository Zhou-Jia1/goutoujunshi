package com.shengjidaguai.goutoujunshi.counsel

import com.shengjidaguai.goutoujunshi.domain.*
import com.shengjidaguai.goutoujunshi.data.TemporarySessionStore
import com.shengjidaguai.goutoujunshi.data.network.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class RecoveryTest {
    @Test fun finalCrisisCheckpointFailureRetriesHighestSafetyBeforeAssistantWithoutAnotherRequest() = runBlocking {
        val f = CounselControllerTest.Fixture()
        try {
            ready(f)
            val id = f.provider.calls.single().requestId
            f.persistence.runFailures = 1
            val output = f.provider.outputs.single()
            output.send(AnalysisChunk.Section(SectionId.REPLY, "虚构普通策略"))
            output.send(AnalysisChunk.Completed(StructuredAnswer(safety = SafetyLevel.CRISIS,
                reply = listOf("虚构普通策略"), rawText = "虚构普通策略", freeText = "虚构普通策略", formatDisordered = true)))
            withTimeout(2000) { while(f.controller.state.value.error == null) yield() }
            val failed = f.controller.state.value
            assertEquals("STORAGE_UNAVAILABLE", failed.error?.code)
            assertNull(failed.answer)
            assertFalse(SafetyPolicy.evaluate(failed.emotion, failed.safety).showOrdinaryStrategy)
            assertEquals(0, f.persistence.saved.values.single().messages.count { it.role == MessageRole.ASSISTANT })
            f.controller.retry()
            val completed = f.controller.state.value
            assertEquals(SafetyLevel.CRISIS, completed.answer?.safety)
            assertFalse(SafetyPolicy.evaluate(completed.emotion, completed.answer!!.safety, completed.safety).showOrdinaryStrategy)
            assertEquals(SafetyLevel.CRISIS, f.persistence.saved.values.single().runs.single().highestSafety)
            assertEquals(1, f.provider.calls.size)
            assertEquals(id, completed.currentRequest?.requestId)
        } finally { f.close() }
    }
    @Test fun crisisCheckpointSurvivesDirectProcessDeathWithoutFailureOrStop() = runBlocking {
        val p = CounselControllerTest.MemoryPersistence()
        val first = CounselControllerTest.Fixture(p)
        ready(first)
        first.provider.outputs.single().send(AnalysisChunk.Section(SectionId.SAFETY, "CRISIS"))
        withTimeout(2000) { while(p.saved.values.single().runs.single().highestSafety != SafetyLevel.CRISIS) yield() }
        first.close()
        val second = CounselControllerTest.Fixture(p)
        try {
            second.controller.initialize()
            assertEquals(SafetyLevel.CRISIS, second.controller.state.value.safety)
            second.controller.retry()
            withTimeout(2000) { while(second.provider.calls.isEmpty()) yield() }
            second.provider.outputs.single().send(AnalysisChunk.Completed(StructuredAnswer(reply = listOf("虚构普通策略"))))
            withTimeout(2000) { while(second.controller.state.value.answer == null) yield() }
            assertEquals(SafetyLevel.CRISIS, second.controller.state.value.answer?.safety)
        } finally { second.close() }
    }

    @Test fun safetyCheckpointFailurePreservesGateAndRetryRepairsStorageWithoutNetwork() = runBlocking {
        val f = CounselControllerTest.Fixture()
        try {
            ready(f)
            f.persistence.runFailures = 1
            f.provider.outputs.single().send(AnalysisChunk.Section(SectionId.SAFETY, "CRISIS"))
            withTimeout(2000) { while(f.controller.state.value.error == null) yield() }
            assertEquals(SafetyLevel.CRISIS, f.controller.state.value.safety)
            assertEquals("STORAGE_UNAVAILABLE", f.controller.state.value.error?.code)
            f.controller.retry()
            assertEquals(1, f.provider.calls.size)
            assertEquals("INTERRUPTED", f.controller.state.value.error?.code)
            assertEquals(SafetyLevel.CRISIS, f.persistence.saved.values.single().runs.single().highestSafety)
        } finally { f.close() }
    }
    @Test fun finalCrisisIsPublishedBeforeAssistantStorageCanFail() = runBlocking {
        val f = CounselControllerTest.Fixture()
        try {
            ready(f)
            f.persistence.assistantFailures = 1
            val output = f.provider.outputs.single()
            output.send(AnalysisChunk.Section(SectionId.REPLY, "虚构普通策略"))
            output.send(AnalysisChunk.Completed(StructuredAnswer(safety = SafetyLevel.CRISIS, reply = listOf("虚构普通策略"))))
            withTimeout(2000) { while(f.controller.state.value.error == null) yield() }
            assertEquals("STORAGE_UNAVAILABLE", f.controller.state.value.error?.code)
            assertNull(f.controller.state.value.answer)
            assertEquals(SafetyLevel.CRISIS, f.controller.state.value.safety)
            assertEquals(SafetyLevel.CRISIS, f.persistence.saved.values.single().runs.single().highestSafety)
            f.controller.retry()
            assertEquals(SafetyLevel.CRISIS, f.controller.state.value.answer?.safety)
            assertEquals(1, f.provider.calls.size)
        } finally { f.close() }
    }
    @Test fun metadataOnlyStillShowsSlowAfterTwentySecondsWithoutCancelling() = runBlocking {
        var clock = 0L
        val f = CounselControllerTest.Fixture(clock = { clock })
        try {
            ready(f)
            f.provider.outputs.single().send(AnalysisChunk.Section(SectionId.SAFETY, "NONE"))
            withTimeout(2000) { while(f.controller.state.value.partial.isEmpty()) yield() }
            clock = 20_001
            withTimeout(2000) { while(!f.controller.state.value.waiting.slow) delay(10) }
            assertEquals(2, f.controller.state.value.waiting.stage)
            assertTrue(f.controller.state.value.streaming)
            assertEquals(1, f.provider.calls.size)
        } finally { f.close() }
    }

    @Test fun sameOperationRetryAndFormalRecoveryKeepHighestCrisis() = runBlocking {
        val p = CounselControllerTest.MemoryPersistence()
        val first = CounselControllerTest.Fixture(p)
        ready(first)
        val id = first.provider.calls.single().requestId
        first.provider.outputs.single().send(AnalysisChunk.Section(SectionId.SAFETY, "CRISIS"))
        first.provider.outputs.single().send(AnalysisChunk.Failed(AnalysisError("NETWORK", "ignored", true)))
        withTimeout(2000) { while(first.controller.state.value.error == null) yield() }
        assertEquals(SafetyLevel.CRISIS, p.saved.values.single().runs.single().highestSafety)
        first.controller.retry()
        withTimeout(2000) { while(first.provider.calls.size < 2) yield() }
        assertEquals(SafetyLevel.CRISIS, first.controller.state.value.safety)
        assertEquals(id, first.provider.calls.last().requestId)
        first.provider.outputs.last().send(AnalysisChunk.Section(SectionId.SAFETY, "NONE"))
        first.close()
        val second = CounselControllerTest.Fixture(p)
        try {
            second.controller.initialize()
            assertEquals(SafetyLevel.CRISIS, second.controller.state.value.safety)
            second.controller.retry()
            withTimeout(2000) { while(second.provider.calls.isEmpty()) yield() }
            second.provider.outputs.single().send(AnalysisChunk.Completed(StructuredAnswer(reply = listOf("虚构普通策略"))))
            withTimeout(2000) { while(second.controller.state.value.answer == null) yield() }
            assertEquals(SafetyLevel.CRISIS, second.controller.state.value.answer?.safety)
        } finally { second.close() }
    }
    @Test fun highestStreamSafetySurvivesMalformedLowerCompletionAndIsPersisted() = runBlocking {
        val f = CounselControllerTest.Fixture()
        try {
            ready(f)
            val output = f.provider.outputs.single()
            output.send(AnalysisChunk.Section(SectionId.SAFETY, "CRISIS"))
            output.send(AnalysisChunk.Section(SectionId.SAFETY, "NONE"))
            output.send(AnalysisChunk.Completed(StructuredAnswer(reply = listOf("虚构普通策略"), freeText = "格式乱", formatDisordered = true)))
            withTimeout(2000) { while(f.controller.state.value.answer == null) yield() }
            assertEquals(SafetyLevel.CRISIS, f.controller.state.value.answer?.safety)
            val message = f.persistence.saved.values.single().messages.single { it.role == MessageRole.ASSISTANT }
            assertEquals(SafetyLevel.CRISIS, kotlinx.serialization.json.Json.decodeFromString(StructuredAnswer.serializer(), message.answerJson!!).safety)
        } finally { f.close() }
    }
    @Test fun liveRecoveryNeedsFreshCompletePreviewAndConfirmationWithSameRequestId() = runBlocking {
        val p = CounselControllerTest.MemoryPersistence()
        val settings = object : ModelSettingsRepository {
            override suspend fun load() = ModelSettingsSnapshot(SavedModelSettings(true, "https://synthetic.example/v1", "synthetic", revision = "synthetic-revision"))
            override suspend fun save(settings: SavedModelSettings, newKey: String?, removeKey: Boolean) = load()
            override suspend fun clear() {}
        }
        val assembler = object : PromptAssembler {
            override fun prepare(request: AnalysisRequest) = PreparedPrompt("虚构知识", "虚构发送 ${request.userText}",
                listOf(KnowledgeReference("synthetic.md", "a".repeat(64), 4)), "synthetic")
        }
        val first = CounselControllerTest.Fixture(p)
        ready(first)
        val c = p.people.single()
        val id = first.provider.calls.single().requestId
        p.saved[c.id] = p.saved.getValue(c.id).let { it.copy(runs = it.runs.map { r -> r.copy(configRevision = "synthetic-revision") }) }
        first.close()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val provider = CounselControllerTest.ControlledProvider()
        val controller = CounselController(p, TemporarySessionStore(), FrozenPromptAssembler(assembler), provider, scope, settings, { provider })
        try {
            controller.initialize()
            assertEquals("INTERRUPTED", controller.state.value.error?.code)
            controller.retry()
            withTimeout(2000) { while(controller.state.value.preview == null) yield() }
            assertTrue(provider.calls.isEmpty())
            assertNotNull(controller.state.value.outboundBody)
            controller.analyze()
            withTimeout(2000) { while(provider.calls.isEmpty()) yield() }
            assertEquals(id, provider.calls.single().requestId)
        } finally { scope.cancel() }
    }
    private suspend fun ready(f: CounselControllerTest.Fixture, formal: Boolean = true) {
        f.controller.initialize()
        if (formal) f.controller.createContact("虚构甲", RelationType.FRIEND)
        f.controller.editDraft("虚构问题")
        f.controller.analyze()
        withTimeout(2000) { while(f.provider.calls.isEmpty()) yield() }
    }

    @Test fun temporaryProcessDeathHasNoStoredRunOrDraft() = runBlocking {
        val p = CounselControllerTest.MemoryPersistence()
        val first = CounselControllerTest.Fixture(p)
        ready(first, false)
        first.close()
        assertTrue(p.saved.isEmpty())
        val second = CounselControllerTest.Fixture(p)
        try {
            second.controller.initialize()
            assertEquals("", second.controller.state.value.draft)
            assertNull(second.controller.state.value.error)
            assertTrue(second.provider.calls.isEmpty())
        } finally { second.close() }
    }

    @Test fun checkpointFailureRetriesStorageBeforeOfferingAnotherNetworkCall() = runBlocking {
        val f = CounselControllerTest.Fixture()
        try {
            ready(f)
            f.persistence.runFailures = 1
            f.provider.outputs.single().send(AnalysisChunk.Failed(AnalysisError("NETWORK", "ignored", true)))
            withTimeout(2000) { while(f.controller.state.value.error == null) yield() }
            assertEquals("STORAGE_UNAVAILABLE", f.controller.state.value.error?.code)
            f.controller.retry()
            assertEquals(1, f.provider.calls.size)
            assertEquals("NETWORK", f.controller.state.value.error?.code)
            f.controller.retry()
            withTimeout(2000) { while(f.provider.calls.size < 2) yield() }
        } finally { f.close() }
    }

    @Test fun completedAnswerCheckpointFailureRetriesOnlyStorage() = runBlocking {
        val f = CounselControllerTest.Fixture()
        try {
            ready(f)
            f.persistence.runFailures = 1
            f.provider.outputs.single().send(AnalysisChunk.Completed(StructuredAnswer(reply = listOf("虚构完成"))))
            withTimeout(2000) { while(f.controller.state.value.error == null) yield() }
            assertEquals("STORAGE_UNAVAILABLE", f.controller.state.value.error?.code)
            f.controller.retry()
            assertNotNull(f.controller.state.value.answer)
            assertEquals(1, f.provider.calls.size)
            assertEquals(1, f.persistence.saved.values.single().messages.count { it.role == MessageRole.ASSISTANT })
        } finally { f.close() }
    }

    @Test fun networkFailureKeepsDraftAndSameRequestAndDoesNotDuplicateUser() = runBlocking {
        for (formal in listOf(false, true)) {
            val f = CounselControllerTest.Fixture()
            try {
                ready(f, formal)
                val request = f.provider.calls.single()
                f.provider.outputs.single().send(AnalysisChunk.Failed(AnalysisError("NETWORK", "ignored-secret", true)))
                withTimeout(2000) { while(f.controller.state.value.error == null) yield() }
                assertEquals("虚构问题", f.controller.state.value.draft)
                assertEquals(formal, f.controller.state.value.draftSaved)
                f.controller.retry()
                withTimeout(2000) { while(f.provider.calls.size < 2) yield() }
                assertEquals(request, f.provider.calls.last())
                assertEquals(1, f.controller.state.value.messages.size)
            } finally { f.close() }
        }
    }

    @Test fun oldSourceInputPrivacyOrConfigurationDoesNotRecover() = runBlocking {
        for (mutation in listOf("source", "input", "privacy", "configuration", "deleted")) {
            val p = CounselControllerTest.MemoryPersistence()
            val first = CounselControllerTest.Fixture(p)
            ready(first)
            first.close()
            val c = p.people.single()
            when (mutation) {
                "source" -> p.people[0] = c.copy(sourceRevision = 1)
                "input" -> p.saved[c.id] = p.saved.getValue(c.id).copy(draft = "另一个输入")
                "privacy" -> p.privacy[c.id] = RedactionProfile(revision = 1)
                "configuration" -> p.saved[c.id] = p.saved.getValue(c.id).let { it.copy(runs = it.runs.map { r -> r.copy(configRevision = "obsolete") }) }
                "deleted" -> p.deleteContact(c.id)
            }
            val second = CounselControllerTest.Fixture(p)
            try {
                second.controller.initialize()
                assertNull("obsolete $mutation", second.controller.state.value.error)
                assertTrue(second.provider.calls.isEmpty())
            } finally { second.close() }
        }
    }

    @Test fun savedAssistantPreventsResendEvenWithUnacknowledgedRunCompletion() = runBlocking {
        val p = CounselControllerTest.MemoryPersistence()
        val first = CounselControllerTest.Fixture(p)
        ready(first)
        val request = first.provider.calls.single()
        val c = p.people.single()
        val answer = StructuredAnswer(reply = listOf("虚构完成回复"))
        p.saveMessage(c, p.saved.getValue(c.id).conversation, Message("assistant:${request.requestId}", request.conversationId,
            MessageRole.ASSISTANT, "虚构完成回复", kotlinx.serialization.json.Json.encodeToString(StructuredAnswer.serializer(), answer), requestId = request.requestId, contactId = c.id))
        first.close()
        val second = CounselControllerTest.Fixture(p)
        try {
            second.controller.initialize()
            assertNotNull(second.controller.state.value.answer)
            assertNull(second.controller.state.value.error)
            second.controller.retry()
            assertTrue(second.provider.calls.isEmpty())
        } finally { second.close() }
    }
    @Test fun waitingUsesTimeAndNeverPercentage() {
        assertEquals(0, WaitingPhase.at(0).stage)
        assertEquals(1, WaitingPhase.at(5_000).stage)
        assertEquals(2, WaitingPhase.at(12_000).stage)
        assertFalse(WaitingPhase.at(20_000).slow)
        assertTrue(WaitingPhase.at(20_001).slow)
    }

    @Test fun formalProcessRecoveryRequiresExplicitContinuationAndReusesOperation() = runBlocking {
        val persistence = CounselControllerTest.MemoryPersistence()
        val first = CounselControllerTest.Fixture(persistence)
        first.controller.initialize()
        first.controller.createContact("虚构甲", RelationType.FRIEND)
        first.controller.editDraft("虚构问题")
        first.controller.analyze()
        withTimeout(2000) { while(first.provider.calls.isEmpty()) yield() }
        val id = first.provider.calls.single().requestId
        first.close()
        val second = CounselControllerTest.Fixture(persistence)
        try {
            second.controller.initialize()
            assertEquals("INTERRUPTED", second.controller.state.value.error?.code)
            assertTrue(second.provider.calls.isEmpty())
            second.controller.retry()
            withTimeout(2000) { while(second.provider.calls.isEmpty()) yield() }
            assertEquals(id, second.provider.calls.single().requestId)
            assertEquals(1, second.controller.state.value.messages.count { it.role == MessageRole.USER })
        } finally { second.close() }
    }
}
