package com.shengjidaguai.goutoujunshi.floating

import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.PixelFormat
import android.os.PersistableBundle
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.shengjidaguai.goutoujunshi.data.network.*
import com.shengjidaguai.goutoujunshi.R
import com.shengjidaguai.goutoujunshi.counsel.*
import com.shengjidaguai.goutoujunshi.designsystem.*
import com.shengjidaguai.goutoujunshi.domain.SectionId
import com.shengjidaguai.goutoujunshi.domain.SafetyLevel
import com.shengjidaguai.goutoujunshi.domain.SafetyPolicy
import com.shengjidaguai.goutoujunshi.domain.SafeAliasRestorer
import kotlinx.coroutines.*

/** One visible instance owns its UI, callbacks and only the request it explicitly sent. */
internal class FloatingAnswerWindow(private val service: Service, private val scope: CoroutineScope) {
    private val controller=CounselRuntime.get(service.application)
    private val wm=service.getSystemService(WindowManager::class.java)
    private val owner=OverlayOwners()
    private val view=ComposeView(service)
    private val origin=controller.state.value.conversation?.id
    private val lock=controller.lockRevision
    private val ticket=FloatingRuntime.runtimeTicket
    private val copyCollapse=FloatingCopyCollapse(scope)
    private var closed=false
    val isClosed get()=closed
    private var ownedRequest:com.shengjidaguai.goutoujunshi.domain.AnalysisRequest?=null
    private var watcher:Job?=null
    private var ownUiWatcher:Job?=null
    private val screenHeight get()=service.resources.displayMetrics.heightPixels
    private val params=WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT,
        FloatingPanelGeometry.height(screenHeight,screenHeight),WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT).apply { gravity=Gravity.BOTTOM }
    init {
        view.setViewTreeLifecycleOwner(owner);view.setViewTreeViewModelStoreOwner(owner);view.setViewTreeSavedStateRegistryOwner(owner)
        view.setContent {
            val state by controller.state.collectAsState()
            JunshiTheme(calm=(state.emotion ?: 0)>=8) {
                FloatingPanelContent(state, onInteraction=copyCollapse::newInteraction,onClose={ close() },onCopy={ text -> copy(text) },
                    onPreview={ controller.preparePreview(origin,true) { request -> ownedRequest=request;if(closed) controller.stopFloating(origin,request) } },onSendSnapshot={ expected -> controller.analyzeFloating(origin,expected) { request -> ownedRequest=request;if(closed) controller.stopFloating(origin,request) } },
                    onTone={ tone -> state.answer?.let { controller.rewrite(origin,it.id,com.shengjidaguai.goutoujunshi.domain.Tone.valueOf(tone.name),state.styleBias,true) { request -> ownedRequest=request;if(closed) controller.stopFloating(origin,request) } } },
                    onMore={
                        val capture=state.floatingCapture
                        val continuation=capture!=null && ticket!=null && CounselRuntime.imports(service.application).prepareCaptureMore(origin,capture.id,ticket)
                        close(cancel=!continuation,preserveMore=continuation);FloatingRuntime.captureAction?.invoke()
                    },onFull=::openFull,
                    onSide={ state.floatingCapture?.let { receipt -> controller.switchFloatingSide(origin,receipt.id) { request -> ownedRequest=request;if(closed) controller.stopFloating(origin,request) } } },
                    onFocus=::focus,onSupplement={ text -> focus(false);val capture=state.floatingCapture;if(capture!=null) controller.editFloatingDraft(origin,capture.id,text) else controller.editDraft(text) },
                    onDrag={ amount -> params.height=FloatingPanelGeometry.height(screenHeight,params.height-amount.toInt());update() })
            }
        }
        wm.addView(view,params)
        ownUiWatcher=scope.launch { CaptureOwnUi.changes.collect { if(!CaptureOwnUi.snapshot().clear) close() } }
        watcher=scope.launch {
            controller.state.collect { state ->
                if(closed) return@collect
                if(origin!=state.conversation?.id || lock!=controller.lockRevision || ticket!=FloatingRuntime.runtimeTicket || !state.lockState.canDisplaySensitiveContent) close()

            }
        }
    }
    private fun update() { if(!closed) runCatching { wm.updateViewLayout(view,params) }.onFailure { close() } }
    private fun focus(enabled:Boolean) {
        if(closed) return
        params.flags=if(enabled) params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv() else params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        params.softInputMode=WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        update()
        if(enabled) view.requestFocus() else service.getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(view.windowToken,0)
    }
    private fun copy(text:String) {
        val current=controller.state.value
        if(closed || current.conversation?.id!=origin || controller.lockRevision!=lock || FloatingRuntime.runtimeTicket!=ticket || !current.hasCurrentAnswerSource() || current.streaming || current.error!=null || current.answer?.reply?.joinToString("\n")!=text) return
        val clip=ClipData.newPlainText(service.getString(R.string.app_name),text)
        clip.description.extras=PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE",true) }
        service.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        android.widget.Toast.makeText(service,R.string.t33_copied,android.widget.Toast.LENGTH_SHORT).show()
        val copiedEpoch=controller.importAuthority
        val copiedAnswer=current.answer?.id
        copyCollapse.copied({ !closed && controller.importAuthority==copiedEpoch && controller.state.value.answer?.id==copiedAnswer }) { close() }
    }
    private fun openFull() {
        if(closed) return
        // Main reads the exact same controller state. Close before its Activity becomes visible.
        close()
        service.startActivity(Intent(service,com.shengjidaguai.goutoujunshi.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    fun close(cancel:Boolean=true,preserveMore:Boolean=false) {
        if(closed) return
        closed=true;copyCollapse.close();watcher?.cancel();watcher=null;ownUiWatcher?.cancel();ownUiWatcher=null
        if(cancel) controller.stopFloating(origin,ownedRequest)
        if(!preserveMore) ticket?.let(controller::closeFloatingCapture)
        runCatching { service.getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(view.windowToken,0) }
        runCatching { wm.removeViewImmediate(view) };view.disposeComposition();owner.destroy()
    }
}

@Composable internal fun FloatingPanelContent(state:CounselState,onClose:()->Unit={},onCopy:(String)->Unit={},onPreview:()->Unit={},onSend:()->Unit={},
    onTone:(Tone)->Unit={},onMore:()->Unit={},onFull:()->Unit={},onFocus:(Boolean)->Unit={},onSupplement:(String)->Unit={},onDrag:(Float)->Unit={},onSide:()->Unit={},onInteraction:()->Unit={},onSendSnapshot:((com.shengjidaguai.goutoujunshi.domain.AnalysisRequest)->Unit)?=null) {
    val interaction by rememberUpdatedState(onInteraction)
    fun acted(action:()->Unit) { interaction();action() }
    var editing by remember { mutableStateOf(false) }
    val focusRequester=remember { FocusRequester() }
    val keyboard=LocalSoftwareKeyboardController.current
    LaunchedEffect(editing) { if(editing) { focusRequester.requestFocus();keyboard?.show() } }
    val field=remember(state.conversation?.id) { TextFieldState(state.draft) }
    val streamSafety=SafetyLevel.entries.filter { Regex("\\b${it.name}\\b",RegexOption.IGNORE_CASE).containsMatchIn(state.partial[SectionId.SAFETY].orEmpty()) }.maxByOrNull { it.ordinal } ?: SafetyLevel.NONE
    val safety=SafetyPolicy.evaluate(state.emotion,state.safety.upgrade(state.answer?.safety ?: SafetyLevel.NONE),streamSafety,state.lockState)
    val answer=state.answer
    // The button's closure carries the body it rendered, never the window's mutable owner.
    val renderedPreview=state.floatingPreviewRequest
    val current=state.hasCurrentAnswerSource() && !state.streaming && state.error==null
    Surface(Modifier.fillMaxSize().pointerInput(Unit) {
        awaitPointerEventScope {
            while(true) {
                val event=awaitPointerEvent(PointerEventPass.Initial)
                if(event.changes.any { it.pressed && !it.previousPressed }) interaction()
            }
        }
    }) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().pointerInput(Unit) { detectVerticalDragGestures { change,amount -> change.consume();onDrag(amount) } }) {
                SecondaryButton(stringResource(R.string.t33_resize),{ acted {} })
            }
            Box(Modifier.weight(1f)) {
                if(!state.lockState.canDisplaySensitiveContent) BodyText(stringResource(R.string.counsel_locked))
                else {
                    val partial=SafeAliasRestorer.displayPartial(state.partial[SectionId.REPLY].orEmpty(),state.preview)
                    FloatingAnswerPanel(AnswerUiState(verdict=answer?.verdict.orEmpty(),reply=answer?.reply ?: partial.split("+++").filter { it.isNotBlank() },
                        crisis=safety.showSafetyCard,calm=(state.emotion ?: 0)>=8,demo=state.answerDemo,isStreaming=!current,
                        relationLabel=stringResource(com.shengjidaguai.goutoujunshi.ui.relationLabel(state.relationType)),goalLabel=stringResource(com.shengjidaguai.goutoujunshi.ui.goalLabel(state.goal))),
                        state.currentContact?.alias ?: stringResource(R.string.temporary_title),
                        onCopy={ acted { if(current && safety.showOrdinaryStrategy) onCopy(answer?.reply?.joinToString("\n").orEmpty()) } },
                        onChangeTone={ tone -> acted { if(current && safety.showOrdinaryStrategy) onTone(tone) } },onCaptureMore={ acted(onMore) },onOpenInApp={ acted(onFull) },onClose={ acted(onClose) },
                        footer={
                            if(state.captureSourcePreferred) BodyText(stringResource(R.string.t33_capture_priority))
                            state.floatingCapture?.let { capture ->
                                if(capture.canSwapPhysicalSide) {
                                BodyText(stringResource(if(capture.mySide==com.shengjidaguai.goutoujunshi.domain.ocr.OcrSide.RIGHT) R.string.ocr_right_me else R.string.ocr_left_me))
                                BodyText(stringResource(R.string.t33_side_effect),secondary=true)
                                SecondaryButton(stringResource(if(capture.failed) R.string.t33_side_retry else R.string.t33_side_swap),{ acted(onSide) },enabled=!capture.busy && !editing)
                                } else BodyText(stringResource(R.string.t33_manual_speakers))
                            }
                            if(safety.showOrdinaryStrategy) {
                                if(editing) {
                                    ReplyEditField(field,stringResource(R.string.t33_supplement),modifier=Modifier.focusRequester(focusRequester))
                                    PrimaryButton(stringResource(R.string.t33_done),{ acted { editing=false;onSupplement(field.text.toString()) } })
                                } else SecondaryButton(stringResource(R.string.t33_supplement),{ acted { editing=true;onFocus(true) } },enabled=state.floatingCapture?.busy!=true && state.floatingCapture?.failed!=true)
                                if(state.preview!=null && !state.streaming) {
                                    BodyText(stringResource(R.string.preview_request))
                                    BodyText(stringResource(if(state.demo) R.string.preview_demo_notice else R.string.live_scope))
                                    BodyText(stringResource(R.string.redaction_honesty))
                                    BodyText(stringResource(R.string.redaction_count,state.preview.redactionCount))
                                    if(!state.demo) BodyText(runCatching { state.modelSettings.saved.connection().safeDestination() }.getOrDefault(""))
                                    if(state.floatingPreviewReady) PrimaryButton(stringResource(R.string.live_send),{ acted { if(onSendSnapshot!=null) renderedPreview?.let(onSendSnapshot) else onSend() } },enabled=state.floatingCapture?.busy!=true && state.floatingCapture?.failed!=true)
                                    else PrimaryButton(stringResource(R.string.preview_request),{ acted(onPreview) },enabled=state.floatingCapture?.busy!=true && state.floatingCapture?.failed!=true)
                                    BodyText(state.outboundBody ?: (state.preview.system+"\n\n"+state.preview.user))
                                } else if(!state.streaming) PrimaryButton(stringResource(R.string.preview_request),{ acted(onPreview) },enabled=state.floatingCapture?.busy!=true && state.floatingCapture?.failed!=true)
                            }
                            state.error?.let { BodyText(stringResource(if(it.code=="FLOATING_OPEN_FULL") R.string.t33_open_full_required else R.string.t33_request_error)) }
                        })
                }
            }
        }
    }
}
