package com.shengjidaguai.goutoujunshi.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.*
import androidx.compose.material3.Surface
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Modifier
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
                ImportContent(p, counsel.contacts.filter { it.archivedAt == null }, viewModel.imports::edit, viewModel.imports::target,
                    viewModel.imports::acceptRights, viewModel.imports::confirm, viewModel.imports::retry)
            }
            SecondaryButton(stringResource(R.string.close), viewModel.imports::cancel)
        } }
    }
}

@Composable fun ImportContent(preview: ImportPreview, contacts: List<Contact>,
    edit: (Int, ImportRow) -> Unit = { _, _ -> }, target: (ImportTarget) -> Unit = {},
    rights: () -> Unit = {}, confirm: () -> Unit = {}, retry: () -> Unit = {},
) {
    var alias by remember(preview.id) { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxWidth().testTag("import-preview"), verticalArrangement = Arrangement.spacedBy(JunshiTheme.spacing.m)) {
        item { BodyText(stringResource(R.string.import_confirmation)) }
        if (!preview.rightsAccepted) item {
            BodyText(stringResource(R.string.import_rights))
            SecondaryButton(stringResource(R.string.import_rights_accept), rights)
        }
        if (preview.unknownJson) item { BodyText(stringResource(R.string.import_unknown_json)) }
        preview.error?.let { error -> item {
            BodyText(stringResource(when (error) {
                "OCR_PENDING" -> R.string.import_ocr_pending
                "IMPORT_TOO_LARGE" -> R.string.import_too_large
                "IMPORT_SAVE" -> R.string.import_save_error
                "IMPORT_INITIALIZE" -> R.string.import_initialize_error
                else -> R.string.import_read_error
            }))
            if (error == "IMPORT_SAVE" || error == "IMPORT_INITIALIZE") SecondaryButton(stringResource(R.string.import_retry), retry)
        } }
        item {
            ChoiceField(stringResource(R.string.import_target), preview.target?.let {
                it.newAlias ?: it.contact?.alias ?: stringResource(R.string.temporary_title)
            } ?: stringResource(R.string.import_choose_target),
                listOf(stringResource(R.string.temporary_title) to { target(ImportTarget()) }) +
                    contacts.map { it.alias to { target(ImportTarget(contact = it)) } })
            FormTextField(alias, { alias = it }, stringResource(R.string.contact_alias_hint))
            SecondaryButton(stringResource(R.string.import_new_contact), { if (alias.isNotBlank()) target(ImportTarget(newAlias = alias)) })
        }
        itemsIndexed(preview.rows) { index, row ->
            var editing by remember(preview.id, index) { mutableStateOf(false) }
            JunshiColumn {
                ImportPreviewBubble(ChatLineModel("${preview.id}:$index", row.text,
                    row.name.ifBlank { stringResource(R.string.import_unconfirmed) }, row.speaker == Speaker.ME), { editing = !editing })
                if (editing) {
                FormTextField(row.name, { edit(index, row.copy(name = it)) }, stringResource(R.string.import_nickname))
                row.sentAt?.let { BodyText(it, secondary = true) }
                FormTextField(row.text, { edit(index, row.copy(text = it)) }, stringResource(R.string.import_message))
                }
                ChoiceField(stringResource(R.string.import_speaker), stringResource(when(row.speaker) {
                    Speaker.ME -> R.string.import_me; Speaker.THEM -> R.string.import_them
                    Speaker.OTHER -> R.string.import_other; null -> R.string.import_unconfirmed
                }), Speaker.entries.map { speaker -> stringResource(when(speaker) {
                    Speaker.ME -> R.string.import_me; Speaker.THEM -> R.string.import_them; Speaker.OTHER -> R.string.import_other
                }) to { edit(index, row.copy(speaker = speaker)) } })
            }
        }
        item { PrimaryButton(stringResource(R.string.import_save), confirm, enabled = preview.canConfirm, loading = preview.busy) }
    }
}
