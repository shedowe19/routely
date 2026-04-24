package de.traewelling.app

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import de.traewelling.app.ui.navigation.MainNavigation
import de.traewelling.app.ui.screens.SetupScreen
import de.traewelling.app.ui.theme.TraewellingTheme
import de.traewelling.app.util.WorkManagerHelper
import de.traewelling.app.viewmodel.*
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val authViewModel:         AuthViewModel         by viewModels()
    private val feedViewModel:         FeedViewModel         by viewModels()
    private val checkInViewModel:      CheckInViewModel      by viewModels()
    private val profileViewModel:      ProfileViewModel      by viewModels()
    private val notificationViewModel: NotificationViewModel by viewModels()
    private val userProfileViewModel:  UserProfileViewModel  by viewModels()
    private val statusDetailViewModel: StatusDetailViewModel by viewModels()

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            WorkManagerHelper.scheduleBackgroundSync(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Handle initial intent (e.g. if app was cold-started from a deep link)
        intent?.data?.let { handleDeepLink(it) }

        // Request notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        
        // Start background sync
        WorkManagerHelper.scheduleBackgroundSync(this)

        setContent {
            TraewellingTheme {
                val snackbarHostState = remember { SnackbarHostState() }
                val scope             = rememberCoroutineScope()
                val authState         by authViewModel.uiState.collectAsState()

                // Show "Willkommen @user" once after login / startup validation
                LaunchedEffect(authState.welcomeMessage) {
                    authState.welcomeMessage?.let { msg ->
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                message  = msg,
                                duration = SnackbarDuration.Short
                            )
                        }
                        authViewModel.clearWelcomeMessage()
                    }
                }

                Scaffold(
                    modifier      = Modifier.fillMaxSize(),
                    snackbarHost  = { SnackbarHost(snackbarHostState) },
                    containerColor = MaterialTheme.colorScheme.background
                ) { innerPadding ->
                    Box(modifier = Modifier.fillMaxSize()) {
                        if (authState.isLoggedIn) {
                            MainNavigation(
                                authViewModel         = authViewModel,
                                feedViewModel         = feedViewModel,
                                checkInViewModel      = checkInViewModel,
                                profileViewModel      = profileViewModel,
                                notificationViewModel = notificationViewModel,
                                userProfileViewModel  = userProfileViewModel,
                                statusDetailViewModel = statusDetailViewModel
                            )
                        } else {
                            Box(modifier = Modifier.padding(innerPadding)) {
                                SetupScreen(viewModel = authViewModel)
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent?) {
        super.onNewIntent(intent)
        intent?.data?.let { handleDeepLink(it) }
    }

    private fun handleDeepLink(uri: android.net.Uri) {
        if (uri.scheme == "traewelling" && uri.host == "oauth-callback") {
            authViewModel.handleOAuthCallback(uri)
        }
    }
}
