package com.shengjidaguai.goutoujunshi.counsel

import com.shengjidaguai.goutoujunshi.data.TemporarySessionStore
import com.shengjidaguai.goutoujunshi.data.network.*
import com.shengjidaguai.goutoujunshi.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class ModelSettingsTest {
    private class Repository : ModelSettingsRepository {
        var snapshot = ModelSettingsSnapshot()
        var failAfterCommit = false
        override suspend fun load() = snapshot
        override suspend fun save(settings: SavedModelSettings, newKey: String?, removeKey: Boolean): ModelSettingsSnapshot {
            val mask = if (removeKey) null else if (newKey != null) "••••" + newKey.takeLast(4)
                else snapshot.maskedKey.takeIf { sameCredentialOrigin(snapshot.saved.connection(), settings.connection()) }
            snapshot = ModelSettingsSnapshot(settings, mask)
            if (failAfterCommit) { failAfterCommit = false; error("synthetic acknowledgement failure") }
            return snapshot
        }
        override suspend fun clear() { snapshot = ModelSettingsSnapshot() }
    }
    private class Provider(override val kind: ProviderKind) : AnalysisProvider {
        val requests = mutableListOf<AnalysisRequest>()
        val output = Channel<AnalysisChunk>(Channel.UNLIMITED)
        var cancelled = false
        override fun analyze(request: AnalysisRequest) = flow {
            requests += request
            try { for (chunk in output) { emit(chunk); if (chunk is AnalysisChunk.Completed || chunk is AnalysisChunk.Failed) break } }
            finally { cancelled = true }
        }
    }
    private class Fixture {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repo = Repository()
        val demo = Provider(ProviderKind.DEMO)
        val lives = mutableListOf<Provider>()
        val assembler = FrozenPromptAssembler(object : PromptAssembler {
            override fun prepare(request: AnalysisRequest) = PreparedPrompt("synthetic knowledge", "redacted ${request.userText}", listOf(KnowledgeReference("synthetic.md", "a".repeat(64), 9)), "synthetic-v1")
        })
        val controller = CounselController(CounselControllerTest.MemoryPersistence(), TemporarySessionStore(), assembler,
            demo, scope, repo, { Provider(ProviderKind.BYOK).also { lives += it } })
        init { controller.initialize() }
        fun live(host: String = "a.example", key: String? = "synthetic-key-1234") = controller.saveModelSettings(
            SavedModelSettings(true, "https://$host/v1", "synthetic-model", "synthetic"), key)
        fun close() = scope.cancel()
    }
    private suspend fun await(predicate: () -> Boolean) = withTimeout(3000) { while (!predicate()) yield() }
    private val completion = AnalysisChunk.Completed(StructuredAnswer(reply = listOf("synthetic response")))

    @Test fun liveRequiresPreviewAndBodyMatchesFrozenAssemblerWithoutFallback() = runBlocking {
        val f = Fixture()
        try {
            f.live(); f.controller.editDraft("synthetic input"); f.controller.analyze()
            assertEquals("PREVIEW_REQUIRED", f.controller.state.value.error?.code)
            assertTrue(f.lives.single().requests.isEmpty())
            f.controller.preparePreview(); await { f.controller.state.value.preview != null }
            assertEquals(CompletionBody.encode(f.repo.snapshot.saved.connection(), f.controller.state.value.preview!!), f.controller.state.value.outboundBody)
            f.controller.analyze(); await { f.lives.single().requests.size == 1 }
            f.lives.single().output.send(AnalysisChunk.Failed(AnalysisError("HTTP_401", "safe", false)))
            await { !f.controller.state.value.streaming }
            assertEquals("HTTP_401", f.controller.state.value.error?.code)
            assertTrue(f.demo.requests.isEmpty())
        } finally { f.close() }
    }

    @Test fun configSwitchCancelsProviderAndInvalidatesPreviewAndAnswer() = runBlocking {
        val f = Fixture()
        try {
            f.live(); f.controller.editDraft("synthetic input"); f.controller.preparePreview()
            await { f.controller.state.value.preview != null }
            f.controller.analyze(); await { f.lives.first().requests.isNotEmpty() }
            f.live("b.example", null)
            await { f.lives.first().cancelled }
            f.lives.first().output.send(completion)
            assertNull(f.controller.state.value.answer)
            assertNull(f.controller.state.value.preview)
            assertNull(f.controller.state.value.outboundBody)
            assertNull(f.controller.state.value.modelSettings.maskedKey)
            assertFalse(f.controller.state.value.demo)
        } finally { f.close() }
    }

    @Test fun connectionProbeUsesOnlySyntheticInputAndCancelThenClearRejectsCompletion() = runBlocking {
        val f = Fixture()
        try {
            f.live(); f.controller.editDraft("private synthetic draft")
            f.controller.testModelConnection(); await { f.lives.first().requests.isNotEmpty() }
            val probe = f.lives.first().requests.single()
            assertEquals("connection-test", probe.requestId)
            assertFalse(probe.userText.contains("private"))
            assertNull(probe.contactId); assertTrue(probe.evidence.isEmpty())
            f.controller.cancelModelTest(); await { f.lives.first().cancelled }
            assertEquals("CANCELLED", f.controller.state.value.connectionResult)
            f.controller.clearAll()
            f.lives.first().output.send(completion)
            assertEquals(ModelSettingsSnapshot(), f.controller.state.value.modelSettings)
            assertNull(f.controller.state.value.connectionResult)
            assertTrue(f.controller.state.value.demo)
            f.live()
            assertEquals(ModelSettingsSnapshot(), f.repo.snapshot) // explicit initialize required after wipe
        } finally { f.close() }
    }

    @Test fun originPolicyNeverReusesKeyAcrossHostOrPort() {
        val a = ModelConnection("https://a.example/v1", "a", "a")
        assertTrue(sameCredentialOrigin(a, a.copy(baseUrl = "https://a.example:443/v2", model = "b")))
        assertFalse(sameCredentialOrigin(a, a.copy(baseUrl = "https://b.example/v1")))
        assertFalse(sameCredentialOrigin(a, a.copy(baseUrl = "https://a.example:444/v1")))
        assertFalse(sameCredentialOrigin(a, a.copy(baseUrl = "http://a.example/v1")))
    }

    @Test fun savedSettingsReloadWithoutPuttingSecretInUiStateAndNewOriginRequiresExplicitKey() = runBlocking {
        val f = Fixture()
        try {
            f.live()
            assertEquals("••••1234", f.controller.state.value.modelSettings.maskedKey)
            assertFalse(f.controller.state.value.toString().contains("synthetic-key"))
            assertEquals(f.controller.state.value.modelSettings, f.repo.load())
            f.live("b.example", null)
            assertNull(f.controller.state.value.modelSettings.maskedKey)
            f.live("c.example", "synthetic-proxy-5678")
            assertEquals("••••5678", f.controller.state.value.modelSettings.maskedKey)
        } finally { f.close() }
    }

    @Test fun legacyMessagesDefaultToDemoProvenance() {
        val message = kotlinx.serialization.json.Json.decodeFromString(Message.serializer(), "{\"id\":\"legacy-synthetic\",\"role\":\"ASSISTANT\"}")
        assertEquals(ProviderKind.DEMO, message.providerKind)
    }

    @Test fun ambiguousSettingsCommitRevokesOldProviderForBothSendAndConnectionTest() = runBlocking {
        val f = Fixture()
        try {
            f.live()
            val old = f.lives.single()
            f.repo.failAfterCommit = true
            f.live("b.example", "synthetic-B-key")
            assertEquals("SETTINGS_UNAVAILABLE", f.controller.state.value.connectionResult)
            assertNull(f.controller.state.value.modelSettings.maskedKey)
            assertFalse(f.controller.state.value.demo)
            f.controller.testModelConnection()
            await { f.controller.state.value.connectionResult == "SETTINGS_UNAVAILABLE" && !f.controller.state.value.testingConnection }
            f.controller.editDraft("synthetic draft"); f.controller.preparePreview()
            await { f.controller.state.value.preview != null }
            f.controller.analyze()
            await { f.controller.state.value.error?.code == "SETTINGS_UNAVAILABLE" }
            assertTrue(old.requests.isEmpty())
            assertTrue(f.demo.requests.isEmpty())
        } finally { f.close() }
    }
}
