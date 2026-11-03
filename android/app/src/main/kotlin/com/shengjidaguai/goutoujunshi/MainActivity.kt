package com.shengjidaguai.goutoujunshi
import com.shengjidaguai.goutoujunshi.counsel.safetyPresentation

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.shengjidaguai.goutoujunshi.importing.ScreenshotPhotoPicker
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.res.painterResource
import com.shengjidaguai.goutoujunshi.designsystem.R as DesignR
import androidx.compose.ui.res.stringResource
import androidx.navigation.compose.*
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shengjidaguai.goutoujunshi.counsel.CounselViewModel
import com.shengjidaguai.goutoujunshi.ui.*
import com.shengjidaguai.goutoujunshi.designsystem.JunshiTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val captureUiOwner=Any()
    private var pendingFollowup by mutableStateOf<Pair<String,String>?>(null)
    override fun onResume() {super.onResume();ViewModelProvider(this)[CounselViewModel::class.java].recheckReminderPermission()}
    override fun onStart() { super.onStart();com.shengjidaguai.goutoujunshi.floating.CaptureOwnUi.started(captureUiOwner) }
    override fun onStop() { com.shengjidaguai.goutoujunshi.floating.CaptureOwnUi.hidden(captureUiOwner);super.onStop() }
    override fun onDestroy() { com.shengjidaguai.goutoujunshi.floating.CaptureOwnUi.hidden(captureUiOwner);super.onDestroy() }
    override fun onCreate(savedInstanceState: Bundle?) {
        com.shengjidaguai.goutoujunshi.privacy.PrivacyRuntime.systemReturn(this)
        com.shengjidaguai.goutoujunshi.privacy.PrivacyRuntime.secure(this)
        super.onCreate(savedInstanceState)
        com.shengjidaguai.goutoujunshi.privacy.PrivacyRuntime.fullPrivateUiReturned(this,intent)
        val viewModel = ViewModelProvider(this)[CounselViewModel::class.java]
        pendingFollowup=com.shengjidaguai.goutoujunshi.followup.FollowupRouter.read(intent)
        viewModel.receiveShare(intent)
        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            LaunchedEffect(state.lockState,state.loading,pendingFollowup) {
                if(state.lockState.canDisplaySensitiveContent && !state.loading) pendingFollowup?.let {pendingFollowup=null;viewModel.openFollowup(it.first,it.second)}
            }
            val captureNotice by com.shengjidaguai.goutoujunshi.floating.CaptureOwnUi.notice.collectAsStateWithLifecycle()
            JunshiTheme(calm = state.safetyPresentation().calm) {
                androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.fillMaxSize()) {
                    if(captureNotice) Surface {
                        com.shengjidaguai.goutoujunshi.designsystem.JunshiColumn {
                            com.shengjidaguai.goutoujunshi.designsystem.BodyText(stringResource(R.string.capture_return_chat))
                        }
                    }
                    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.weight(1f)) { JunshiNavigation(viewModel) }
                }
            }
        }
    }
    override fun onNewIntent(intent: Intent) {
        com.shengjidaguai.goutoujunshi.privacy.PrivacyRuntime.systemReturn(this)
        super.onNewIntent(intent)
        com.shengjidaguai.goutoujunshi.privacy.PrivacyRuntime.fullPrivateUiReturned(this,intent)
        setIntent(intent)
        pendingFollowup=com.shengjidaguai.goutoujunshi.followup.FollowupRouter.read(intent)
        val vm=ViewModelProvider(this)[CounselViewModel::class.java]
        if(vm.state.value.lockState.canDisplaySensitiveContent) pendingFollowup?.let {pendingFollowup=null;vm.openFollowup(it.first,it.second)}
        vm.receiveShare(intent)
    }
    override fun onSaveInstanceState(outState: Bundle) {
        // The live intent has already been stripped of private share payloads.
        super.onSaveInstanceState(outState)
    }
}

