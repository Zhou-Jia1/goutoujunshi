package com.shengjidaguai.goutoujunshi

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
    override fun onStart() { super.onStart();com.shengjidaguai.goutoujunshi.floating.CaptureOwnUi.started(captureUiOwner) }
    override fun onStop() { com.shengjidaguai.goutoujunshi.floating.CaptureOwnUi.hidden(captureUiOwner);super.onStop() }
    override fun onDestroy() { com.shengjidaguai.goutoujunshi.floating.CaptureOwnUi.hidden(captureUiOwner);super.onDestroy() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val viewModel = ViewModelProvider(this)[CounselViewModel::class.java]
        viewModel.receiveShare(intent)
        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            val captureNotice by com.shengjidaguai.goutoujunshi.floating.CaptureOwnUi.notice.collectAsStateWithLifecycle()
            JunshiTheme(calm = (state.emotion ?: 0) >= 8) {
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
        super.onNewIntent(intent)
        setIntent(intent)
        ViewModelProvider(this)[CounselViewModel::class.java].receiveShare(intent)
    }
    override fun onSaveInstanceState(outState: Bundle) {
        // The live intent has already been stripped of private share payloads.
        super.onSaveInstanceState(outState)
    }
}

@Composable
internal fun JunshiNavigation(viewModel: CounselViewModel) {
    val nav = rememberNavController()
    val current by nav.currentBackStackEntryAsState()
    val destinations = listOf("counsel" to R.string.nav_counsel, "contacts" to R.string.nav_contacts, "me" to R.string.nav_me)
    // Fixed registrations outlive import overlays and route changes. Each VM owns one in-flight slot.
    val images=rememberLauncherForActivityResult(ScreenshotPhotoPicker.contract()) { viewModel.importImages(it) }
    val files=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { viewModel.importFile(it) }
    val pendingImport by viewModel.imports.state.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    if (!state.lockState.canDisplaySensitiveContent) {
        val context=androidx.compose.ui.platform.LocalContext.current
        com.shengjidaguai.goutoujunshi.floating.FloatingLockGate({com.shengjidaguai.goutoujunshi.floating.FloatingRuntime.unlock(context)})
        return
    }
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
                pickImages={ viewModel.launchImagePicker { images.launch(ScreenshotPhotoPicker.request()) } },
                pickFile={ viewModel.launchFilePicker { files.launch(arrayOf("text/plain","application/json")) } }) { nav.navigate("me") { launchSingleTop = true } } } } }
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
