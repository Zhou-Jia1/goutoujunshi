package com.shengjidaguai.goutoujunshi.counsel

import com.shengjidaguai.goutoujunshi.data.*
import com.shengjidaguai.goutoujunshi.domain.*
import org.junit.Assert.*
import org.junit.Test

/** Full valid USER profile whose immutable envelope is exactly 3800 code points at revision99. */
internal fun budgetBoundaryEntries(owner: String): List<MemoryEntry> = (0..10).map { index ->
    MemoryEntry("manual:"+java.util.UUID.nameUUIDFromBytes("budget-$index".toByteArray()),owner,
        scope=MemoryScope.USER,subjectId=owner,field="USER",sourceType=MemorySource.USER_EXPLICIT,confidence=Confidence.HIGH,
        value=if(index<10) "虚".repeat(189)+"13800138000" else "只留本机的末条记忆".padEnd(54,'虚'),updatedAt=1793404800000)
}

class LongTermMemoryTest {
    private val entries=listOf(MemoryEntry("a","a",value="虚构甲",sourceType=MemorySource.USER_EXPLICIT),MemoryEntry("b","b",value="虚构乙",sourceType=MemorySource.USER_EXPLICIT),MemoryEntry("inactive","a",value="失效",active=false))
    @Test fun revisionNinetyNineToOneHundredBudgetDropMustRetainUnchangedActiveSource() {
        val owner="01234567-89ab-cdef-0123-456789abcdef"
        val rows=budgetBoundaryEntries(owner)
        val before=MemorySnapshot(ConsentState(enabled=true,consentVersion=1),WriteStamp(1,0,1,memoryRevision=99),rows)
        val original=MemoryPresentation.context(owner,before)
        assertEquals(3800,original.text.codePointCount(0,original.text.length))
        assertEquals(11,MemoryContextPolicy.dependencies(original).size)
        val after=before.copy(stamp=before.stamp!!.copy(memoryRevision=100),entries=rows+MemoryEntry("auto-append",owner,subjectId=owner,field="pattern",value="合法新增猜测",sourceType=MemorySource.ASSISTANT_INFERENCE,confidence=Confidence.LOW),verifiedInferenceIds=setOf("auto-append"))
        val current=MemoryPresentation.context(owner,after)
        assertFalse(rows.last().id in MemoryContextPolicy.dependencies(current))
        assertTrue(after.entries.contains(rows.last()));assertTrue(after.anchors.isEmpty())
        assertTrue(MemoryPresentation.sourcesRetained(original,MemoryPresentation.retention(owner,after)))
        assertNotEquals(original,current) // HTTP/write authority still requires the exact immutable context
        for(changed in listOf(
            after.copy(entries=after.entries.filterNot { it.id==rows.last().id }),
            after.copy(entries=after.entries.map { if(it.id==rows.last().id) it.copy(value="已修正") else it }),
            after.copy(entries=after.entries.map { if(it.id==rows.last().id) it.copy(active=false) else it }),
            after.copy(consent=after.consent.copy(paused=true)),
            after.copy(consent=after.consent.copy(enabled=false)),
            after.copy(consent=after.consent.copy(consentVersion=2)),
            after.copy(consent=after.consent.copy(policyVersion="2")))) {
            assertFalse(MemoryPresentation.sourcesRetained(original,MemoryPresentation.retention(owner,changed)))
        }
        assertFalse(MemoryPresentation.sourcesRetained(original,MemoryPresentation.retention("another-owner",after)))
    }
    @Test fun contextIsOwnerScopedAndPausedOrTemporaryCannotInject() {
        val snapshot=MemorySnapshot(ConsentState(enabled=true),WriteStamp(0,0,1,memoryRevision=2),entries)
        val context=MemoryPresentation.context("a",snapshot)
        assertTrue(context.text.contains("虚构甲"));assertFalse(context.text.contains("虚构乙"));assertFalse(context.text.contains("失效"))
        assertTrue(context.text.contains("推测不是事实"))
        assertEquals(OutboundDataContext(),MemoryPresentation.context(null,snapshot))
        assertEquals(OutboundDataContext(),MemoryPresentation.context("a",snapshot.copy(consent=snapshot.consent.copy(paused=true))))
        assertNotEquals(context,MemoryPresentation.context("a",snapshot.copy(stamp=snapshot.stamp!!.copy(memoryRevision=3))))
    }
    @Test fun appendRetainsOldCopyAndHistoryButEditingDeletingOrPausingDependenciesRevokesIt() {
        val one=MemorySnapshot(ConsentState(enabled=true,consentVersion=1),WriteStamp(1,0,1,memoryRevision=1),listOf(entries[0]))
        val original=MemoryPresentation.context("a",one)
        var next=one
        repeat(3) { index ->
            next=next.copy(stamp=next.stamp!!.copy(memoryRevision=index+2L),entries=next.entries+entries[0].copy(id="new-$index",value="新增$index"))
            assertTrue(MemoryPresentation.sourcesRetained(original,MemoryPresentation.retention("a",next)))
        }
        assertFalse(MemoryPresentation.sourcesRetained(original,MemoryPresentation.retention("a",next.copy(entries=next.entries.filterNot { it.id=="a" }))))
        assertFalse(MemoryPresentation.sourcesRetained(original,MemoryPresentation.retention("a",next.copy(entries=next.entries.map { if(it.id=="a") it.copy(value="已改") else it }))))
        assertFalse(MemoryPresentation.sourcesRetained(original,MemoryPresentation.retention("a",next.copy(consent=next.consent.copy(paused=true)))))
        assertTrue(MemoryPresentation.sourcesRetained(OutboundDataContext(),MemoryPresentation.retention("a",one)))
    }
    @Test fun quotaAnchorsAllowOnlyReadPermissionAndNeverEnterOutboundContext() {
        val source=MemorySnapshot(ConsentState(enabled=true,consentVersion=1),WriteStamp(1,0,1,memoryRevision=1),listOf(entries[0]))
        val original=MemoryPresentation.context("a",source)
        val anchor=mapOf(entries[0].id to MemoryContextPolicy.entryFingerprint(entries[0]))
        val next=source.copy(entries=emptyList(),anchors=anchor,stamp=source.stamp!!.copy(memoryRevision=2))
        val outgoing=MemoryPresentation.context("a",next)
        assertTrue(MemoryPresentation.sourcesRetained(original,MemoryPresentation.retention("a",next)))
        assertFalse(MemoryPresentation.sourcesRetained(original,MemoryPresentation.retention("a",next.copy(anchors=emptyMap()))))
        assertFalse(outgoing.text.contains(entries[0].value));assertFalse(outgoing.text.contains(anchor.values.single()))
        assertNotEquals(original,outgoing)
        assertFalse(MemoryPresentation.sourcesRetained(original,MemoryPresentation.retention("a",next.copy(consent=next.consent.copy(paused=true)))))
        assertFalse(MemoryPresentation.sourcesRetained(original,MemoryPresentation.retention("b",next)))
    }
    @Test fun contextSelectsWholeEntriesWithinFourThousandCodepointsWithoutTruncatingJsonOrTokens() {
        val rows=(1..80).map { entries[0].copy(id="row-$it",value="[昵称1]"+"虚构".repeat(90)) }
        val context=MemoryPresentation.context("a",MemorySnapshot(ConsentState(enabled=true),WriteStamp(0,0,1,memoryRevision=1),rows))
        assertTrue(context.text.codePointCount(0,context.text.length)<=4000)
        val dependencies=MemoryContextPolicy.dependencies(context)
        assertTrue(dependencies.isNotEmpty());assertTrue(dependencies.size<rows.size)
        assertEquals(dependencies.keys,rows.filter { it.id in dependencies }.map { it.id }.toSet())
        assertTrue(context.text.endsWith("}"))
    }
    @Test fun unverifiedLegacyInferenceIsVisibleButExcludedFromRealContextMapping() {
        val legacy=MemoryEntry("legacy","a",subjectId="a",field="pattern",value="旧推测不应发送")
        val explicit=entries[0]
        val snapshot=MemorySnapshot(ConsentState(enabled=true),WriteStamp(0,0,1,memoryRevision=0),listOf(legacy,explicit))
        assertEquals(2,snapshot.entries.size)
        val context=MemoryPresentation.context("a",snapshot)
        assertFalse(context.text.contains(legacy.value));assertTrue(context.text.contains(explicit.value))
        val legacyReceipt=MemoryContextPolicy.context("a",snapshot.consent,0,listOf(legacy))
        assertFalse(MemoryPresentation.sourcesRetained(legacyReceipt,MemoryPresentation.retention("a",snapshot)))
    }
    @Test fun invalidOrRejectedSuggestionsNeverTriggerConsent() {
        assertNull(MemoryPresentation.candidate(StructuredAnswer(memoryProposals=listOf(MemoryProposal(scope=MemoryScope.USER,value="虚构"))),MemorySnapshot()))
        val proposal=MemoryProposal(subjectId="ta",field="pattern",value="虚构假设")
        assertNull(MemoryPresentation.candidate(StructuredAnswer(memoryProposals=listOf(proposal)),MemorySnapshot(rejected=setOf(LongTermMemoryPolicy.contentFingerprint("","虚构假设")))))
    }
}