@Composable
internal fun JunshiNavigation(viewModel: CounselViewModel) {
    val activity=androidx.activity.compose.LocalActivity.current
    val nav = rememberNavController()
    val current by nav.currentBackStackEntryAsState()
    val destinations = listOf("counsel" to R.string.nav_counsel, "contacts" to R.string.nav_contacts, "me" to R.string.nav_me)
    // Fixed registrations outlive import overlays and route changes. Each VM owns one in-flight slot.
    val images=rememberLauncherForActivityResult(ScreenshotPhotoPicker.contract()) { activity?.let(com.shengjidaguai.goutoujunshi.privacy.PrivacyRuntime::systemReturn);val handoff=viewModel.takePickerHandoff(com.shengjidaguai.goutoujunshi.importing.PickerKind.IMAGE);viewModel.importImages(it);activity?.let {owner->com.shengjidaguai.goutoujunshi.privacy.PrivacyRuntime.completeSystemTrip(owner,handoff)} }
    val files=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { activity?.let(com.shengjidaguai.goutoujunshi.privacy.PrivacyRuntime::systemReturn);val handoff=viewModel.takePickerHandoff(com.shengjidaguai.goutoujunshi.importing.PickerKind.FILE);viewModel.importFile(it);activity?.let {owner->com.shengjidaguai.goutoujunshi.privacy.PrivacyRuntime.completeSystemTrip(owner,handoff)} }
    val pendingImport by viewModel.imports.state.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    if (!state.lockState.canDisplaySensitiveContent) {
        val context=androidx.compose.ui.platform.LocalContext.current
        com.shengjidaguai.goutoujunshi.floating.FloatingLockGate({com.shengjidaguai.goutoujunshi.floating.FloatingRuntime.unlock(context)})
        return
    }
    if(!state.loading && state.conversation==null && state.error==null) {
        com.shengjidaguai.goutoujunshi.privacy.PrivacyWelcome {
            nav.navigate("counsel") {popUpTo(nav.graph.startDestinationId) {inclusive=true};launchSingleTop=true}
            viewModel.initialize()
        }
        return
    }
    LaunchedEffect(state.followupQuestion,state.followupId) {if(state.followupQuestion) nav.navigate("counsel") {launchSingleTop=true}}
    if (pendingImport != null) { ImportOverlay(viewModel); return }
    Scaffold(bottomBar = {
        NavigationBar {
            destinations.forEach { (route, label) ->
                NavigationBarItem(selected = current?.destination?.route == route,
                    onClick = { nav.navigate(route) { launchSingleTop = true; popUpTo(nav.graph.startDestinationId) { saveState = true }; restoreState = true } },
                    icon = { Icon(painterResource(when (route) {
                        "counsel" -> DesignR.drawable.ic_junshi_monochrome
                        "contacts" -> DesignR.drawable.ic_scene_group
                        else -> DesignR.drawable.ic_scene_home
                    }), null, androidx.compose.ui.Modifier.size(JunshiTheme.spacing.xl)) }, label = { Text(stringResource(label)) })
            }
        }
    }) { padding ->
        NavHost(nav, startDestination = "counsel", modifier = androidx.compose.ui.Modifier.padding(padding)) {
            composable("counsel") { Column { FloatingEntry(); androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.weight(1f)) { CounselScreen(viewModel,
                pickImages={ viewModel.launchImagePicker { viewModel.rememberPickerHandoff(com.shengjidaguai.goutoujunshi.importing.PickerKind.IMAGE,activity?.let(com.shengjidaguai.goutoujunshi.privacy.PrivacyRuntime::systemTrip));try {images.launch(ScreenshotPhotoPicker.request())} catch(failure:RuntimeException) {activity?.let(com.shengjidaguai.goutoujunshi.privacy.PrivacyRuntime::systemReturn);activity?.let {com.shengjidaguai.goutoujunshi.privacy.PrivacyRuntime.finishHandoff(it.application,it)};throw failure} } },
                pickFile={ viewModel.launchFilePicker { viewModel.rememberPickerHandoff(com.shengjidaguai.goutoujunshi.importing.PickerKind.FILE,activity?.let(com.shengjidaguai.goutoujunshi.privacy.PrivacyRuntime::systemTrip));try {files.launch(arrayOf("text/plain","application/json"))} catch(failure:RuntimeException) {activity?.let(com.shengjidaguai.goutoujunshi.privacy.PrivacyRuntime::systemReturn);activity?.let {com.shengjidaguai.goutoujunshi.privacy.PrivacyRuntime.finishHandoff(it.application,it)};throw failure} } }) { nav.navigate("me") { launchSingleTop = true } } } } }
            composable("contacts") { ContactsScreen(viewModel) { nav.navigate("counsel") { launchSingleTop = true } } }
            composable("me") { Column { FloatingEntry(); androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.weight(1f)) { SettingsScreen(viewModel) } } }
        }
    }
}

@Composable
private fun FloatingEntry() {
    val context = androidx.compose.ui.platform.LocalContext.current
    com.shengjidaguai.goutoujunshi.designsystem.SecondaryButton(stringResource(R.string.floating_title), {
        com.shengjidaguai.goutoujunshi.floating.FloatingRuntime.open(context)
    })
}
