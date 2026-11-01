package com.shengjidaguai.goutoujunshi.domain

import org.junit.Assert.*
import org.junit.Test

class LongTermMemoryPolicyTest {
    @Test fun wrongContentUsesCanonicalIdentity() {
        assertEquals(LongTermMemoryPolicy.contentKey("  喜欢 Ａ  "), LongTermMemoryPolicy.contentKey("喜欢 a"))
    }
    @Test fun modelsCannotPromoteClaimsToFacts() {
        assertFalse(LongTermMemoryPolicy.worthRemembering(MemoryProposal(scope = MemoryScope.USER, subjectId="me", field="name", value="虚构")))
        assertTrue(LongTermMemoryPolicy.worthRemembering(MemoryProposal(subjectId="ta", field="pattern", value="可能周末忙")))
    }
    @Test fun lowestConfidenceIsEvictedBeforeNewerHighConfidence() {
        val entries = (0..4).map { MemoryEntry(id="$it", contactId="a", subjectId="ta", field="x", value="$it", confidence=if(it==4) Confidence.LOW else Confidence.HIGH, updatedAt=it.toLong()) }
        val next = entries[0].copy(id="new", confidence=Confidence.LOW, updatedAt=10)
        assertFalse(LongTermMemoryPolicy.planInference(next, entries).any { it.id=="4" })
    }
}
