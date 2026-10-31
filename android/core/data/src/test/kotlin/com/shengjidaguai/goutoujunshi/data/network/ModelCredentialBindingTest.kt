package com.shengjidaguai.goutoujunshi.data.network

import com.shengjidaguai.goutoujunshi.domain.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.last
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class ModelCredentialBindingTest {
    private class Records : ModelRecordStorage {
        var bytes: ByteArray? = null
        var failAcknowledgement = false
        var failReadback = false
        var pendingReadFailure = false
        override suspend fun read(): ByteArray? {
            if (pendingReadFailure) { pendingReadFailure = false; error("synthetic readback failure") }
            return bytes?.copyOf()
        }
        override suspend fun write(record: ByteArray) {
            bytes = record.copyOf()
            if (failReadback) { failReadback = false; pendingReadFailure = true }
            if (failAcknowledgement) { failAcknowledgement = false; error("synthetic acknowledgement failure") }
        }
        override suspend fun clear() { bytes = null }
    }
    private val assembler = object : PromptAssembler {
        override fun prepare(request: AnalysisRequest) = PreparedPrompt("synthetic knowledge", "synthetic probe",
            listOf(KnowledgeReference("synthetic.md", "a".repeat(64), 9)), "synthetic-v1")
    }
    private val a = SavedModelSettings(true, "https://a.synthetic.example/v1", "synthetic-model", "A")
    private val b = a.copy(baseUrl = "https://b.synthetic.example/v1", label = "B")
    private fun provider(server: MockWebServer, credentials: CredentialSource) = OpenAiCompatibleProvider(
        ModelConnection(server.url("/v1").toString(), "synthetic-model", "Mock"), assembler, credentials,
        allowLocalhostHttpForTests = true)

    @Test fun committedNewRecordWithFailedReadbackCannotSendBKeyThroughOldAProvider() = runBlocking {
        for (failure in listOf("readback", "acknowledgement")) {
            val records = Records(); val repo = VaultModelSettingsRepository(records)
            val original = repo.save(a, "synthetic-A-key", false)
            val oldSource = VaultCredentialSource(records, original.saved)
            if (failure == "readback") records.failReadback = true else records.failAcknowledgement = true
            assertTrue(runCatching { repo.save(b, "synthetic-B-key", false) }.isFailure)
            MockWebServer().use { serverA ->
                val terminal = provider(serverA, oldSource).analyze(AnalysisRequest(userText = "synthetic")).last()
                assertEquals("KEY_MISSING", (terminal as AnalysisChunk.Failed).error.code)
                assertEquals(0, serverA.requestCount)
            }
            val current = repo.load()
            assertEquals(b.baseUrl, current.saved.baseUrl)
            assertNotEquals(original.saved.revision, current.saved.revision)
            MockWebServer().use { serverB ->
                serverB.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
                    .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"synthetic reply\"}}]}\n\ndata: [DONE]\n\n"))
                assertTrue(provider(serverB, VaultCredentialSource(records, current.saved))
                    .analyze(AnalysisRequest(userText = "synthetic")).last() is AnalysisChunk.Completed)
                assertEquals("Bearer synthetic-B-key", serverB.takeRequest().getHeader("Authorization"))
            }
        }
    }

    @Test fun keyRemovalClearAndSameOriginRevisionRevokeRetainedReaders() = runBlocking {
        val records = Records(); val repo = VaultModelSettingsRepository(records)
        val first = repo.save(a, "synthetic-A-key", false)
        val stale = VaultCredentialSource(records, first.saved)
        val second = repo.save(a.copy(model = "another-synthetic-model"), null, false)
        assertNull(stale.read())
        assertEquals("synthetic-A-key", VaultCredentialSource(records, second.saved).read()!!.toString(Charsets.UTF_8))
        val removed = repo.save(second.saved, null, true)
        assertNull(VaultCredentialSource(records, second.saved).read())
        assertNull(VaultCredentialSource(records, removed.saved).read())
        val restored = repo.save(a, "synthetic-new-key", false)
        repo.clear()
        assertNull(VaultCredentialSource(records, restored.saved).read())
    }
}
