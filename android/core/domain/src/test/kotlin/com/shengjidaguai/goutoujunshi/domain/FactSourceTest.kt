package com.shengjidaguai.goutoujunshi.domain
import org.junit.Assert.*
import org.junit.Test
class FactSourceTest {
 private val lines=listOf(ChatLine(id="line-a",importId="import-a",contactId="a",text="虚构来源片段",speakerConfirmed=true))
 private val evidence=listOf(EvidenceBlock(id="import-a",lines=lines))
 @Test fun confirmedCurrentReferenceResolvesWithoutLookingOutsideEvidence() {
  assertEquals(lines,FactSourceResolver.resolve(listOf("line-a"),"a",3,3,evidence).lines)
 }
 @Test fun noReferenceIsUserStatementButUnknownOrCrossContactIsUnavailable() {
  assertTrue(FactSourceResolver.resolve(emptyList(),"a",3,3,evidence).userStatement)
  assertFalse(FactSourceResolver.resolve(listOf("unknown"),"a",3,3,evidence).available)
  assertFalse(FactSourceResolver.resolve(listOf("line-a"),"b",3,3,evidence).available)
  assertFalse(FactSourceResolver.resolve(listOf("line-a"),"a",4,3,evidence).available)
  assertFalse(FactSourceResolver.resolve(listOf("line-a"),"a",3,null,evidence).available)
  assertFalse(FactSourceResolver.resolve(listOf("line-a"),"a",3,3,evidence.map { it.copy(lines=lines.map { line->line.copy(speakerConfirmed=false) }) }).available)
 }
}
