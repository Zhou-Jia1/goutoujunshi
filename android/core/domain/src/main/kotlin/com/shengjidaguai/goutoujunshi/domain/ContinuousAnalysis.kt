package com.shengjidaguai.goutoujunshi.domain

import kotlinx.serialization.Serializable

@Serializable data class TimelineSummary(val key: String, val request: AnalysisRequest, val text: String)
data class RecordPlan(val recent: List<ChatLine>, val earlier: List<ChatLine>, val summarySelection: List<ChatLine>, val needsSummary: Boolean)

/** Network projection only. Original evidence remains authoritative for recovery and source policy. */
object ContinuousAnalysis {
    fun ordered(evidence: List<EvidenceBlock>): List<ChatLine> = evidence.flatMap { it.lines }
        .withIndex().sortedWith(compareBy<IndexedValue<ChatLine>> { timestamp(it.value.sentAt) }.thenBy { it.index }).map { it.value }
    private fun timestamp(value: String?): java.time.Instant {
        if (value == null) return java.time.Instant.MAX
        return runCatching { java.time.Instant.parse(value) }.getOrNull()
            ?: runCatching { java.time.OffsetDateTime.parse(value).toInstant() }.getOrNull()
            ?: runCatching { java.time.LocalDateTime.parse(value).toInstant(java.time.ZoneOffset.UTC) }.getOrNull()
            ?: runCatching { java.time.LocalDate.parse(value).atStartOfDay().toInstant(java.time.ZoneOffset.UTC) }.getOrNull()
            ?: java.time.Instant.MAX
    }
    fun cost(line: ChatLine) = 2 * line.id.length + (line.speakerName?.length ?: 2) + line.text.codePointCount(0, line.text.length) + 160
    private fun tail(lines: List<ChatLine>): List<ChatLine> {
        var budget = 12000
        return lines.asReversed().takeWhile { val fits = cost(it) <= budget; if (fits) budget -= cost(it); fits }.asReversed()
    }
    fun plan(evidence: List<EvidenceBlock>, overlay: Boolean = false): RecordPlan {
        val all = ordered(evidence)
        val recent = tail(all)
        require(all.isEmpty() || recent.isNotEmpty()) { "Latest message exceeds budget" }
        val earlier = all.dropLast(recent.size)
        return RecordPlan(recent, earlier, tail(earlier), earlier.isNotEmpty() && !overlay)
    }
    fun sourcesRetained(old: AnalysisRequest, contactId: String?, current: List<EvidenceBlock>): Boolean =
        old.contactId == contactId && old.evidence.all { original ->
            current.singleOrNull { it.id == original.id } == original
        } && old.evidence.flatMap { it.lines }.all { it.speakerConfirmed && (it.contactId == null || it.contactId == contactId) }
    /** Request-local sensitive token numbers never identify entities in another request. */
    fun scopeHistoricalTokens(text: String, scope: String): String =
        Regex("\\[(?:昵称|屏蔽词|邮箱|链接|证件号|银行卡号|手机号|电话|地址)[0-9]+]").replace(text) {
            "〈$scope·${it.value.removeSurrounding("[", "]")}〉"
        }
    fun historyExcerpt(text: String, budget: Int): String {
        val prefix = if (text.codePointCount(0, text.length) <= budget) text else text.substring(0, text.offsetByCodePoints(0, budget))
        val angle = prefix.lastIndexOf('〈').takeIf { it > prefix.lastIndexOf('〉') } ?: prefix.length
        val square = prefix.lastIndexOf('[').takeIf { it > prefix.lastIndexOf(']') } ?: prefix.length
        return prefix.substring(0, minOf(angle, square))
    }
    fun validSummary(text: String, selected: List<ChatLine>): Boolean {
        if (text.isBlank() || text.codePointCount(0, text.length) > 1000) return false
        val refs = Regex("\\[([^]\\r\\n]+)]").findAll(text).map { it.groupValues[1] }.toList()
        val ids = selected.filter { it.speakerConfirmed }.map { it.id }.toSet()
        val masked = Regex("(?:昵称|屏蔽词|邮箱|链接|证件号|银行卡号|手机号|电话|地址)[0-9]+")
        return refs.isNotEmpty() && refs.all { it in ids || masked.matches(it) } &&
            text.lineSequence().filter { it.isNotBlank() }.all { line ->
                Regex("^\\[([^]]+)]").find(line)?.groupValues?.get(1) in ids
            }
    }
}
