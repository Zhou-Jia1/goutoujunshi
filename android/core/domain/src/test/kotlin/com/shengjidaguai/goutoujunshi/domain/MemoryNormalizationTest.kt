package com.shengjidaguai.goutoujunshi.domain

import org.junit.Assert.*
import org.junit.Test

class MemoryNormalizationTest {
    private val consent = ConsentState(enabled = true, consentVersion = 1)
    private val entry = MemoryEntry(id = "m", contactId = "c", subjectId = "self", field = "f", value = "x")

    @Test fun plannedWritePersistsOnlyNormalizedValidatedFields() {
        val input = entry.copy(subjectId = " self ", field = " f ", value = "x" + " ".repeat(1000), sourceRef = " ref ", occurredAt = " 2026-10-28 ")
        val stored = MemoryPolicy.planWrite(input, consent, 1, "c", emptyList()).single()
        assertEquals("self", stored.subjectId)
        assertEquals("f", stored.field)
        assertEquals("x", stored.value)
        assertEquals("ref", stored.sourceRef)
        assertEquals("2026-10-28", stored.occurredAt)
        assertEquals(stored, MemoryPolicy.validateWrite(input, consent, 1, "c", emptyList()))
        assertEquals(stored, MemoryPolicy.validateEntry(input))
    }

    @Test fun stripWhitespaceNeverLeavesHiddenControlsInStoredFields() {
        val normalized = MemoryPolicy.planWrite(entry.copy(value = "\r\n\t\u0085 x \r\n"), consent, 1, "c", emptyList()).single()
        assertEquals("x", normalized.value)
        assertRejected { MemoryPolicy.planWrite(entry.copy(value = "x\ry"), consent, 1, "c", emptyList()) }
        assertRejected { MemoryPolicy.planWrite(entry.copy(value = "\u0000x"), consent, 1, "c", emptyList()) }
        assertRejected { MemoryPolicy.planWrite(entry.copy(value = " \n\t "), consent, 1, "c", emptyList()) }
        assertRejected { MemoryPolicy.planWrite(entry.copy(value = " " + "😀".repeat(201) + " "), consent, 1, "c", emptyList()) }
        assertRejected { MemoryPolicy.planWrite(entry.copy(field = " " + "f".repeat(65) + " "), consent, 1, "c", emptyList()) }
        assertRejected { MemoryPolicy.planWrite(entry.copy(sourceRef = " " + "r".repeat(201) + " "), consent, 1, "c", emptyList()) }
    }

    @Test fun normalizedSubjectCannotBypassStableScopeQuota() {
        val full = (0 until 30).map { entry.copy(id = "$it", scope = MemoryScope.USER, sourceType = MemorySource.USER_EXPLICIT) }
        val newEntry = entry.copy(id = "new", subjectId = " self ", scope = MemoryScope.USER, sourceType = MemorySource.USER_EXPLICIT)
        assertRejected { MemoryPolicy.planWrite(newEntry, consent, 1, "c", full) }
    }

    private fun assertRejected(block: () -> Unit) {
        try { block(); fail("expected rejection") } catch (_: MemoryWriteRejected) { }
    }
}
