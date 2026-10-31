package com.shengjidaguai.goutoujunshi.data.network

import com.shengjidaguai.goutoujunshi.domain.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test

class OpenAiCompatibleProviderTest {
    @Test fun `account and refusal errors are static and never echo provider body`() = runTest {
        for ((body, code) in listOf(
            "{\"error\":{\"code\":\"insufficient_quota\",\"message\":\"$secret\"}}" to "INSUFFICIENT_BALANCE",
            "{\"error\":{\"code\":\"content_policy_violation\",\"message\":\"$secret\"}}" to "CONTENT_FILTERED")) {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(429).setBody(body))
                val result = error(provider(server))
                assertEquals(code, result.code)
                assertFalse(result.toString().contains(secret))
                assertFalse(result.retryable)
            }
        }
        MockWebServer().use { server ->
            server.enqueue(stream(event("[[REPLY]]虚构半截") + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"content_filter\"}]}\n\ndata: [DONE]\n\n"))
            val chunks = provider(server).analyze(AnalysisRequest(userText = "虚构输入")).toList()
            assertEquals("CONTENT_FILTERED", (chunks.last() as AnalysisChunk.Failed).error.code)
            assertTrue(chunks.none { it is AnalysisChunk.Completed })
        }
    }

    @Test fun `malformed model text completes as readable content while broken envelopes never complete`() = runTest {
        MockWebServer().use { server ->
            val text = "虚构全文：没有标记也要能读。[[UNKNOWN]]末尾"
            server.enqueue(stream(event(text) + "data: [DONE]\n\n"))
            val answer = (provider(server).analyze(AnalysisRequest(userText = "虚构输入")).last() as AnalysisChunk.Completed).answer
            assertEquals(text, answer.rawText)
            assertTrue(answer.freeText.contains("末尾"))
        }
        MockWebServer().use { server ->
            server.enqueue(stream(event("[[REPLY]]虚构半截") + "data: invalid-$secret\n\ndata: [DONE]\n\n"))
            val chunks = provider(server).analyze(AnalysisRequest(userText = "虚构输入")).toList()
            assertEquals("INVALID_JSON", (chunks.last() as AnalysisChunk.Failed).error.code)
            assertTrue(chunks.none { it is AnalysisChunk.Completed })
            assertFalse(chunks.last().toString().contains(secret))
        }
    }
    private val secret = "sk-fictional-secret-DO-NOT-ECHO"
    private val reference = KnowledgeReference("references/test.md", "a".repeat(64), 20)
    private val assembler = object : PromptAssembler {
        override fun prepare(request: AnalysisRequest) = PreparedPrompt(
            "虚构知识正文：先分清事实和猜测。", request.userText.replace("13800138000", "[手机号1]"), listOf(reference), "test-v1")
    }
    private fun provider(server: MockWebServer, prompt: PromptAssembler = assembler,
        timeouts: ModelTimeouts = ModelTimeouts(), client: OkHttpClient = OkHttpClient(),
        credentials: CredentialSource = CredentialSource { secret.toByteArray() }) = OpenAiCompatibleProvider(
        ModelConnection(server.url("/custom/v1/").toString(), "synthetic-model", "Mock"), prompt,
        credentials, client, timeouts, allowLocalhostHttpForTests = true)
    private fun event(content: String): String = "data: " + buildJsonObject {
        putJsonArray("choices") { add(buildJsonObject { putJsonObject("delta") { put("content", content) } }) }
    } + "\n\n"
    private fun stream(body: String) = MockResponse().setHeader("Content-Type", "text/event-stream; charset=utf-8").setBody(body)

    @Test fun `idle deadline starts after first content instead of while waiting for headers`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(stream(event("[[REPLY]]合成回复") + "data: [DONE]\n\n").setHeadersDelay(200, TimeUnit.MILLISECONDS))
            val chunks = provider(server, timeouts = ModelTimeouts(connectMillis = 1_000, firstPacketMillis = 1_000, idleMillis = 50))
                .analyze(AnalysisRequest(userText = "合成输入")).toList()
            assertEquals(listOf("合成回复"), (chunks.last() as AnalysisChunk.Completed).answer.reply)
        }
    }

    @Test fun `sse permits lone CR and mixed line endings`() = runTest {
        for (ending in listOf("\r", "\r\n", "\n")) MockWebServer().use { server ->
            val body = (event("[[REPLY]]合成回复🙂") + "data: [DONE]\n\n").replace("\n", ending)
            server.enqueue(stream(body).throttleBody(1, 1, TimeUnit.MILLISECONDS))
            val chunks = provider(server).analyze(AnalysisRequest(userText = "合成输入")).toList()
            assertEquals(listOf("合成回复🙂"), (chunks.last() as AnalysisChunk.Completed).answer.reply)
        }
    }
    private suspend fun error(provider: OpenAiCompatibleProvider): AnalysisError =
        (provider.analyze(AnalysisRequest(userText = "合成输入")).last() as AnalysisChunk.Failed).error

    @Test fun `real request uses only prepared system and redacted user with unicode stream`() = runTest {
        MockWebServer().use { server ->
            val multiline = "data: {\"choices\":\n" + "data: [{\"delta\":{\"content\":\"好🙂\"}}]}\n\n"
            server.enqueue(stream(": keepalive\n\n" + event("[[RE") + event("PLY]]你") + multiline + "data: [DONE]\n\n")
                .throttleBody(1, 1, TimeUnit.MILLISECONDS))
            val chunks = provider(server).analyze(AnalysisRequest(requestId = "r1", userText = "联系13800138000")).toList()
            val answer = (chunks.last() as AnalysisChunk.Completed).answer
            assertEquals(listOf("你好🙂"), answer.reply)
            assertEquals("你好🙂", chunks.filterIsInstance<AnalysisChunk.Section>().joinToString("") { it.textDelta })
            val request = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("/custom/v1/chat/completions", request.path)
            assertEquals("Bearer $secret", request.getHeader("Authorization"))
            val raw = request.body.readUtf8()
            assertEquals(CompletionBody.encode(ModelConnection(server.url("/custom/v1/").toString(), "synthetic-model", "Mock"),
                assembler.prepare(AnalysisRequest(requestId = "r1", userText = "联系13800138000"))), raw)
            assertFalse(raw.contains("13800138000"))
            val root = Json.parseToJsonElement(raw).jsonObject
            assertEquals(true, root["stream"]!!.jsonPrimitive.boolean)
            val messages = root["messages"]!!.jsonArray
            assertEquals("虚构知识正文：先分清事实和猜测。", messages[0].jsonObject["content"]!!.jsonPrimitive.content)
            assertEquals("联系[手机号1]", messages[1].jsonObject["content"]!!.jsonPrimitive.content)
        }
    }

    @Test fun `preparation failure never reads key or sends HTTP`() = runTest {
        MockWebServer().use { server ->
            val failing = object : PromptAssembler { override fun prepare(request: AnalysisRequest): PreparedPrompt = error("missing knowledge $secret") }
            var keyRead = false
            assertEquals("PROMPT_INVALID", error(provider(server, failing, credentials = CredentialSource { keyRead = true; secret.toByteArray() })).code)
            assertFalse(keyRead)
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun `HTTP errors are mapped without exposing bodies secrets or inputs and never retried`() = runTest {
        for (status in listOf(401, 429, 500)) MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(status).setBody("$secret phone=13800138000 password=private"))
            val error = error(provider(server))
            assertEquals("HTTP_$status", error.code)
            assertFalse(error.message.contains(secret)); assertFalse(error.message.contains("13800138000"))
            assertFalse(error.message.contains("private")); assertEquals(1, server.requestCount)
        }
    }

    @Test fun `invalid JSON non SSE EOF and broken streams cannot complete`() = runTest {
        val cases = listOf(
            stream("data: not-json-$secret\n\n") to "INVALID_JSON",
            MockResponse().setHeader("Content-Type", "application/json").setBody("{}") to "NOT_SSE",
            stream(event("[[REPLY]]partial")) to "STREAM_INTERRUPTED",
            stream(event("[[REPLY]]partial") + event("x".repeat(4096))).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY) to null,
        )
        for ((response, expected) in cases) MockWebServer().use { server ->
            server.enqueue(response)
            val result = error(provider(server))
            if (expected != null) assertEquals(expected, result.code)
            assertFalse(result.message.contains(secret)); assertEquals(1, server.requestCount)
        }
    }

    @Test fun `first packet timeout is bounded`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val result = error(provider(server, timeouts = ModelTimeouts(1000, 100, 1000)))
            assertEquals("FIRST_PACKET_TIMEOUT", result.code)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun `keepalives cannot indefinitely postpone first content or idle timeout`() = runTest {
        for (prefix in listOf("", event("[[REPLY]]partial"))) MockWebServer().use { server ->
            server.enqueue(stream(prefix + ": keepalive\n\n".repeat(200)).throttleBody(50, 20, TimeUnit.MILLISECONDS))
            val result = error(provider(server, timeouts = ModelTimeouts(1000, 150, 150)))
            assertEquals(if (prefix.isEmpty()) "FIRST_PACKET_TIMEOUT" else "STREAM_TIMEOUT", result.code)
        }
    }

    @Test fun `idle timeout after partial output does not report completion`() = runTest {
        MockWebServer().use { server ->
            // Small flushed chunks finish the first event quickly, then keep the transport
            // alive without model content. Assert that content arrived before testing idle.
            server.enqueue(stream(event("[[REPLY]]partial") + ": idle\n\n".repeat(1_000) + "data: [DONE]\n\n")
                .throttleBody(50, 20, TimeUnit.MILLISECONDS))
            val chunks = provider(server, timeouts = ModelTimeouts(1000, 2000, 100))
                .analyze(AnalysisRequest(userText = "合成输入")).toList()
            assertTrue(chunks.any { it is AnalysisChunk.Section && it.textDelta.contains("partial") })
            assertFalse(chunks.any { it is AnalysisChunk.Completed })
            assertEquals("STREAM_TIMEOUT", (chunks.last() as AnalysisChunk.Failed).error.code)
        }
    }

    @Test fun `collector cancellation promptly cancels actual OkHttp call`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val cancelled = CompletableDeferred<Unit>()
            val client = OkHttpClient.Builder().eventListener(object : EventListener() {
                override fun canceled(call: Call) { cancelled.complete(Unit) }
            }).build()
            val job = launch(Dispatchers.Default) { provider(server, client = client).analyze(AnalysisRequest(userText = "synthetic")).collect() }
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)) }
            job.cancel()
            withContext(Dispatchers.Default) { withTimeout(2000) { cancelled.await(); job.join() } }
            assertTrue(job.isCancelled)
        }
    }

    @Test fun `connection probe validates an actual completion`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(stream(event("连接成功") + "data: [DONE]\n\n"))
            assertEquals(ConnectionTestResult.Success, provider(server).testConnection())
            server.enqueue(MockResponse().setResponseCode(401))
            assertTrue(provider(server).testConnection() is ConnectionTestResult.Failure)
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun `URL validation keeps base path and disallows secret URL parts and insecure production`() {
        assertEquals("https://example.test/custom/v1/chat/completions", ModelConnection("https://example.test/custom/v1/", "m", "p").completionUrl().toString())
        assertEquals("https://example.test/chat/completions", ModelConnection("https://example.test/chat/completions", "m", "p").completionUrl().toString())
        for (url in listOf("http://localhost/v1", "https://user:pass@example.test", "https://example.test?key=secret", "https://example.test#secret")) {
            assertThrows(IllegalArgumentException::class.java) { ModelConnection(url, "m", "p").completionUrl() }
        }
        assertThrows(IllegalArgumentException::class.java) { ModelConnection("http://example.test", "m", "p").completionUrl(true) }
    }
}
