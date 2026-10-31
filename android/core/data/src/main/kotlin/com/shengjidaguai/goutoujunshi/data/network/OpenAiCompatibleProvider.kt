package com.shengjidaguai.goutoujunshi.data.network

import com.shengjidaguai.goutoujunshi.domain.*
import java.io.EOFException
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import okhttp3.Call
import okhttp3.Authenticator
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class ModelTimeouts(val connectMillis: Long = 15_000, val firstPacketMillis: Long = 45_000, val idleMillis: Long = 45_000) {
    init { require(connectMillis > 0 && firstPacketMillis > 0 && idleMillis > 0) }
}

sealed interface ConnectionTestResult {
    data object Success : ConnectionTestResult
    data class Failure(val error: AnalysisError) : ConnectionTestResult
}

/** No logging, redirects, or transparent retries: each collection represents one potentially billable request. */
class OpenAiCompatibleProvider(
    private val connection: ModelConnection,
    private val assembler: PromptAssembler,
    private val credentials: CredentialSource,
    client: OkHttpClient = OkHttpClient(),
    private val timeouts: ModelTimeouts = ModelTimeouts(),
    private val allowLocalhostHttpForTests: Boolean = false,
) : AnalysisProvider {
    override val kind = ProviderKind.BYOK
    private val http = client.newBuilder()
        .connectTimeout(timeouts.connectMillis, TimeUnit.MILLISECONDS)
        // Phase timers below own the deadline. An OkHttp read timeout here would also apply
        // before the first response and incorrectly shorten firstPacketMillis to idleMillis.
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(timeouts.connectMillis, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
        .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
        .addNetworkInterceptor { chain ->
            val attempt = chain.request().tag(SingleAttempt::class.java)
            if (attempt != null && !attempt.started.compareAndSet(false, true)) throw IOException("Automatic retry disabled")
            chain.proceed(chain.request())
        }.build()
    private val json = Json { ignoreUnknownKeys = true }

    override fun analyze(request: AnalysisRequest): Flow<AnalysisChunk> = channelFlow {
        val call = AtomicReference<Call?>(null)
        val worker = launch(Dispatchers.IO) {
            val timedOut = AtomicReference<String?>(null)
            var firstPacketTimer: kotlinx.coroutines.Job? = null
            try {
                // Preparation is the sole source of outbound content, including knowledge and redaction.
                val prompt = try { assembler.prepare(request) } catch (e: CancellationException) { throw e }
                catch (_: Exception) { throw ProviderFailure("PROMPT_INVALID", "发送内容未能通过本地知识与隐私检查", false) }
                val url = try { connection.completionUrl(allowLocalhostHttpForTests) }
                catch (_: IllegalArgumentException) { throw ProviderFailure("CONFIG_INVALID", "请检查 HTTPS 模型地址与模型名称", false) }
                val bytes = credentials.read() ?: throw ProviderFailure("KEY_MISSING", "请先填写 API Key", false)
                val key = try { bytes.toString(Charsets.UTF_8) } finally { bytes.fill(0) }
                if (key.isBlank() || key.any { it.isISOControl() } || key.any { it.code > 127 }) {
                    throw ProviderFailure("KEY_INVALID", "API Key 格式无效", false)
                }
                val body = CompletionBody.encode(connection, prompt).toRequestBody("application/json; charset=utf-8".toMediaType())
                val active = http.newCall(Request.Builder().url(url).header("Authorization", "Bearer $key")
                    .header("Accept", "text/event-stream").tag(SingleAttempt::class.java, SingleAttempt()).post(body).build())
                call.set(active)
                currentCoroutineContext().ensureActive()
                firstPacketTimer = launch(Dispatchers.Default) {
                    delay(timeouts.firstPacketMillis)
                    timedOut.set("FIRST_PACKET_TIMEOUT"); active.cancel()
                }
                active.execute().use { response ->
                    if (!response.isSuccessful) throw httpFailure(response.code)
                    if (response.header("Content-Type")?.substringBefore(';')?.trim()?.equals("text/event-stream", true) != true) {
                        throw ProviderFailure("NOT_SSE", "服务商未返回 SSE 流式响应，请检查协议与地址", false)
                    }
                    val source = response.body.source()
                    val lines = SseLineReader(source, MAX_LINE_BYTES)
                    val parser = SectionParser()
                    var receivedText = false
                    val events = SseDecoder()
                    while (true) {
                        val line = lines.readLine()
                        val data = events.feedLine(line) ?: continue
                        if (data == "[DONE]") {
                            if (!receivedText) throw ProviderFailure("EMPTY_RESPONSE", "模型没有返回文本内容", true)
                            firstPacketTimer?.cancel()
                            send(AnalysisChunk.Completed(parser.finish().copy(id = request.requestId)))
                            break
                        }
                        val token = decodeToken(data)
                        if (token.isNotEmpty()) {
                            firstPacketTimer?.cancel()
                            firstPacketTimer = launch(Dispatchers.Default) {
                                delay(timeouts.idleMillis)
                                timedOut.set("STREAM_TIMEOUT"); active.cancel()
                            }
                            receivedText = true
                            for (section in parser.feed(token)) send(section)
                        }
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                // Never return exception messages or response bodies: they may echo credentials or input.
                val error = when {
                    timedOut.get() == "FIRST_PACKET_TIMEOUT" -> AnalysisError("FIRST_PACKET_TIMEOUT", "模型迟迟没有返回内容，可稍后手动重试", true)
                    timedOut.get() == "STREAM_TIMEOUT" -> AnalysisError("STREAM_TIMEOUT", "接收模型内容超时，可稍后手动重试", true)
                    e is ProviderFailure -> AnalysisError(e.code, e.safeMessage, e.canRetry)
                    e is SocketTimeoutException -> AnalysisError("STREAM_TIMEOUT", "接收模型内容超时，可稍后手动重试", true)
                    e is EOFException -> AnalysisError("STREAM_INTERRUPTED", "模型响应中途断开，尚未完成", true)
                    e is IOException -> AnalysisError("NETWORK", "网络连接中断，请检查网络后手动重试", true)
                    else -> AnalysisError("PROVIDER_ERROR", "模型请求失败，请检查配置后手动重试", false)
                }
                send(AnalysisChunk.Failed(error))
            } finally {
                firstPacketTimer?.cancel()
                call.get()?.cancel()
            }
        }
        worker.invokeOnCompletion { cause -> close(cause) }
        // Runs as soon as collection is cancelled, even while execute/read blocks on an IO worker.
        awaitClose { worker.cancel(); call.get()?.cancel() }
    }

    /** A real completion probe with a synthetic request, using the same knowledge/privacy assembler. */
    suspend fun testConnection(): ConnectionTestResult = when (val terminal = analyze(
        AnalysisRequest(requestId = "connection-test", userText = "连接测试：请只回复‘连接成功’。")
    ).last()) {
        is AnalysisChunk.Completed -> ConnectionTestResult.Success
        is AnalysisChunk.Failed -> ConnectionTestResult.Failure(terminal.error)
        else -> ConnectionTestResult.Failure(AnalysisError("INCOMPLETE", "连接测试未完成", true))
    }

    private fun decodeToken(data: String): String {
        try {
            val root = json.parseToJsonElement(data).jsonObject
            if (root["error"] != null) throw ProviderFailure("PROVIDER_REJECTED", "服务商拒绝了模型请求，请检查账户和模型权限", false)
            val choices = root["choices"]?.jsonArray ?: throw IllegalArgumentException()
            if (choices.isEmpty()) return "" // Some providers send a final usage-only event.
            val delta = choices.first().jsonObject["delta"]?.jsonObject ?: throw IllegalArgumentException()
            val content = delta["content"] ?: return ""
            if (content == JsonNull) return ""
            require(content is JsonPrimitive && content.isString)
            return content.content
        } catch (e: ProviderFailure) { throw e }
        catch (_: Exception) { throw ProviderFailure("INVALID_JSON", "服务商返回的流式数据格式无效", false) }
    }

    private fun httpFailure(status: Int) = when (status) {
        401, 403 -> ProviderFailure("HTTP_$status", "API Key 无效或没有模型访问权限（$status）", false)
        429 -> ProviderFailure("HTTP_429", "服务商限流或额度不足（429），请稍后手动重试", true)
        in 500..599 -> ProviderFailure("HTTP_$status", "模型服务暂时不可用（$status），请稍后手动重试", true)
        else -> ProviderFailure("HTTP_$status", "模型服务返回错误（$status），请检查地址与模型配置", false)
    }

    private class ProviderFailure(val code: String, val safeMessage: String, val canRetry: Boolean) : Exception()
    private class SingleAttempt(val started: AtomicBoolean = AtomicBoolean(false))
    private companion object { const val MAX_LINE_BYTES = 262_144L }
}

typealias ByokProvider = OpenAiCompatibleProvider

/** The preview and transport share this exact encoder. Authorization is never included. */
object CompletionBody {
    fun encode(connection: ModelConnection, prompt: PreparedPrompt): String = buildJsonObject {
        put("model", connection.model); put("stream", true)
        putJsonArray("messages") {
            add(buildJsonObject { put("role", "system"); put("content", prompt.system) })
            add(buildJsonObject { put("role", "user"); put("content", prompt.user) })
        }
    }.toString()
}
