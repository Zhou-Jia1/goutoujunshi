package com.shengjidaguai.goutoujunshi.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.flow.distinctUntilChanged
import com.shengjidaguai.goutoujunshi.domain.ocr.OcrSide
import androidx.compose.runtime.*
import androidx.compose.material3.Surface
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shengjidaguai.goutoujunshi.R
import com.shengjidaguai.goutoujunshi.counsel.CounselViewModel
import com.shengjidaguai.goutoujunshi.designsystem.*
import com.shengjidaguai.goutoujunshi.domain.*
import com.shengjidaguai.goutoujunshi.importing.*

@Composable fun ImportOverlay(viewModel: CounselViewModel) {
    val preview by viewModel.imports.state.collectAsStateWithLifecycle()
    val counsel by viewModel.state.collectAsStateWithLifecycle()
    preview?.let { p ->
        BackHandler(onBack = viewModel.imports::cancel)
        Surface { Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(JunshiTheme.spacing.l),
            verticalArrangement = Arrangement.spacedBy(JunshiTheme.spacing.m)) {
            JunshiTopBar(stringResource(R.string.import_title), viewModel.imports::cancel)
            Box(Modifier.weight(1f)) {
                ImportContent(p, counsel.contacts.filter { it.archivedAt == null }, viewModel.imports::edit, { target -> viewModel.imports.ifCurrent(p.id) { viewModel.imports.target(target) } },
                    { viewModel.imports.ifCurrent(p.id,viewModel.imports::acceptRights) }, { viewModel.imports.ifCurrent(p.id,viewModel.imports::confirm) }, { viewModel.imports.ifCurrent(p.id) {
                        if(p.retryNeedsCapture() && com.shengjidaguai.goutoujunshi.floating.FloatingRuntime.runtimeTicket==p.captureSession)
                            com.shengjidaguai.goutoujunshi.floating.FloatingRuntime.captureAction?.invoke()
                        else viewModel.imports.retry()
                    } },
                    recognize = { viewModel.imports.ifCurrent(p.id,viewModel.imports::recognize) },
                    side = { viewModel.imports.side(p.id,it) },
                    move = { id,delta -> viewModel.imports.moveImage(p.id,id,delta) },
                    editStable = { id,update -> viewModel.imports.editRow(p.id,id,update) },
                    onFloatingSource=viewModel.imports::floatingSource,onContinueCapture=viewModel.imports::continueCaptureMore,
                    recapture = { viewModel.imports.ifCurrent(p.id) {
                        if(p.captureSession!=null && com.shengjidaguai.goutoujunshi.floating.FloatingRuntime.runtimeTicket==p.captureSession)
                            com.shengjidaguai.goutoujunshi.floating.FloatingRuntime.captureAction?.invoke()
                    } })
            }
            SecondaryButton(stringResource(R.string.close), viewModel.imports::cancel)
        } }
    }
}

