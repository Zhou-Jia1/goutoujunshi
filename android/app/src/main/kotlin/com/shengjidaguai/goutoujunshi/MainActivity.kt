package com.shengjidaguai.goutoujunshi

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val viewModel = ViewModelProvider(this)[CounselViewModel::class.java]
        viewModel.receiveShare(intent)
        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            JunshiTheme(calm = (state.emotion ?: 0) >= 8) { JunshiNavigation(viewModel) }
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
private fun JunshiNavigation(viewModel: CounselViewModel) {
    val nav = rememberNavController()
    val current by nav.currentBackStackEntryAsState()
    val destinations = listOf("counsel" to R.string.nav_counsel, "contacts" to R.string.nav_contacts, "me" to R.string.nav_me)
    val pendingImport by viewModel.imports.state.collectAsStateWithLifecycle()
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
            composable("counsel") { CounselScreen(viewModel) }
            composable("contacts") { ContactsScreen(viewModel) { nav.navigate("counsel") { launchSingleTop = true } } }
            composable("me") { SettingsScreen(viewModel) }
        }
    }
}
