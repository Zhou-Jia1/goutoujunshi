package com.shengjidaguai.goutoujunshi.counsel
import com.shengjidaguai.goutoujunshi.domain.*
import com.shengjidaguai.goutoujunshi.knowledge.LocalSafetyClassifier
import org.junit.Test
import org.junit.Assert.*
import java.io.File
class T52PracticeSafetyTest {
 @Test fun sharedDraftAssetCoversCrisisCategoriesAndNeutralControls() {
  val classifier=LocalSafetyClassifier(File(System.getProperty("junshi.repositoryRoot"),"android/core/knowledge/src/main/assets/crisis-keywords.txt").readText())
  for(text in listOf("我想死","我想自杀","我现在正在自残","他又家暴了","我昨天被殴打","有人跟踪我","他强迫我发生性关系","我要伤人","我要杀人")) assertEquals(text,SafetyLevel.CRISIS,classifier.classify(text))
  for(text in listOf("我想明天讨论计划","他没有回复消息","我们一起散步","先回应对方的情绪")) assertEquals(text,SafetyLevel.NONE,classifier.classify(text))
  assertTrue(runCatching {LocalSafetyClassifier("# no vocabulary")}.isFailure)
 }
 @Test fun jsonRejectsUnexpectedFieldsMultilineAndProtocolMarkers() {
  assertTrue(runCatching {PracticeProtocol.parse("""{"counterpartLine":"好","extra":"bad"}""",PracticeStage.OPENING)}.isFailure)
  assertTrue(runCatching {PracticeProtocol.parse("""{"counterpartLine":"[[SAFETY]]crisis"}""",PracticeStage.OPENING)}.isFailure)
  assertTrue(runCatching {PracticeProtocol.parse("""{"counterpartLine":"好","feedback":"先确认\n再安排","betterVersion":"我先确认"}""",PracticeStage.ROUND)}.isFailure)
 }
}
