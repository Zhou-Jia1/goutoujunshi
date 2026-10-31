package com.shengjidaguai.goutoujunshi.knowledge

/** One session-scoped redactor is shared across every field of an outbound request. */
class Redactor(customTerms: List<String> = emptyList()) {
    private val terms = customTerms.filter { it.isNotBlank() }.distinct().sortedByDescending { it.codePointLength() }
    private val substitutions = linkedMapOf<Pair<String, String>, String>()
    val count: Int get() = substitutions.size

    fun redact(input: String): String {
        var output = input
        for (term in terms) if (output.contains(term)) output = output.replace(term, token("屏蔽词", term))
        for ((kind, expression) in patterns) {
            output = expression.replace(output) { token(kind, it.value) }
        }
        return output
    }

    private fun token(kind: String, value: String): String = substitutions.getOrPut(kind to value) {
        "[$kind${substitutions.keys.count { it.first == kind } + 1}]"
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
