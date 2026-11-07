package com.dzienisz.zureqshopper.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.NavType
import com.dzienisz.zureqshopper.AppModel
import com.dzienisz.zureqshopper.R
import com.dzienisz.zureqshopper.api.Product
import com.dzienisz.zureqshopper.api.ZureqClient
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import java.net.URLDecoder
import java.net.URLEncoder

private enum class Tab(val route: String, val label: Int, val icon: ImageVector) {
    Search("search", R.string.tab_search, Icons.Filled.Search),
    Settings("settings", R.string.tab_settings, Icons.Filled.Settings),
}

@Composable
fun RootScreen(model: AppModel) {
    val state by model.state.collectAsStateWithLifecycle()
    if (!state.hasKey) {
        WelcomeScreen(model)
        return
    }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { model.refreshUsage() }
    LifecycleResumeEffect(Unit) {
        scope.launch { model.refreshUsage() }
        onPauseOrDispose {}
    }

    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val searchModel: SearchViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = SearchViewModel.factory(model),
    )

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { tab ->
                    val selected = backStack?.destination?.hierarchy?.any { it.route == tab.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(tab.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(stringResource(tab.label)) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(navController, startDestination = Tab.Search.route, modifier = Modifier.padding(padding)) {
            composable(Tab.Search.route) {
                SearchScreen(model, searchModel) { product ->
                    val encoded = URLEncoder.encode(ZureqClient.json.encodeToString(Product.serializer(), product), "UTF-8")
                    navController.navigate("product/$encoded")
                }
            }
            composable(
                "product/{product}",
                arguments = listOf(navArgument("product") { type = NavType.StringType }),
            ) { entry ->
                val raw = URLDecoder.decode(entry.arguments?.getString("product").orEmpty(), "UTF-8")
                val product = ZureqClient.json.decodeFromString(Product.serializer(), raw)
                ProductDetailScreen(model, product, onBack = { navController.popBackStack() })
            }
            composable(Tab.Settings.route) { SettingsScreen(model) }
        }
    }
}
