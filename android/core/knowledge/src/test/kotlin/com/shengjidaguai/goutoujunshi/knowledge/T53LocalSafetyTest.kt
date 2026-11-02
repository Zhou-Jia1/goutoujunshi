package com.shengjidaguai.goutoujunshi.knowledge
import com.shengjidaguai.goutoujunshi.domain.SafetyLevel
import java.io.File
import org.junit.Assert.*
import org.junit.Test
class T53LocalSafetyTest {
 @Test fun shippedAssetsAreSharedAndCrisisWins() {
  val root=File(System.getProperty("junshi.repositoryRoot"),"android/core/knowledge/src/main/assets")
  val classifier=CachedLocalSafetyClassifier({root.resolve("crisis-keywords.txt").readText()},{root.resolve("calm-keywords.txt").readText()})
  assertEquals(SafetyLevel.NONE,classifier.classify("合成普通文本"))
  assertEquals(SafetyLevel.CAUTION,classifier.classify("受不了了"))
  assertEquals(SafetyLevel.CRISIS,classifier.classify("受不了了，想死"))
 }
 @Test fun cachesSuccessAndEitherFailureOnlyOnce() {
  var crisis=0;var calm=0
  val good=CachedLocalSafetyClassifier({crisis++;"合成危机"},{calm++;"合成高情绪"})
  repeat(3) {assertEquals(SafetyLevel.CAUTION,good.classify("合成高情绪"))}
  assertEquals(1,crisis);assertEquals(1,calm)
  var failures=0
  val failed=CachedLocalSafetyClassifier({"合成危机"},{failures++;error("synthetic missing calm asset")})
  repeat(3) {assertTrue(runCatching {failed.classify("普通文本")}.isFailure)}
  assertEquals(1,failures)
 }
}
