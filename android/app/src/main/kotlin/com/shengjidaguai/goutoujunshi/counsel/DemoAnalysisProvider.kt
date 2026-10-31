package com.shengjidaguai.goutoujunshi.counsel

import android.content.res.AssetManager
import com.shengjidaguai.goutoujunshi.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/** Canned examples only. The UI must keep its demo badge visible throughout playback. */
class DemoAnalysisProvider(
    private val assembler: PromptAssembler,
    private val readExample: (String) -> String,
    private val intervalMillis: Long = 50,
) : AnalysisProvider {
    constructor(assembler: PromptAssembler, assets: AssetManager) : this(assembler,
        { path -> assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() } })

    override val kind = ProviderKind.DEMO
    override fun analyze(request: AnalysisRequest): Flow<AnalysisChunk> = flow {
        try {
            // Exercise the same integrity, identity, knowledge routing and redaction gate as BYOK.
            assembler.prepare(request)
            val group = (request.relationType ?: RelationType.ACQUAINTANCE).group().name.lowercase()
            val text = readExample("demo/$group.txt")
            val parser = SectionParser()
            for (part in text.chunked(6)) {
                delay(intervalMillis)
                parser.feed(part).forEach { emit(it) }
            }
            emit(AnalysisChunk.Completed(parser.finish().copy(id = "answer:${request.requestId}")))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emit(AnalysisChunk.Failed(AnalysisError("DEMO_UNAVAILABLE", "", retryable = true)))
        }
    }.flowOn(Dispatchers.IO)
}
