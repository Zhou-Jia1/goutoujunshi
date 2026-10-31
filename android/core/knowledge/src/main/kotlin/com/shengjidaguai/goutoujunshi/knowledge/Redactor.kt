package com.shengjidaguai.goutoujunshi.knowledge

import com.shengjidaguai.goutoujunshi.domain.RedactionAlias

/** Select spans in ORIGINAL input; generated tokens are never searched again. */
class Redactor(customTerms: List<String> = emptyList(), aliases: List<RedactionAlias> = emptyList()) {
    private val terms = customTerms.filter { it.isNotBlank() }.distinct()
    private val names = aliases.associate { it.original to it.alias }
    private val substitutions = linkedMapOf<Pair<String, String>, String>()
    private val safe = linkedMapOf<String, String>()
    val count: Int get() = substitutions.size
    val safeAliases: Map<String, String> get() = safe.toMap()
    init {
        require(terms.size + aliases.size <= 100 && terms.all { it.length <= 200 })
        require(aliases.all { candidate ->
            candidate.original.isNotBlank() && candidate.original.length <= 200 &&
                candidate.alias.isNotBlank() && candidate.alias.length <= 40 &&
                !candidate.alias.any { it.isISOControl() || it in "[]<>" } &&
                names.keys.none { candidate.alias.contains(it) } &&
                terms.none { candidate.alias.contains(it) } &&
                patterns.none { (_, regex) -> regex.containsMatchIn(candidate.alias) }
        }) { "Unsafe alias" }
    }
    private data class Span(val start: Int, val end: Int, val kind: String, val value: String, val priority: Int)
    fun redact(input: String): String {
        val candidates = mutableListOf<Span>()
        fun literal(value: String, kind: String, priority: Int) {
            var from = 0
            while (from < input.length) {
                val index = input.indexOf(value, from)
                if (index < 0) break
                candidates += Span(index, index + value.length, kind, value, priority)
                from = index + 1
            }
        }
        terms.forEach { literal(it, "屏蔽词", 0) }
        names.keys.forEach { literal(it, "昵称", 1) }
        patterns.forEachIndexed { index, (kind, regex) -> regex.findAll(input).forEach {
            candidates += Span(it.range.first, it.range.last + 1, kind, it.value, index + 2)
        } }
        // Longest overlapping span wins (e.g. phone inside a custom company name).
        val selected = mutableListOf<Span>()
        candidates.sortedWith(compareByDescending<Span> { it.end - it.start }.thenBy { it.priority }.thenBy { it.start }).forEach { span ->
            if (selected.none { span.start < it.end && it.start < span.end }) selected += span
        }
        return buildString {
            var cursor = 0
            for (span in selected.sortedBy { it.start }) {
                append(input.substring(cursor, span.start))
                val token = substitutions.getOrPut(span.kind to span.value) {
                    "[${span.kind}${substitutions.keys.count { it.first == span.kind } + 1}]"
                }
                if (span.kind == "昵称") safe[token] = names.getValue(span.value)
                append(token)
                cursor = span.end
            }
            append(input.substring(cursor))
        }
    }
    companion object {
        private val patterns = listOf(
            "邮箱" to Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"),
            "链接" to Regex("https?://[^\\s<>]+", RegexOption.IGNORE_CASE),
            "证件号" to Regex("(?<![0-9])[0-9]{17}[0-9Xx](?![0-9])"),
            "银行卡号" to Regex("(?<![0-9])[0-9]{16,19}(?![0-9])"),
            "手机号" to Regex("(?<![0-9])(?:\\+?86[- ]?)?1[3-9][0-9]{9}(?![0-9])"),
            "电话" to Regex("(?<![0-9])0[0-9]{2,3}[- ]?[0-9]{7,8}(?![0-9])"),
            "地址" to Regex("[\\p{IsHan}]{2,}(?:省|市|区|县)[\\p{IsHan}0-9]{1,}(?:路|街|巷)[0-9一二三四五六七八九十号栋单元室-]{1,}"),
        )
    }
}