@Composable fun ImportContent(preview: ImportPreview, contacts: List<Contact>,
    edit: (Int, ImportRow) -> Unit = { _, _ -> }, target: (ImportTarget) -> Unit = {},
    rights: () -> Unit = {}, confirm: () -> Unit = {}, retry: () -> Unit = {},
    recognize: () -> Unit = {}, side:(OcrSide)->Unit = {}, move:(String,Int)->Unit = { _,_ -> },
    editStable: ((String,(ImportRow)->ImportRow)->Unit)? = null,
    recapture:()->Unit=retry,
    onFloatingSource:((FloatingSourceContext?)->Unit)?=null,
    onContinueCapture:()->Unit={},
) {
    var alias by remember(preview.id) { mutableStateOf("") }
    var sourceApp by remember(preview.id) { mutableStateOf(preview.floatingSource?.app.orEmpty()) }
    var sourceAccount by remember(preview.id) { mutableStateOf(preview.floatingSource?.account.orEmpty()) }
    val dragThreshold=with(LocalDensity.current) { (JunshiTheme.spacing.xl*2).toPx() }
    LazyColumn(Modifier.fillMaxWidth().testTag("import-preview"), verticalArrangement = Arrangement.spacedBy(JunshiTheme.spacing.m)) {
        item { BodyText(stringResource(R.string.import_confirmation)) }
        if(preview.captureSession!=null) {
            item {
                if(onFloatingSource!=null) {
                BodyText(stringResource(R.string.t33_identity_hint))
                ImportTextField(sourceApp,{ sourceApp=it;onFloatingSource(null) },stringResource(R.string.t33_source_app),singleLine=true)
                ImportTextField(sourceAccount,{ sourceAccount=it;onFloatingSource(null) },stringResource(R.string.t33_source_account),singleLine=true)
                SecondaryButton(stringResource(R.string.t33_confirm_source),{ if(sourceApp.isNotBlank() && sourceAccount.isNotBlank()) onFloatingSource(FloatingSourceContext(sourceApp.trim(),sourceAccount.trim())) })
                if(preview.floatingSource!=null) BodyText(stringResource(R.string.t33_source_confirmed))
                }
                BodyText(stringResource(R.string.capture_more_hint))
                SecondaryButton(stringResource(R.string.capture_retry),recapture,enabled=!preview.busy)
            }
        }
        if(preview.images.isNotEmpty()) {
            item { BodyText(stringResource(R.string.ocr_order)) }
            itemsIndexed(preview.imageIds, key={ _,id -> id }) { index,id ->
                var drag by remember(id) { mutableFloatStateOf(0f) }
                Column(Modifier.pointerInput(preview.id,id,preview.imageIds,preview.busy) {
                    detectDragGesturesAfterLongPress(onDragStart={ drag=0f }, onDragCancel={ drag=0f },
                        onDragEnd={ if(!preview.busy && kotlin.math.abs(drag)>dragThreshold) move(id,if(drag>0) 1 else -1) },
                        onDrag={ change,amount -> change.consume(); drag+=amount.y })
                }) {
                    BodyText(stringResource(R.string.ocr_image_number,preview.imageLabels[id] ?: (index+1)))
                    SecondaryButton(stringResource(R.string.ocr_up),{ move(id,-1) })
                    SecondaryButton(stringResource(R.string.ocr_down),{ move(id,1) })
                }
            }
            item {
                if(!preview.recognized) PrimaryButton(stringResource(R.string.ocr_start),recognize,enabled=!preview.busy,loading=preview.busy)
                else {
                    BodyText(stringResource(R.string.ocr_side))
                    SecondaryButton(stringResource(R.string.ocr_left_me),{ side(OcrSide.LEFT) })
                    SecondaryButton(stringResource(R.string.ocr_right_me),{ side(OcrSide.RIGHT) })
                    preview.mySide?.let { BodyText(stringResource(if(it==OcrSide.LEFT) R.string.ocr_left_me else R.string.ocr_right_me)) }
                }
            }
        }
        if(preview.captureSession!=null && preview.recognized && preview.rows.any { !it.excluded }) item {
            BodyText(stringResource(R.string.ocr_side))
            SecondaryButton(stringResource(R.string.ocr_left_me),{ side(OcrSide.LEFT) })
            SecondaryButton(stringResource(R.string.ocr_right_me),{ side(OcrSide.RIGHT) })
        }
        if (!preview.rightsAccepted) item {
            BodyText(stringResource(R.string.import_rights))
            SecondaryButton(stringResource(R.string.import_rights_accept), rights)
        }
        if (preview.unknownJson) item { BodyText(stringResource(R.string.import_unknown_json)) }
        preview.error?.let { error -> item {
            BodyText(stringResource(when (error) {
                "OCR_UNAVAILABLE" -> R.string.ocr_unavailable
                "OCR_TIMEOUT" -> R.string.ocr_timeout
                "OCR_LIMIT" -> R.string.ocr_limit
                "OCR_EMPTY" -> R.string.ocr_empty
                "OCR_READ" -> R.string.ocr_read
                "IMPORT_TOO_LARGE" -> R.string.import_too_large
                "IMPORT_SAVE" -> R.string.import_save_error
                "CAPTURE_HISTORY_REVIEW" -> R.string.t33_capture_history_review
                "IMPORT_INITIALIZE" -> R.string.import_initialize_error
                else -> R.string.import_read_error
            }))
            if (error == "IMPORT_SAVE" || error == "IMPORT_INITIALIZE" || error.startsWith("OCR_")) SecondaryButton(stringResource(if(error.startsWith("OCR_")) com.shengjidaguai.goutoujunshi.designsystem.R.string.ds_retry else R.string.import_retry), retry)
        } }
        item {
            ChoiceField(stringResource(R.string.import_target), preview.target?.let {
                it.newAlias ?: it.contact?.alias ?: stringResource(R.string.temporary_title)
            } ?: stringResource(R.string.import_choose_target),
                listOf(stringResource(R.string.temporary_title) to { target(ImportTarget()) }) +
                    contacts.map { it.alias to { target(ImportTarget(contact = it)) } })
            key(preview.id) { ImportTextField(alias, { alias = it }, stringResource(R.string.contact_alias_hint),singleLine=true) }
            SecondaryButton(stringResource(R.string.import_new_contact), { if (alias.isNotBlank()) target(ImportTarget(newAlias = alias)) })
        }
        if(preview.captureMoreAvailable) item {
            BodyText(stringResource(R.string.t33_continue_capture_hint))
            SecondaryButton(stringResource(R.string.t33_continue_capture),onContinueCapture,enabled=preview.captureMoreEligible && !preview.busy)
            if(preview.captureMoreNeedsContinuation) BodyText(stringResource(R.string.t33_more_owner_conflict))
            if(preview.captureContinuationId!=null) BodyText(stringResource(R.string.t33_capture_continued))
        }
        itemsIndexed(preview.rows, key={ _,row -> row.id }) { index, row ->
            fun change(update:(ImportRow)->ImportRow) { if(editStable!=null) editStable(row.id,update) else edit(index,update(row)) }
            var editing by remember(preview.id, row.id) { mutableStateOf(false) }
            JunshiColumn {
                if(row.excluded) BodyText(stringResource(R.string.ocr_excluded))
                if(row.ocrConfidence!=null) {
                    SecondaryButton(stringResource(if(row.excluded) R.string.ocr_restore else R.string.ocr_exclude), { change { it.copy(excluded=!it.excluded,exclusionOverride=!it.excluded) } })
                    if(row.ocrConfidence<.8f && !row.reviewed) {
                        BodyText(stringResource(R.string.ocr_low))
                        SecondaryButton(stringResource(R.string.ocr_reviewed), { change { it.copy(reviewed=true) } })
                    }
                }
                Box(Modifier.fillMaxWidth(), contentAlignment=if(row.side==OcrSide.RIGHT) Alignment.CenterEnd else Alignment.CenterStart) {
                    val label=row.name.ifBlank { if(row.side==null) stringResource(R.string.import_unconfirmed) else stringResource(when(row.speaker) {
                        Speaker.ME -> R.string.import_me; Speaker.THEM -> R.string.import_them; Speaker.OTHER -> R.string.import_other
                        null -> when(row.side) { OcrSide.LEFT -> R.string.ocr_suggested_left; OcrSide.RIGHT -> R.string.ocr_suggested_right; else -> R.string.import_unconfirmed }
                    }) }
                    ImportPreviewBubble(ChatLineModel(row.id, row.text,label,row.speaker==Speaker.ME,
                        lowConfidence=(row.ocrConfidence ?: 1f)<.8f), { editing = !editing })
                }
                if (editing) {
                ImportTextField(row.name, { value -> change { it.copy(name=value) } }, stringResource(R.string.import_nickname))
                row.sentAt?.let { BodyText(it, secondary = true) }
                ImportTextField(row.text, { value -> change { it.copy(text=value) } }, stringResource(R.string.import_message))
                }
                Box(Modifier.testTag("ocr-speaker-${row.id}")) { ChoiceField(stringResource(R.string.import_speaker), stringResource(when(row.speaker) {
                    Speaker.ME -> R.string.import_me; Speaker.THEM -> R.string.import_them
                    Speaker.OTHER -> R.string.import_other; null -> R.string.import_unconfirmed
                }), Speaker.entries.map { speaker -> stringResource(when(speaker) {
                    Speaker.ME -> R.string.import_me; Speaker.THEM -> R.string.import_them; Speaker.OTHER -> R.string.import_other
                }) to { change { it.copy(speaker=speaker) } } }) }
            }
        }
        item { PrimaryButton(stringResource(R.string.import_save), confirm, enabled = preview.canConfirm, loading = preview.busy) }
    }
}

/** State belongs only to this live composition, never rememberSaveable. */
@Composable private fun ImportTextField(value:String,onChange:(String)->Unit,label:String,singleLine:Boolean=false) {
    val field=remember { TextFieldState(value) }
    val currentValue by rememberUpdatedState(value)
    val currentChange by rememberUpdatedState(onChange)
    LaunchedEffect(field) { snapshotFlow { field.text.toString() }.distinctUntilChanged().collect { if(it!=currentValue) currentChange(it) } }
    ReplyEditField(field,label,singleLine=singleLine)
}
