package com.shengjidaguai.goutoujunshi.domain

import kotlinx.serialization.Serializable

/** Explicit privacy settings, independent of learning consent or identity discovery. */
@Serializable data class RedactionAlias(val original: String, val alias: String)
@Serializable data class RedactionProfile(
    val revision: Long = 0,
    val terms: List<String> = emptyList(),
    val aliases: List<RedactionAlias> = emptyList(),
) {
    init {
        require(revision >= 0 && terms.size + aliases.size <= 100)
        require(terms.all { it.isNotBlank() && it.length <= 200 })
        require(aliases.all { it.original.isNotBlank() && it.original.length <= 200 && it.alias.isNotBlank() && it.alias.length <= 40 })
        require(aliases.map { it.original }.distinct().size == aliases.size)
    }
}

@Serializable enum class OutboundPurpose { ANALYSIS, LONG_RECORD_SUMMARY, STYLE_DESCRIPTION, REPLY_REWRITE, PRACTICE }

/** Typed future-feature entry points retain all source/identity fields on the input snapshot. */
object OutboundRequests {
    fun analysis(source: AnalysisRequest) = source.copy(purpose = OutboundPurpose.ANALYSIS, mode = RequestMode.FULL_ANALYSIS)
    fun summary(source: AnalysisRequest) = source.copy(purpose = OutboundPurpose.LONG_RECORD_SUMMARY, mode = RequestMode.FULL_ANALYSIS)
    fun style(source: AnalysisRequest) = source.copy(purpose = OutboundPurpose.STYLE_DESCRIPTION, mode = RequestMode.FULL_ANALYSIS)
    fun rewrite(source: AnalysisRequest) = source.copy(purpose = OutboundPurpose.REPLY_REWRITE, mode = RequestMode.REWRITE_REPLY)
    fun practice(source: AnalysisRequest) = source.copy(purpose = OutboundPurpose.PRACTICE, mode = RequestMode.FULL_ANALYSIS)
}

/** Every purpose invokes the same provider/assembler gate; future screens use this engine. */
class OutboundEngine(private val provider: AnalysisProvider) {
    fun analyze(source: AnalysisRequest) = provider.analyze(OutboundRequests.analysis(source))
    fun summarize(source: AnalysisRequest) = provider.analyze(OutboundRequests.summary(source))
    fun describeStyle(source: AnalysisRequest) = provider.analyze(OutboundRequests.style(source))
    fun rewrite(source: AnalysisRequest) = provider.analyze(OutboundRequests.rewrite(source))
    fun practice(source: AnalysisRequest) = provider.analyze(OutboundRequests.practice(source))
}

@Serializable data class OutboundDataContext(val text: String = "", val sourceContactId: String? = null)
