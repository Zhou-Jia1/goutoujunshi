package com.shengjidaguai.goutoujunshi.domain

import org.junit.Assert.*
import org.junit.Test

class SafeAliasRestorerTest {
    private val prompt = PreparedPrompt("public", "masked", listOf(KnowledgeReference("test.md", "a".repeat(64), 10)), "v1",
        safeAliases = mapOf("[昵称1]" to "小伙伴"))
    @Test fun accumulatedProjectionWaitsForCompleteAliasAndLeavesSensitiveAndUnknownTokensMasked() {
        val raw = "你好[昵称1] [手机号1] [昵称999]"
        for (end in 3..6) assertEquals("你好", SafeAliasRestorer.displayPartial(raw.take(end), prompt))
        assertEquals("你好小伙伴 [手机号1] [昵称999]", SafeAliasRestorer.displayPartial(raw, prompt))
        assertEquals("你好小伙伴 [手机号1] [昵称999]", SafeAliasRestorer.restore(StructuredAnswer(reply = listOf(raw)), prompt).reply.single())
        assertEquals(raw, SafeAliasRestorer.displayPartial(raw, null))
    }
    @Test fun identifiersReferencesAndTraceRemainStable() {
        val answer = StructuredAnswer(id = "[昵称1]", rawText = "[昵称1]", facts = listOf(Fact("[昵称1]", listOf("[昵称1]"))), reply = listOf("[昵称1]"))
        val result = SafeAliasRestorer.restore(answer, prompt)
        assertEquals(answer.id, result.id); assertEquals(answer.rawText, result.rawText)
        assertEquals(listOf("[昵称1]"), result.facts.single().messageRefs)
        assertEquals("小伙伴", result.facts.single().text)
    }
}

