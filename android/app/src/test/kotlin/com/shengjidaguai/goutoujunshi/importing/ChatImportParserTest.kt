package com.shengjidaguai.goutoujunshi.importing

import com.shengjidaguai.goutoujunshi.domain.Speaker
import java.io.ByteArrayInputStream
import org.junit.Assert.*
import org.junit.Test

class ChatImportParserTest {
    @Test fun committedSyntheticFixturesMatchSupportedAndUnknownContracts() {
        fun fixture(name: String) = checkNotNull(javaClass.classLoader!!.getResourceAsStream("imports/$name.synthetic.json")).use(ChatImportParser::read)
        val supported = ChatImportParser.parse(fixture("junshi-chat-v1"))
        assertFalse(supported.unknownJson); assertEquals(2, supported.rows.size)
        assertTrue(supported.rows.all { it.speaker == null })
        assertTrue(ChatImportParser.parse(fixture("unknown")).unknownJson)
        assertThrows(IllegalArgumentException::class.java) { ChatImportParser.parse(fixture("wrong-types")) }
    }

    @Test fun nicknameTimestampMultilineAndGroupArePreservedUnconfirmed() {
        val rows = ChatImportParser.parse("虚构甲 2026-10-31 09:20\n第一行\n第二行\n虚构乙：你好\n虚构丙：一起讨论").rows
        assertEquals(3, rows.size); assertEquals("第一行\n第二行", rows[0].text)
        assertEquals("2026-10-31 09:20", rows[0].sentAt)
        assertEquals(listOf("虚构甲", "虚构乙", "虚构丙"), rows.map { it.name })
        assertTrue(rows.all { it.speaker == null })
    }
    @Test fun versionOneHasNoImplicitSpeakerTrust() {
        val parsed = ChatImportParser.parse("""{"format":"junshi-chat","version":1,"messages":[{"name":"我","text":"虚构","speaker":"ME"}]}""")
        assertFalse(parsed.unknownJson); assertNull(parsed.rows.single().speaker)
    }
    @Test fun knownSchemaRejectsImplicitPrimitiveCoercionAndAllowsNullableMetadata() {
        for (fields in listOf("\"name\":123", "\"name\":{}", "\"sentAt\":true", "\"sentAt\":[]")) {
            assertThrows(IllegalArgumentException::class.java) {
                ChatImportParser.parse("""{"format":"junshi-chat","version":1,"messages":[{"text":"虚构",$fields}]}""")
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            ChatImportParser.parse("""{"format":"junshi-chat","version":"1","messages":[{"text":"虚构"}]}""")
        }
        val row = ChatImportParser.parse("""{"format":"junshi-chat","version":1,"messages":[{"name":null,"text":"虚构","sentAt":null}]}""").rows.single()
        assertEquals("", row.name); assertNull(row.sentAt)
    }

    @Test fun unknownAndMalformedJsonAreExplicitRawPreview() {
        listOf("{bad", "[]", "{\"format\":123}", "{\"version\":999}").forEach {
            val parsed = ChatImportParser.parse(it)
            assertTrue(parsed.unknownJson); assertEquals(it, parsed.rows.single().text); assertNull(parsed.rows.single().speaker)
        }
    }
    @Test fun strictUtf8BomAndByteLimit() {
        assertEquals("虚构", ChatImportParser.read(ByteArrayInputStream("\uFEFF虚构".toByteArray())))
        assertThrows(Exception::class.java) { ChatImportParser.read(ByteArrayInputStream(byteArrayOf(0xc3.toByte(), 0x28))) }
        assertThrows(IllegalArgumentException::class.java) { ChatImportParser.read(ByteArrayInputStream(ByteArray(MAX_IMPORT_BYTES + 1))) }
        assertThrows(IllegalArgumentException::class.java) { ChatImportParser.parse((1..2001).joinToString("\n") { "虚构：消息" }) }
    }
    @Test(timeout = 10000) fun nearLimitSingleMessageWithManyContinuationsIsAccumulatedLinearly() {
        val input = "虚构甲：开头\n" + "x\n".repeat(800_000) + "结尾"
        val rows = ChatImportParser.parse(input).rows
        assertEquals(1, rows.size)
        assertEquals("虚构甲", rows.single().name)
        assertTrue(rows.single().text.startsWith("开头\nx\n"))
        assertTrue(rows.single().text.endsWith("x\n结尾"))
        assertEquals(1_600_005, rows.single().text.length)
    }
    @Test fun continuationParsingChecksCancellationBeforeCompletingLargeInput() {
        var checks = 0
        val cancelled = java.util.concurrent.CancellationException("synthetic cancellation")
        val result = assertThrows(java.util.concurrent.CancellationException::class.java) {
            ChatImportParser.parse("虚构：开头\n" + "x\n".repeat(100_000)) {
                if (++checks == 20) throw cancelled
            }
        }
        assertSame(cancelled, result); assertEquals(20, checks)
    }

    @Test fun hashUsesConfirmedEditedContentWithoutSourceOrRandomIdentity() {
        val rows = listOf(ImportRow("虚构", "消息", speaker = Speaker.ME))
        assertEquals(ChatImportParser.hash(rows), ChatImportParser.hash(rows.toList()))
        assertNotEquals(ChatImportParser.hash(rows), ChatImportParser.hash(rows.map { it.copy(text = "更正") }))
    }
    @Test fun everySpeakerIncludingNamedOtherNeedsExplicitConfirmation() {
        val base = ImportPreview(rows = listOf(ImportRow("虚构甲", "消息")), target = ImportTarget(), rightsAccepted = true)
        assertFalse(base.canConfirm)
        assertTrue(base.copy(rows = base.rows.map { it.copy(speaker = Speaker.OTHER) }).canConfirm)
        assertFalse(base.copy(rows = listOf(ImportRow(text = "消息", speaker = Speaker.OTHER))).canConfirm)
        assertFalse(base.copy(rightsAccepted = false).canConfirm)
    }
}
