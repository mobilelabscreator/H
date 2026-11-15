package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.ui.navigation.AppRoute
import com.example.ui.navigation.TopLevelDestinations
import com.example.ui.screens.agent.AgentScreen
import com.example.ui.screens.agent.AgentViewModel
import com.example.ui.screens.chat.ChatScreen
import com.example.ui.screens.chat.ChatViewModel
import com.example.ui.screens.knowledge.KnowledgeScreen
import com.example.ui.screens.knowledge.KnowledgeViewModel
import com.example.ui.screens.settings.SettingsScreen
import com.example.ui.screens.settings.SettingsViewModel
import com.example.ui.screens.skills.SkillsScreen
import com.example.ui.screens.skills.SkillsViewModel
import com.example.ui.screens.sources.SourcesScreen
import com.example.ui.screens.sources.SourcesViewModel
import com.example.ui.theme.AgentLabTheme

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as AgentLabApp).container

        setContent {
            val windowSizeClass = calculateWindowSizeClass(this)
            val isWideScreen = windowSizeClass.widthSizeClass != WindowWidthSizeClass.Compact

            AgentLabTheme {
                AgentLabRoot(
                    container = container,
                    isWideScreen = isWideScreen,
                )
            }
        }
    }
}

@Composable
fun AgentLabRoot(
    container: AgentLabContainer,
    isWideScreen: Boolean,
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    val chatVm: ChatViewModel = viewModel(factory = ChatViewModel.factory(container))
    val sourcesVm: SourcesViewModel = viewModel(factory = SourcesViewModel.factory(container))
    val knowledgeVm: KnowledgeViewModel = viewModel(factory = KnowledgeViewModel.factory(container))
    val skillsVm: SkillsViewModel = viewModel(factory = SkillsViewModel.factory(container))
    val agentVm: AgentViewModel = viewModel(factory = AgentViewModel.factory(container))
    val settingsVm: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(container))

    val currentSettings by settingsVm.settings.collectAsStateWithLifecycle()

    fun navigateToTab(route: AppRoute) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            if (!isWideScreen) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    TopLevelDestinations.forEach { dest ->
                        val selected = currentDestination?.hierarchy?.any {
                            it.hasRoute(dest.route::class)
                        } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = { navigateToTab(dest.route) },
                            icon = {
                                Icon(
                                    imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                    contentDescription = dest.label,
                                )
                            },
                            label = {
                                Text(
                                    text = dest.label,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            modifier = Modifier.testTag(dest.testTag),
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (isWideScreen) {
                NavigationRail(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    TopLevelDestinations.forEach { dest ->
                        val selected = currentDestination?.hierarchy?.any {
                            it.hasRoute(dest.route::class)
                        } == true
                        NavigationRailItem(
                            selected = selected,
                            onClick = { navigateToTab(dest.route) },
                            icon = {
                                Icon(
                                    imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                    contentDescription = dest.label,
                                )
                            },
                            label = { Text(dest.label) },
                            modifier = Modifier.testTag(dest.testTag),
                        )
                    }
                }
            }

            NavHost(
                navController = navController,
                startDestination = AppRoute.Chat,
                modifier = Modifier.weight(1f),
            ) {
                composable<AppRoute.Chat> {
                    ChatScreen(
                        viewModel = chatVm,
                        isWideScreen = isWideScreen,
                        needsApiKey = currentSettings.aiProvider.requiresApiKey && currentSettings.apiKey.isBlank(),
                        onNavigateToSettings = { navigateToTab(AppRoute.Settings) },
                    )
                }
                composable<AppRoute.Sources> {
                    SourcesScreen(
                        viewModel = sourcesVm,
                        isWideScreen = isWideScreen,
                    )
                }
                composable<AppRoute.Knowledge> {
                    KnowledgeScreen(
                        viewModel = knowledgeVm,
                        isWideScreen = isWideScreen,
                    )
                }
                composable<AppRoute.Skills> {
                    SkillsScreen(
                        viewModel = skillsVm,
                        isWideScreen = isWideScreen,
                    )
                }
                composable<AppRoute.Agent> {
                    AgentScreen(
                        viewModel = agentVm,
                        isWideScreen = isWideScreen,
                    )
                }
                composable<AppRoute.Settings> {
                    SettingsScreen(viewModel = settingsVm)
                }
            }
        }
    }
}
