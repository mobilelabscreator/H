package com.example.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.serialization.Serializable

sealed interface AppRoute {
    @Serializable data object Chat : AppRoute
    @Serializable data object Sources : AppRoute
    @Serializable data object Knowledge : AppRoute
    @Serializable data object Skills : AppRoute
    @Serializable data object Agent : AppRoute
    @Serializable data object Settings : AppRoute
}

data class TopLevelDestination(
    val route: AppRoute,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String,
)

val TopLevelDestinations = listOf(
    TopLevelDestination(
        route = AppRoute.Chat,
        label = "Chat",
        selectedIcon = Icons.Filled.ChatBubble,
        unselectedIcon = Icons.Outlined.ChatBubbleOutline,
        testTag = "nav_chat",
    ),
    TopLevelDestination(
        route = AppRoute.Sources,
        label = "Sources",
        selectedIcon = Icons.Filled.Language,
        unselectedIcon = Icons.Outlined.Language,
        testTag = "nav_sources",
    ),
    TopLevelDestination(
        route = AppRoute.Knowledge,
        label = "Knowledge",
        selectedIcon = Icons.Filled.Psychology,
        unselectedIcon = Icons.Outlined.Psychology,
        testTag = "nav_knowledge",
    ),
    TopLevelDestination(
        route = AppRoute.Skills,
        label = "Skills",
        selectedIcon = Icons.Filled.Code,
        unselectedIcon = Icons.Outlined.Code,
        testTag = "nav_skills",
    ),
    TopLevelDestination(
        route = AppRoute.Agent,
        label = "Agent",
        selectedIcon = Icons.Filled.AutoAwesome,
        unselectedIcon = Icons.Outlined.AutoAwesome,
        testTag = "nav_agent",
    ),
    TopLevelDestination(
        route = AppRoute.Settings,
        label = "Settings",
        selectedIcon = Icons.Filled.Settings,
        unselectedIcon = Icons.Outlined.Settings,
        testTag = "nav_settings",
    ),
)
