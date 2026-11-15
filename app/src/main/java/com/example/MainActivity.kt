package com.example

import android.content.Intent
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
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
import com.example.core.web.WebContentReader
import com.example.ui.components.WebExplorerSheet
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
    private var incomingExploreTarget by mutableStateOf<String?>(null)

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as AgentLabApp).container
        handleIncomingShareIntent(intent)

        setContent {
            val windowSizeClass = calculateWindowSizeClass(this)
            val isWideScreen = windowSizeClass.widthSizeClass != WindowWidthSizeClass.Compact

            AgentLabTheme {
                AgentLabRoot(
                    container = container,
                    isWideScreen = isWideScreen,
                    incomingExploreTarget = incomingExploreTarget,
                    onConsumeIncomingExplore = { incomingExploreTarget = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingShareIntent(intent)
    }

    private fun handleIncomingShareIntent(intent: Intent?) {
        if (intent == null) return
        val raw = when (intent.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        } ?: return
        val extracted = WebContentReader.extractFirstUrlOrNull(raw) ?: raw.trim()
        if (extracted.isNotBlank()) {
            incomingExploreTarget = extracted
        }
    }
}

@Composable
fun AgentLabRoot(
    container: AgentLabContainer,
    isWideScreen: Boolean,
    incomingExploreTarget: String? = null,
    onConsumeIncomingExplore: () -> Unit = {},
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

    var showWebExplorer by androidx.compose.runtime.remember { mutableStateOf(false) }
    var explorerInitialTarget by androidx.compose.runtime.remember { mutableStateOf<String?>(null) }

    androidx.compose.runtime.LaunchedEffect(incomingExploreTarget) {
        if (!incomingExploreTarget.isNullOrBlank()) {
            explorerInitialTarget = incomingExploreTarget
            showWebExplorer = true
            onConsumeIncomingExplore()
        }
    }

    fun openWebExplorer(initialInput: String? = null) {
        explorerInitialTarget = initialInput
        showWebExplorer = true
    }

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
                        val label = stringResource(dest.labelRes)
                        val selected = currentDestination?.hierarchy?.any {
                            it.hasRoute(dest.route::class)
                        } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = { navigateToTab(dest.route) },
                            icon = {
                                Icon(
                                    imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                    contentDescription = label,
                                )
                            },
                            label = {
                                Text(
                                    text = label,
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
                        val label = stringResource(dest.labelRes)
                        val selected = currentDestination?.hierarchy?.any {
                            it.hasRoute(dest.route::class)
                        } == true
                        NavigationRailItem(
                            selected = selected,
                            onClick = { navigateToTab(dest.route) },
                            icon = {
                                Icon(
                                    imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                    contentDescription = label,
                                )
                            },
                            label = { Text(label) },
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
                        onExploreInternet = { target -> openWebExplorer(target) },
                    )
                }
                composable<AppRoute.Sources> {
                    SourcesScreen(
                        viewModel = sourcesVm,
                        isWideScreen = isWideScreen,
                        onExploreInternet = { target -> openWebExplorer(target) },
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
                        onExploreInternet = { target -> openWebExplorer(target) },
                    )
                }
                composable<AppRoute.Settings> {
                    SettingsScreen(viewModel = settingsVm)
                }
            }
        }
    }

    if (showWebExplorer) {
        WebExplorerSheet(
            explorer = container.internetExplorerEngine,
            initialUrlOrQuery = explorerInitialTarget,
            onDismiss = { showWebExplorer = false },
        )
    }
}
