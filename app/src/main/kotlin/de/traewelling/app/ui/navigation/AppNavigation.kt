package de.traewelling.app.ui.navigation

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import de.traewelling.app.ui.screens.*
import de.traewelling.app.viewmodel.*
import kotlinx.coroutines.launch

sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    object Main          : Screen("main",          "Main",              Icons.Default.Home)
    object Feed          : Screen("feed",          "Feed",              Icons.Default.Home)
    object CheckIn       : Screen("checkin",       "Check-in",          Icons.Default.Train)
    object Notifications : Screen("notifications", "Meldungen",         Icons.Default.Notifications)
    object Profile       : Screen("profile",       "Profil",            Icons.Default.Person)
    object Settings      : Screen("settings",      "Einstellungen",     Icons.Default.Settings)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MainNavigation(
    authViewModel: AuthViewModel,
    feedViewModel: FeedViewModel,
    checkInViewModel: CheckInViewModel,
    profileViewModel: ProfileViewModel,
    notificationViewModel: NotificationViewModel,
    userProfileViewModel: UserProfileViewModel,
    statusDetailViewModel: StatusDetailViewModel,
    userSearchViewModel: UserSearchViewModel,
    settingsViewModel: SettingsViewModel
) {
    val navController = rememberNavController()

    NavHost(
        navController    = navController,
        startDestination = Screen.Main.route,
    ) {
        composable(Screen.Main.route) {
            val notificationState by notificationViewModel.uiState.collectAsState()
            val unreadCount = notificationState.unreadCount

            val tabs = listOf(Screen.Feed, Screen.CheckIn, Screen.Notifications, Screen.Profile)
            val pagerState = rememberPagerState(pageCount = { tabs.size })
            val coroutineScope = rememberCoroutineScope()

            Scaffold(
                bottomBar = {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface,
                        tonalElevation = 10.dp
                    ) {
                        tabs.forEachIndexed { index, screen ->
                            NavigationBarItem(
                                icon = {
                                    if (screen == Screen.Notifications && unreadCount > 0) {
                                        BadgedBox(badge = {
                                            Badge { Text(if (unreadCount > 99) "99+" else unreadCount.toString()) }
                                        }) {
                                            Icon(screen.icon, screen.label)
                                        }
                                    } else {
                                        Icon(screen.icon, screen.label)
                                    }
                                },
                                label = { Text(screen.label) },
                                selected = pagerState.currentPage == index,
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                    indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                                ),
                                onClick = {
                                    coroutineScope.launch {
                                        pagerState.animateScrollToPage(index)
                                    }
                                }
                            )
                        }
                    }
                }
            ) { paddingValues ->
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.padding(bottom = paddingValues.calculateBottomPadding())
                ) { page ->
                    when (tabs[page]) {
                        Screen.Feed -> {
                            FeedScreen(
                                viewModel     = feedViewModel,
                                onUserClick   = { username -> navController.navigate("userProfile/$username") },
                                onStatusClick = { statusId -> navController.navigate("statusDetail/$statusId") },
                                onSearchUsersClick = { navController.navigate("userSearch") }
                            )
                        }
                        Screen.CheckIn -> {
                            CheckInScreen(checkInViewModel)
                        }
                        Screen.Notifications -> {
                            NotificationScreen(notificationViewModel)
                        }
                        Screen.Profile -> {
                            ProfileScreen(
                                profileViewModel,
                                authViewModel,
                                onStatusClick = { statusId -> navController.navigate("statusDetail/$statusId") },
                                onSettingsClick = { navController.navigate(Screen.Settings.route) }
                            )
                        }
                        else -> {}
                    }
                }
            }
        }

        composable(
            route = "userProfile/{username}",
            arguments = listOf(navArgument("username") { type = NavType.StringType })
        ) { backStackEntry ->
            val username = backStackEntry.arguments?.getString("username") ?: ""
            UserProfileScreen(
                username  = username,
                viewModel = userProfileViewModel,
                onBack    = { navController.popBackStack() },
                onStatusClick = { statusId -> navController.navigate("statusDetail/$statusId") }
            )
        }
        composable("userSearch") {
            UserSearchScreen(
                viewModel = userSearchViewModel,
                onUserClick = { username -> navController.navigate("userProfile/$username") },
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = "statusDetail/{statusId}",
            arguments = listOf(navArgument("statusId") { type = NavType.IntType })
        ) { backStackEntry ->
            val statusId = backStackEntry.arguments?.getInt("statusId") ?: 0
            StatusDetailScreen(
                statusId    = statusId,
                viewModel   = statusDetailViewModel,
                onBack      = { navController.popBackStack() },
                onUserClick = { username -> navController.navigate("userProfile/$username") }
            )
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                viewModel = settingsViewModel,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
