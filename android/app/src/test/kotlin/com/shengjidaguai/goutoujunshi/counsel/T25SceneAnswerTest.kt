package com.shengjidaguai.goutoujunshi.counsel
import com.shengjidaguai.goutoujunshi.domain.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
class T25SceneAnswerTest {
 private suspend fun await(p:()->Boolean) = withTimeout(3000) { while(!p()) yield() }
 private suspend fun completed(f: CounselControllerTest.Fixture): StructuredAnswer {
  f.controller.initialize(); f.controller.editDraft("向领导汇报虚构进度"); f.controller.analyze()
  await { f.provider.calls.size == 1 }
  f.provider.outputs[0].send(AnalysisChunk.Completed(StructuredAnswer(id="base", verdict="保留判断", reply=listOf("原话"), why=listOf("保留原因"), scene=Scene(RelationType.BOSS,Goal.REPORT))))
  await { f.controller.state.value.answer != null }; return f.controller.state.value.answer!!
 }
 @Test fun reloadedOldAnswerCannotGainNewSourceOrPrivacyAuthority() = runBlocking {
  for (privacyChanged in listOf(false,true)) {
   val f=CounselControllerTest.Fixture()
   val reloaded=CounselControllerTest.Fixture(f.persistence)
   try {
    f.controller.initialize();f.controller.createContact("虚构甲",RelationType.FRIEND)
    val base=completed(f);val contact=f.controller.state.value.currentContact!!
    if (privacyChanged) f.persistence.privacy[contact.id]=RedactionProfile(revision=1)
    else f.persistence.people.replaceAll { if(it.id==contact.id)it.copy(sourceRevision=1) else it }
    reloaded.controller.initialize();await { !reloaded.controller.state.value.loading }
    assertEquals(base,reloaded.controller.state.value.answer);assertFalse(reloaded.controller.state.value.hasCurrentAnswerSource())
    val origin=reloaded.controller.state.value.conversation!!.id
    reloaded.controller.rewrite(origin,base.id,Tone.FORMAL,0.7f);yield();assertFalse(reloaded.controller.state.value.streaming)
    reloaded.controller.feedback(origin,base.id,"过期编辑");yield();assertTrue(f.persistence.feedbackPlans.isEmpty())
    reloaded.controller.changeScene(origin,base.id,RelationType.PARENT,Goal.COMFORT,"high");yield()
    assertTrue(reloaded.provider.calls.isEmpty());assertEquals(base,reloaded.controller.state.value.answer)
   } finally { f.close();reloaded.close() }
  }
 }
 @Test fun formalFeedbackFailureIsHonestAndExplicitRetryIsIdempotent() = runBlocking {
  val f=CounselControllerTest.Fixture();try {
   f.controller.initialize();f.controller.createContact("虚构甲",RelationType.FRIEND)
   val base=completed(f);val origin=f.controller.state.value.conversation!!.id
   f.persistence.feedbackFailures=1
   f.controller.feedback(origin,base.id,"虚构用户修改");await { f.controller.state.value.error!=null }
   assertFalse(f.controller.state.value.feedbackSaved);assertTrue(f.persistence.feedbackPlans.isEmpty())
   // A fresh explicit save retries storage, never the provider.
   f.controller.retry();await { f.controller.state.value.feedbackSaved }
   f.controller.feedback(origin,base.id,"虚构用户修改");yield()
   val plan=f.persistence.feedbackPlans.values.single();assertTrue(plan.wasEdited);assertFalse(plan.learningAuthorized)
   assertEquals("assistant:${f.provider.calls[0].requestId}",plan.sourceAnswerId);assertEquals(1,f.provider.calls.size)
  } finally { f.close() }
 }
 @Test fun manualSceneNewFullAndPredictionNeverTakesManualChoice() = runBlocking {
  val f=CounselControllerTest.Fixture(); try {
   val base=completed(f); val origin=f.controller.state.value.conversation!!.id
   assertEquals(Goal.REPORT,f.provider.calls[0].goal)
   f.controller.changeScene(origin,base.id,RelationType.PARENT,Goal.COMFORT,"high")
   await { f.provider.calls.size==2 }; val r=f.provider.calls[1]
   assertEquals(RequestMode.FULL_ANALYSIS,r.mode); assertEquals(RelationType.PARENT,r.relationType);assertEquals(Goal.COMFORT,r.goal);assertEquals("high",r.formality)
   f.provider.outputs[1].send(AnalysisChunk.Completed(StructuredAnswer(id="next",reply=listOf("安慰"),scene=Scene(RelationType.BOSS,Goal.REPORT))))
   await { f.controller.state.value.answer!=null };assertEquals(RelationType.PARENT,f.controller.state.value.relationType)
  } finally { f.close() }
 }
 @Test fun rewriteMergesOnlyReplyAndAltAndRetryIsIdempotent() = runBlocking {
  val f=CounselControllerTest.Fixture();try {
   val base=completed(f);val origin=f.controller.state.value.conversation!!.id
   f.controller.rewrite(origin,base.id,Tone.FORMAL,0.8f);await { f.provider.calls.size==2 }
   val r=f.provider.calls[1];assertEquals(RequestMode.REWRITE_REPLY,r.mode);assertEquals(base.id,r.parentAnswerId);assertTrue(r.inputRevision > f.provider.calls[0].inputRevision)
   f.provider.outputs[1].send(AnalysisChunk.Failed(AnalysisError("NETWORK",retryable=true)));await { f.controller.state.value.error!=null }
   f.controller.retry();await { f.provider.calls.size==3 };assertEquals(r,f.provider.calls[2])
   f.provider.outputs[2].send(AnalysisChunk.Completed(StructuredAnswer(reply=listOf("新话"),verdict="不应覆盖",why=listOf("不应覆盖"))))
   await { f.controller.state.value.answer!=null };val answer=f.controller.state.value.answer!!
   assertEquals(base.verdict,answer.verdict);assertEquals(base.why,answer.why);assertEquals(listOf("新话"),answer.reply)
   assertEquals(2,f.controller.state.value.messages.count { it.role==MessageRole.USER });assertEquals(2,f.controller.state.value.messages.count { it.role==MessageRole.ASSISTANT })
   assertEquals(base,f.controller.state.value.messages.first { it.role==MessageRole.ASSISTANT }.answerJson?.let { kotlinx.serialization.json.Json.decodeFromString(StructuredAnswer.serializer(),it) })
  } finally { f.close() }
 }
 @Test fun crisisRewriteHidesAndCannotStartAnotherStrategy() = runBlocking {
  val f=CounselControllerTest.Fixture();try {
   val base=completed(f);val origin=f.controller.state.value.conversation!!.id
   f.controller.rewrite(origin,base.id,Tone.GENTLE,0.5f);await { f.provider.calls.size==2 }
   f.provider.outputs[1].send(AnalysisChunk.Completed(StructuredAnswer(safety=SafetyLevel.CRISIS,reply=listOf("不展示"))))
   await { !f.controller.state.value.streaming };assertEquals(SafetyLevel.CRISIS,f.controller.state.value.safety)
   f.controller.rewrite(origin,f.controller.state.value.answer!!.id,Tone.FORMAL,0.5f);f.controller.analyze();yield();assertEquals(2,f.provider.calls.size)
  } finally { f.close() }
 }
 @Test fun oldAnswerOrContactCannotRewriteOrRecordFeedback() = runBlocking {
  val f=CounselControllerTest.Fixture();try {
   val base=completed(f);val old=f.controller.state.value.conversation!!.id
   f.controller.newTemporary();f.controller.editDraft("虚构乙");f.controller.preparePreview();await { f.controller.state.value.preview!=null };val preview=f.controller.state.value.preview
   f.controller.rewrite(old,base.id,Tone.FORMAL,0.5f);f.controller.feedback(old,base.id,"我改的");yield()
   assertSame(preview,f.controller.state.value.preview);assertFalse(f.controller.state.value.feedbackSaved);assertEquals(1,f.provider.calls.size)
  } finally { f.close() }
 }
 @Test fun temporaryFeedbackRecordsExplicitChoiceWithoutWritingPersistence() = runBlocking {
  val f=CounselControllerTest.Fixture();try {
   val base=completed(f);val origin=f.controller.state.value.conversation!!.id
   f.controller.feedback(origin,base.id,"虚构用户自己的修改");await { f.controller.state.value.feedbackSaved }
   f.controller.feedback(origin,base.id,"虚构用户自己的修改");yield();assertTrue(f.persistence.saved.isEmpty())
   f.controller.newTemporary();assertFalse(f.controller.state.value.feedbackSaved)
  } finally { f.close() }
 }
}
