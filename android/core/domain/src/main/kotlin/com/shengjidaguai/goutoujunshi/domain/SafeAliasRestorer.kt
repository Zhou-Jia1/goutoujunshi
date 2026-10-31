package com.shengjidaguai.goutoujunshi.domain

/** Restore complete human-readable fields only. Stream deltas remain masked, including split tokens.
 * IDs, references, raw trace and memory proposals retain their original machine representation. */
object SafeAliasRestorer {
    fun displayPartial(value: String, prompt: PreparedPrompt?): String {
        if (prompt == null) return value
        val lastOpen = value.lastIndexOf('[')
        val tail = if (lastOpen >= 0) value.substring(lastOpen) else ""
        val complete = if (tail.isNotEmpty() && prompt.safeAliases.keys.any { it.startsWith(tail) && it != tail }) value.substring(0, lastOpen) else value
        return Regex("\\[昵称[0-9]+]").replace(complete) { prompt.safeAliases[it.value] ?: it.value }
    }
    fun restore(answer: StructuredAnswer, prompt: PreparedPrompt): StructuredAnswer {
        fun text(value: String): String = Regex("\\[昵称[0-9]+]").replace(value) { prompt.safeAliases[it.value] ?: it.value }
        return answer.copy(
            empathy = text(answer.empathy), verdict = text(answer.verdict), reply = answer.reply.map(::text),
            alternatives = answer.alternatives.map { it.copy(label = text(it.label), reply = text(it.reply)) },
            why = answer.why.map(::text), cost = text(answer.cost), situation = text(answer.situation),
            facts = answer.facts.map { it.copy(text = text(it.text)) }, guesses = answer.guesses.map { it.copy(text = text(it.text)) },
            unknowns = answer.unknowns.map(::text), risk = text(answer.risk), habit = text(answer.habit), next = text(answer.next),
            tips = answer.tips.map { it.copy(name = text(it.name), explanation = text(it.explanation), example = text(it.example)) },
            ask = answer.ask.map(::text), freeText = text(answer.freeText), sections = answer.sections.mapValues { text(it.value) },
        )
    }
}
