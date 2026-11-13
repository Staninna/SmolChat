/*
 * Copyright (C) 2026 SmolChat contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.shubham0204.smollmandroid.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.shubham0204.smollmandroid.R
import io.shubham0204.smollmandroid.ui.screens.AppsScreen
import io.shubham0204.smollmandroid.ui.screens.HomeScreen
import io.shubham0204.smollmandroid.ui.screens.LogScreen
import io.shubham0204.smollmandroid.ui.screens.ModelsScreen
import io.shubham0204.smollmandroid.ui.screens.OnboardingScreen
import io.shubham0204.smollmandroid.ui.screens.PlaygroundScreen
import io.shubham0204.smollmandroid.ui.screens.SettingsScreen
import io.shubham0204.smollmandroid.ui.theme.ServerTheme
import kotlin.reflect.KClass
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel

@Serializable object HomeRoute

@Serializable object ModelsRoute

@Serializable object AppsRoute

@Serializable object LogRoute

@Serializable object SettingsRoute

@Serializable object PlaygroundRoute

@Serializable object OnboardingRoute

private data class TopLevel(
    val route: Any,
    val routeClass: KClass<*>,
    val label: Int,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
)

private val topLevel =
    listOf(
        TopLevel(HomeRoute, HomeRoute::class, R.string.nav_home, Icons.Outlined.Home, Icons.Rounded.Home),
        TopLevel(ModelsRoute, ModelsRoute::class, R.string.nav_models, Icons.Outlined.Inventory2, Icons.Rounded.Inventory2),
        TopLevel(AppsRoute, AppsRoute::class, R.string.nav_apps, Icons.Outlined.Apps, Icons.Rounded.Apps),
        TopLevel(LogRoute, LogRoute::class, R.string.nav_log, Icons.Outlined.Insights, Icons.Rounded.Insights),
    )

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { ServerTheme { ServerApp() } }
    }
}

@Composable
private fun ServerApp(vm: MainViewModel = koinViewModel()) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val destination = backStack?.destination
    // read once: changing the start destination later would reset the graph
    val start = remember { if (vm.settings.value.onboardingDone) HomeRoute else OnboardingRoute }
    val showNavigation = topLevel.any { t -> destination?.hasRoute(t.routeClass) == true }

    fun navigateTop(route: Any) =
        nav.navigate(route) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            if (showNavigation) {
                topLevel.forEach { item ->
                    val selected = destination?.hasRoute(item.routeClass) == true
                    item(
                        selected = selected,
                        onClick = { navigateTop(item.route) },
                        icon = {
                            Icon(if (selected) item.selectedIcon else item.icon, contentDescription = null)
                        },
                        label = { Text(stringResource(item.label)) },
                    )
                }
            }
        }
    ) {
        NavHost(
            navController = nav,
            startDestination = start,
            enterTransition = { fadeIn() },
            exitTransition = { fadeOut() },
        ) {
            composable<OnboardingRoute> {
                OnboardingScreen(
                    vm = vm,
                    onDone = {
                        vm.completeOnboarding()
                        if (!nav.popBackStack()) {
                            nav.navigate(HomeRoute) { popUpTo<OnboardingRoute> { inclusive = true } }
                        }
                    },
                    onImportModel = { nav.navigate(ModelsRoute) { popUpTo<OnboardingRoute> { inclusive = true } } },
                )
            }
            composable<HomeRoute> {
                HomeScreen(
                    vm = vm,
                    onOpenSettings = { nav.navigate(SettingsRoute) },
                    onOpenPlayground = { nav.navigate(PlaygroundRoute) },
                    onOpenSetup = { nav.navigate(OnboardingRoute) },
                    onOpenModels = { navigateTop(ModelsRoute) },
                    onOpenApps = { navigateTop(AppsRoute) },
                    onOpenActivity = { navigateTop(LogRoute) },
                )
            }
            composable<ModelsRoute> { ModelsScreen(vm = vm) }
            composable<AppsRoute> { AppsScreen(vm = vm) }
            composable<LogRoute> { LogScreen(vm = vm, onOpenSettings = { nav.navigate(SettingsRoute) }) }
            composable<SettingsRoute> { SettingsScreen(vm = vm, onBack = { nav.popBackStack() }) }
            composable<PlaygroundRoute> { PlaygroundScreen(vm = vm, onBack = { nav.popBackStack() }) }
        }
    }
}
