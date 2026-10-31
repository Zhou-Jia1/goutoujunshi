package com.shengjidaguai.goutoujunshi
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.navigation.compose.*
import com.shengjidaguai.goutoujunshi.designsystem.JunshiTheme
import dagger.hilt.android.AndroidEntryPoint
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { JunshiTheme { JunshiNavigation() } }
    }
}
@Composable private fun JunshiNavigation() {
    val nav = rememberNavController()
    val current by nav.currentBackStackEntryAsState()
    val destinations = listOf("counsel" to R.string.nav_counsel, "contacts" to R.string.nav_contacts, "me" to R.string.nav_me)
    Scaffold(bottomBar = { NavigationBar { destinations.forEach { (route, label) ->
        NavigationBarItem(selected = current?.destination?.route == route,
            onClick = { nav.navigate(route) { launchSingleTop = true; popUpTo(nav.graph.startDestinationId) { saveState = true }; restoreState = true } },
            icon = { Text(stringResource(label)) }, label = { Text(stringResource(label)) })
    } } }) { padding -> NavHost(nav, startDestination = "counsel") {
        composable("counsel") { BootstrapMessage(stringResource(R.string.bootstrap_message), padding) }
        composable("contacts") { BootstrapMessage(stringResource(R.string.bootstrap_contacts), padding) }
        composable("me") { BootstrapMessage(stringResource(R.string.bootstrap_settings), padding) }
    } }
}
