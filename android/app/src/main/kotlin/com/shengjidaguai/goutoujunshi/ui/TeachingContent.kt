package com.shengjidaguai.goutoujunshi.ui

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shengjidaguai.goutoujunshi.R
import com.shengjidaguai.goutoujunshi.counsel.*
import com.shengjidaguai.goutoujunshi.designsystem.*
import com.shengjidaguai.goutoujunshi.domain.*

@Composable fun TeachingContent(view: TeachingView, toggle: () -> Unit, dismiss: (Boolean) -> Unit, saveScene:()->Unit = {}) {
    JunshiColumn(Modifier.testTag("teaching-content")) {
        view.tip?.let { tip ->
            TipCard(tip.name, tip.explanation, tip.example.takeIf { it.isNotBlank() }?.let {stringResource(R.string.tip_how_used,it)}, view.saved, toggle)
            if(view.saved && !view.sceneRecorded) SecondaryButton(stringResource(R.string.tip_save_scene),saveScene)
            BodyText(stringResource(R.string.tip_save_scope), secondary = true)
        }
        view.habit?.let { text -> HabitNotice(stringResource(R.string.habit_positive, text), { dismiss(false) }, { dismiss(true) }) }
    }
}

@Composable fun TipsScreen(vm: CounselViewModel, back: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.openTips() }
    TipsContent(state.savedTips, back) { id->state.tipsReceipt?.let {vm.removeTip(id,it)} }
}

@Composable fun TipsContent(tips: List<Tip>, back: () -> Unit, remove:(String)->Unit = {}) {
    LazyColumn(Modifier.fillMaxSize().testTag("tips-book"),contentPadding=PaddingValues(JunshiTheme.spacing.l),verticalArrangement=Arrangement.spacedBy(JunshiTheme.spacing.m)) {
        item { JunshiTopBar(stringResource(R.string.tip_book)); SecondaryButton(stringResource(R.string.tip_back), back); BodyText(stringResource(R.string.tip_save_scope),secondary=true) }
        if(tips.isEmpty()) item { BodyText(stringResource(R.string.tip_empty)) }
        tips.flatMap { tip -> tip.scenes.ifEmpty { setOf(TipScene()) }.map { it.group to tip } }.groupBy { it.first }.forEach { (group, entries) ->
            item { BodyText(stringResource(when(group) {RelationGroup.WORK->R.string.tip_scene_work;RelationGroup.INTIMATE->R.string.tip_scene_intimate;RelationGroup.FAMILY->R.string.tip_scene_family;RelationGroup.SOCIAL->R.string.tip_scene_social;RelationGroup.MEDIATION->R.string.tip_scene_mediation;else->R.string.tip_scene_other})) }
            entries.map { it.second }.distinctBy {it.id}.forEach { tip -> item {
                JunshiColumn { BodyText(tip.name);BodyText(tip.explanation);if(tip.example.isNotBlank()) BodyText(tip.example,secondary=true);
                    tip.scenes.filter {it.group==group}.forEach { BodyText(stringResource(goalLabel(it.goal)),secondary=true) };
                    SecondaryButton(stringResource(com.shengjidaguai.goutoujunshi.designsystem.R.string.ds_unsave_tip),{remove(tip.id)}) }
            } }
        }
    }
}
