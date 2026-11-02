package com.shengjidaguai.goutoujunshi.domain

import org.junit.Assert.*
import org.junit.Test

class ContinuousAnalysisTest {
    private fun evidence(n: Int) = listOf(EvidenceBlock("a", "PASTE", (1..n).map {
        ChatLine("a:$it", "a", it, Speaker.ME, "虚构消息".repeat(20), speakerConfirmed = true, contactId = "c")
    }))
    @Test fun thousandMessagesKeepIdentityAndBoundBothSteps() {
        val input = evidence(1000)
        val plan = ContinuousAnalysis.plan(input)
        assertTrue(plan.earlier.isNotEmpty())
        assertTrue(plan.recent.sumOf { ContinuousAnalysis.cost(it) } <= 12000)
        assertTrue(plan.summarySelection.sumOf { ContinuousAnalysis.cost(it) } <= 12000)
        assertEquals(1000, input.single().lines.size)
        assertTrue(plan.summarySelection.size < plan.earlier.size)
        assertFalse(ContinuousAnalysis.plan(input, overlay = true).needsSummary)
    }
    @Test fun historyAllowsAppendButRejectsModificationDeletionAndOtherContact() {
        val old = AnalysisRequest(contactId = "c", evidence = evidence(2))
        assertTrue(ContinuousAnalysis.sourcesRetained(old, "c", evidence(2) + EvidenceBlock("new", "PASTE", listOf(ChatLine("new:1", "new", text="新增", speakerConfirmed=true)))))
        assertFalse(ContinuousAnalysis.sourcesRetained(old, "other", evidence(3)))
        assertFalse(ContinuousAnalysis.sourcesRetained(old, "c", evidence(1)))
        assertFalse(ContinuousAnalysis.sourcesRetained(old, "c", evidence(2).map { it.copy(source = "FILE") }))
    }
    @Test fun interleavedImportsKeepCanonicalIdsAndChronologicalNumbers() {
        val a = ChatLine("original-a", "batch-a", text="虚构早期", sentAt="2026-10-01T12:00:00+08:00", speakerConfirmed=true)
        val b = ChatLine("original-b", "batch-b", text="虚构稍晚", sentAt="2026-10-01T05:00:00Z", speakerConfirmed=true)
        val blocks = listOf(EvidenceBlock("batch-b","PASTE",listOf(b)),EvidenceBlock("batch-a","PASTE",listOf(a)))
        assertEquals(listOf("original-a","original-b"),ContinuousAnalysis.ordered(blocks).map { it.id })
        assertEquals(listOf("original-b","original-a"),blocks.flatMap { it.lines }.map { it.id })
        assertTrue(ContinuousAnalysis.validSummary("[original-a] 联系方式为[手机号1]",listOf(a)))
        assertFalse(ContinuousAnalysis.validSummary("[手机号1] 缺少来源",listOf(a)))
    }
    @Test fun denseScopedTokensFailBudgetWithoutCuttingIdentityMarkers() {
        val line = evidence(1).single().lines.single()
        val raw = "[${line.id}] " + "[手机号1]".repeat(40)
        assertTrue(ContinuousAnalysis.validSummary(raw,listOf(line)))
        val scoped = ContinuousAnalysis.scopeHistoricalTokens(raw,"早期节选#${"a".repeat(64)}")
        assertTrue(scoped.codePointCount(0,scoped.length)>1000)
        assertFalse(ContinuousAnalysis.validSummary(scoped,listOf(line)))
        val history = ContinuousAnalysis.historyExcerpt("历史"+scoped,100)
        assertEquals(history.count { it=='〈' }, history.count { it=='〉' })
        assertTrue(history.codePointCount(0,history.length)<=100)
    }
    @Test fun timelineRejectsUnknownRefsAndMissingNumberedLines() {
        val lines = evidence(2).single().lines
        assertTrue(ContinuousAnalysis.validSummary("[a:1] 虚构事件", lines))
        assertFalse(ContinuousAnalysis.validSummary("[other:1] 不可验证", lines))
        assertFalse(ContinuousAnalysis.validSummary("没有编号", lines))
    }
}
