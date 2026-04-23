package de.traewelling.app.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import de.traewelling.app.ui.screens.*
import de.traewelling.app.viewmodel.*

sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    object Feed          : Screen("feed",          "Feed",              Icons.Default.Home)
    object CheckIn       : Screen("checkin",       "Check-in",          Icons.Default.Train)
    object Notifications : Screen("notifications", "Meldungen",        Icons.Default.Notifications)
    object Profile       : Screen("profile",       "Profil",            Icons.Default.Person)
}

@Composable
fun MainNavigation(
    authViewModel: AuthViewModel,
    feedViewModel: FeedViewModel,
    checkInViewModel: CheckInViewModel,
    profileViewModel: ProfileViewModel,
    notificationViewModel: NotificationViewModel,
    userProfileViewModel: UserProfileViewModel,
    statusDetailViewModel: StatusDetailViewModel
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val notificationState by notificationViewModel.uiState.collectAsState()
    val unreadCount = notificationState.unreadCount

    val tabs = listOf(Screen.Feed, Screen.CheckIn, Screen.Notifications, Screen.Profile)

    // Only show bottom bar on main tabs, not on nested screens
    val showBottomBar = currentRoute in tabs.map { it.route }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    tabs.forEach { screen ->
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
                            selected = currentRoute == screen.route,
                            onClick = {
                                if (currentRoute != screen.route) {
                                    navController.navigate(screen.route) {
                                        popUpTo(navController.graph.startDestinationId) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState    = true
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    ) { paddingValues ->
        NavHost(
            navController    = navController,
            startDestination = Screen.Feed.route,
            modifier         = Modifier.padding(bottom = paddingValues.calculateBottomPadding())
        ) {
            composable(Screen.Feed.route) {
                FeedScreen(
                    viewModel     = feedViewModel,
                    onUserClick   = { username -> navController.navigate("userProfile/$username") },
                    onStatusClick = { statusId -> navController.navigate("statusDetail/$statusId") }
                )
            }
            composable(Screen.CheckIn.route) {
                CheckInScreen(checkInViewModel)
            }
            composable(Screen.Notifications.route) {
                NotificationScreen(notificationViewModel)
            }
            composable(Screen.Profile.route) {
                ProfileScreen(
                    profileViewModel,
                    authViewModel,
                    onStatusClick = { statusId -> navController.navigate("statusDetail/$statusId") }
                )
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
        }
    }
}
