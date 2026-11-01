package com.shengjidaguai.goutoujunshi.domain
import org.junit.Assert.*
import org.junit.Test
class ScenePredictorTest {
 @Test fun t27RetryHashRemainsValidOnlyWithUnchangedSceneDefaults() {
  val request=AnalysisRequest(requestId="legacy",userText="虚构输入",conversationId="conversation")
  val begun=AnalysisLedger().begin(request,"user")
  val fields=kotlinx.serialization.json.Json { encodeDefaults=true }.encodeToJsonElement(AnalysisRequest.serializer(),request) as kotlinx.serialization.json.JsonObject
  val old=kotlinx.serialization.json.JsonObject(fields.filterKeys { it !in setOf("formality","manualRelation","manualGoal","manualFormality") }).toString()
  val hash=java.security.MessageDigest.getInstance("SHA-256").digest(old.toByteArray()).joinToString("") { "%02x".format(it) }
  val ledger=begun.copy(runs=begun.runs.map { it.copy(operationFingerprint=hash) })
  assertEquals(1,ledger.begin(request,"ignored-on-retry").messages.size)
  assertTrue(runCatching { ledger.begin(request.copy(formality="high"),"ignored") }.isFailure)
  assertTrue(runCatching { ledger.begin(request.copy(goal=Goal.DECLINE),"ignored") }.isFailure)
 }
 @Test fun predictsFromConfirmedEvidence() {
  val scene = ScenePredictor.predict(null, "", listOf(EvidenceBlock(lines=listOf(ChatLine(text="向领导汇报进度", speakerConfirmed=true)))))
  assertEquals(RelationType.BOSS, scene.relationType); assertEquals(Goal.REPORT, scene.goal)
 }
 @Test fun ignoresUnconfirmedAndPreservesContact() {
  assertEquals(RelationType.FRIEND, ScenePredictor.predict(RelationType.FRIEND, "领导", emptyList()).relationType)
  assertEquals(Goal.REPLY, ScenePredictor.predict(null,"",listOf(EvidenceBlock(lines=listOf(ChatLine(text="拒绝"))))).goal)
 }
 @Test fun explicitSelectionWins() {
  val predicted = ScenePredictor.predict(null,"向领导汇报",emptyList(), RelationType.PARENT, Goal.COMFORT)
  assertEquals(RelationType.PARENT,predicted.relationType); assertEquals(Goal.COMFORT,predicted.goal)
 }
}
