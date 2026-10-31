package com.shengjidaguai.goutoujunshi.domain

import org.junit.Assert.*
import org.junit.Test

class OperationIntegrityTest {
    @Test fun retryRequiresEveryOperationFieldToRemainUnchanged() {
        val request = AnalysisRequest(requestId = "request", conversationId = "conversation", userText = "虚构输入")
        val ledger = AnalysisLedger().begin(request, "user")
        val changedOperations = listOf(
            request.copy(tone = Tone.WARM),
            request.copy(styleBias = 0.8f),
            request.copy(goal = Goal.CLARIFY),
            request.copy(evidence = listOf(EvidenceBlock(text = "不同证据"))),
            request.copy(contactId = "contact"),
            request.copy(styleContext = "不同风格"),
            request.copy(styleSamples = listOf(StyleSample(text = "样本"))),
            request.copy(contactContext = "联系人上下文"),
            request.copy(recentSummary = "摘要"),
            request.copy(redactionTerms = listOf("代号")),
            request.copy(speakersConfirmed = true),
            request.copy(sourceRevision = 1),
            request.copy(relationType = RelationType.BOSS),
            request.copy(emotionLevel = 8),
            request.copy(originalAnswer = StructuredAnswer(reply = listOf("原回复"))),
            request.copy(parentAnswerId = "parent"),
            request.copy(mode = RequestMode.REWRITE_REPLY),
            request.copy(inputRevision = 1),
            request.copy(conversationRevision = 1),
        )
        for (changed in changedOperations) {
            try {
                ledger.begin(changed, "retry-user")
                fail("Changed operation reused requestId: $changed")
            } catch (_: IllegalArgumentException) { }
            assertEquals(ledger, ledger.complete(changed, StructuredAnswer(reply = listOf("迟到结果")), "assistant", changed.inputRevision, changed.conversationRevision))
        }
        assertEquals(ledger, ledger.begin(request.copy(), "retry-user"))
    }

    @Test fun forgedNewRevisionCannotMakeOldCallbackCurrent() {
        val registered = AnalysisRequest(requestId = "r", conversationId = "c", inputRevision = 1, conversationRevision = 2)
        val ledger = AnalysisLedger().begin(registered, "user")
        val forged = registered.copy(inputRevision = 3, conversationRevision = 4)
        assertEquals(ledger, ledger.complete(forged, StructuredAnswer(reply = listOf("旧结果")), "assistant", 3, 4))
        val inconsistentRun = ledger.copy(runs = ledger.runs.map { it.copy(inputRevision = 9) })
        assertEquals(inconsistentRun, inconsistentRun.complete(registered, StructuredAnswer(), "assistant", 1, 2))
    }

    @Test fun nestedMutableInputsCannotMutateRegisteredOperation() {
        val evidence = mutableListOf(EvidenceBlock(text = "原证据"))
        val request = AnalysisRequest(requestId = "r", evidence = evidence)
        val ledger = AnalysisLedger().begin(request, "user")
        evidence += EvidenceBlock(text = "更改后的证据")
        try {
            ledger.begin(request, "retry-user")
            fail("Registration must be an immutable snapshot")
        } catch (_: IllegalArgumentException) { }
        assertEquals(ledger, ledger.complete(request, StructuredAnswer(), "assistant", 0, 0))
    }
}
