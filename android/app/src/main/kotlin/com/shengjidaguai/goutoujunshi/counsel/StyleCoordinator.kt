package com.shengjidaguai.goutoujunshi.counsel

import com.shengjidaguai.goutoujunshi.data.*
import com.shengjidaguai.goutoujunshi.domain.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.UUID

/** RAM display receipts and quiz answers belong to the controller mailbox, never SavedState. */
data class StyleUiState(val snapshot:StyleManagementSnapshot?=null,val receipt:String?=null,val loading:Boolean=false,val failed:Boolean=false,val modelRequired:Boolean=false,val temporaryQuiz:List<String> = emptyList(),val previewGroup:RelationGroup?=null)
internal class StyleCoordinator {
    val state=MutableStateFlow(StyleUiState())
    var open=false
    private var quizSession:String?=null
    private var quizAnswers:List<String> = emptyList()
    fun rememberQuiz(session:String,answers:List<String>) {quizSession=session;quizAnswers=answers.toList()}
    fun quizFor(session:String)=if(quizSession==session) quizAnswers else emptyList()
    fun close() {open=false;state.value=StyleUiState()}
    fun display(snapshot:StyleManagementSnapshot) {state.value=StyleUiState(snapshot,UUID.randomUUID().toString(),temporaryQuiz=state.value.temporaryQuiz)}
    fun revoke() {close();quizSession=null;quizAnswers=emptyList()}
    fun current(receipt:String)=open && state.value.receipt==receipt
}

/** The explicit management receipt retains real, locally qualified provenance for group-only
 * description projection. Never use this lease to construct contact facts or rewrite owner IDs. */
internal data class StyleDescriptionLease(val group:RelationGroup,val snapshot:StyleManagementSnapshot)
