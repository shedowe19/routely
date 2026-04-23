package de.traewelling.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

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
}
