package com.shengjidaguai.goutoujunshi.domain

/** Local hints only. Confirmed user choices and contact ownership always take precedence. */
object ScenePredictor {
 fun predict(contact: RelationType?, text: String, evidence: List<EvidenceBlock>, manualRelation: RelationType? = null, manualGoal: Goal? = null): Scene {
  val input = (listOf(text) + evidence.flatMap { it.lines }.filter { it.speakerConfirmed }.map { it.text }).joinToString("\n")
  val relation = manualRelation ?: contact ?: when {
   listOf("领导", "老板", "汇报").any(input::contains) -> RelationType.BOSS
   listOf("伴侣", "老公", "老婆").any(input::contains) -> RelationType.PARTNER
   listOf("妈妈", "爸爸", "父母").any(input::contains) -> RelationType.PARENT
   listOf("调解", "双方争执").any(input::contains) -> RelationType.MEDIATION
   "朋友" in input -> RelationType.FRIEND
   else -> RelationType.ACQUAINTANCE
  }
  val goal = manualGoal ?: listOf("拒绝" to Goal.DECLINE, "道歉" to Goal.APOLOGIZE, "汇报" to Goal.REPORT, "求助" to Goal.ASK_HELP, "安慰" to Goal.COMFORT, "调解" to Goal.MEDIATE, "澄清" to Goal.CLARIFY).firstOrNull { it.first in input }?.second ?: Goal.REPLY
  return Scene(relation, goal)
 }
}
