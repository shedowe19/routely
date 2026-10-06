package de.traewelling.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.provider.Settings
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import de.traewelling.app.service.TripTrackingService
import de.traewelling.app.service.RideRecognitionService
import de.traewelling.app.ui.navigation.NavigationRequest
import de.traewelling.app.ui.navigation.MainNavigation
import de.traewelling.app.ui.screens.SetupScreen
import de.traewelling.app.ui.theme.TraewellingTheme
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.ViewModelProvider
import de.traewelling.app.util.PreferencesManager
import de.traewelling.app.util.AuthSession
import de.traewelling.app.util.SessionViewModelStore
import de.traewelling.app.viewmodel.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_OPEN_STATUS_ID = "open_status_id"
    }

    private val authViewModel:         AuthViewModel         by viewModels()
    private val sessionViewModels:     SessionViewModelStore by viewModels()
    private val settingsViewModel:     SettingsViewModel     by viewModels()

    private val permissionRevision = MutableStateFlow(0)
    private var permissionRequestInFlight = false
    private var permissionAskedForStatusId: Int? = null
    private var visibleTrackingStatusId: Int? = null
    private var hasRequestedLocationPermission = false
    private val navigationRequest = MutableStateFlow<NavigationRequest?>(null)
    private var navigationToken = 0L
    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        permissionRequestInFlight = false
        permissionRevision.value += 1
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        requestNotificationPermission()
        checkAndResumeTripTracking()
        checkAndResumeRideRecognition()
        receiveNavigationIntent(intent)

        setContent {
            val settingsState by settingsViewModel.uiState.collectAsState()

            TraewellingTheme(theme = settingsState.appTheme) {
                val snackbarHostState = remember { SnackbarHostState() }
                val scope             = rememberCoroutineScope()
                val authState         by authViewModel.uiState.collectAsState()
                val requestedNavigation by navigationRequest.collectAsState()

                LaunchedEffect(authState.isLoggedIn, authState.sessionRevision) {
                    if (!authState.isLoggedIn) sessionViewModels.clearSession()
                }

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
                            key(authState.sessionRevision) {
                                val featureModels = remember(authState.sessionRevision) {
                                    ViewModelProvider(
                                        sessionViewModels.forSession(authState.sessionRevision),
                                        defaultViewModelProviderFactory,
                                        defaultViewModelCreationExtras
                                    )
                                }
                                MainNavigation(
                                    authViewModel         = authViewModel,
                                    feedViewModel         = featureModels[FeedViewModel::class.java],
                                    checkInViewModel      = featureModels[CheckInViewModel::class.java],
                                    profileViewModel      = featureModels[ProfileViewModel::class.java],
                                    notificationViewModel = featureModels[NotificationViewModel::class.java],
                                    userProfileViewModel  = featureModels[UserProfileViewModel::class.java],
                                    statusDetailViewModel = featureModels[StatusDetailViewModel::class.java],
                                    userSearchViewModel   = featureModels[UserSearchViewModel::class.java],
                                    settingsViewModel     = settingsViewModel,
                                    onRequestGpsPermission = { requestGpsPermission(fromSettings = true) },
                                    onStartRideRecognition = { requestRideRecognitionStart() },
                                    onStopRideRecognition = { stopRideRecognition() },
                                    onOpenLiveUpdateSettings = { openLiveUpdateSettings() },
                                    navigationRequest = requestedNavigation,
                                    onNavigationRequestConsumed = { consumedToken ->
                                        if (navigationRequest.value?.token == consumedToken) navigationRequest.value = null
                                    }
                                )
                            }
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

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
    }

    private fun checkAndResumeTripTracking() {
        val prefs = PreferencesManager(this)
        lifecycleScope.launch {
            hasRequestedLocationPermission = prefs.hasRequestedLocationPermission()
            // Location foreground services must start while the Activity is visible.
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                combine(prefs.trackingConfiguration, permissionRevision) { config, revision ->
                    TrackingStartState(config.activeStatusId, config.gpsEnabled, config.session, revision)
                }.distinctUntilChanged().collect { state ->
                    val statusId = state.statusId
                    visibleTrackingStatusId = statusId
                    if (statusId == null || state.session.accessToken == null) {
                        permissionAskedForStatusId = null
                        stopService(Intent(this@MainActivity, TripTrackingService::class.java))
                    } else {
                        val fineGranted = hasPreciseLocationPermission()
                        startTripTracking(statusId, state.gpsEnabled && fineGranted && locationServicesEnabled(), state.session.revision)
                        if (state.gpsEnabled && !fineGranted && permissionAskedForStatusId != statusId) {
                            permissionAskedForStatusId = statusId
                            requestGpsPermission()
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Recheck after returning from Android's app/location settings.
        permissionRevision.value += 1
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        receiveNavigationIntent(intent)
    }

    private fun receiveNavigationIntent(intent: Intent?) {
        val statusId = intent?.getIntExtra(EXTRA_OPEN_STATUS_ID, -1)?.takeIf { it > 0 }
        val openRecognition = intent?.getBooleanExtra(RideRecognitionService.EXTRA_OPEN_RECOGNITION, false) == true
        if (statusId != null || openRecognition) {
            navigationRequest.value = NavigationRequest(++navigationToken, statusId, openRecognition,
                intent?.getStringExtra(TripTrackingService.EXTRA_AUTH_SESSION_REVISION))
            intent?.removeExtra(EXTRA_OPEN_STATUS_ID)
            intent?.removeExtra(RideRecognitionService.EXTRA_OPEN_RECOGNITION)
            intent?.removeExtra(TripTrackingService.EXTRA_AUTH_SESSION_REVISION)
        }
    }

    private fun checkAndResumeRideRecognition() {
        val prefs = PreferencesManager(this)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                combine(prefs.trackingConfiguration, permissionRevision) { config, revision ->
                    RecognitionStartState(config.recognitionEnabled, config.activeStatusId, config.session, revision)
                }.distinctUntilChanged().collect { state ->
                    if (state.enabled && state.session.accessToken != null && state.activeId == null &&
                        hasPreciseLocationPermission() && locationServicesEnabled()
                    ) {
                        try {
                            ContextCompat.startForegroundService(this@MainActivity,
                                RideRecognitionService.startIntent(this@MainActivity).putExtra(
                                    RideRecognitionService.EXTRA_AUTH_SESSION_REVISION, state.session.revision))
                        } catch (error: RuntimeException) {
                            android.util.Log.w("MainActivity", "Fahrterkennung konnte nicht gestartet werden", error)
                        }
                    } else {
                        stopService(Intent(this@MainActivity, RideRecognitionService::class.java))
                    }
                }
            }
        }
    }

    private data class TrackingStartState(val statusId: Int?, val gpsEnabled: Boolean, val session: AuthSession, val revision: Int)
    private data class RecognitionStartState(val enabled: Boolean, val activeId: Int?, val session: AuthSession, val revision: Int)

    private fun requestRideRecognitionStart() {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        val revision = authViewModel.uiState.value.sessionRevision
        lifecycleScope.launch {
            val prefs = PreferencesManager(this@MainActivity)
            val session = prefs.getAuthSession()
            if (session.revision != revision || session.accessToken == null ||
                !prefs.setRideRecognitionEnabled(true, session)) return@launch
            requestNotificationPermission()
            requestGpsPermission(fromSettings = true)
            permissionRevision.value += 1
        }
    }

    private fun stopRideRecognition() {
        val revision = authViewModel.uiState.value.sessionRevision
        lifecycleScope.launch {
            val prefs = PreferencesManager(this@MainActivity)
            val session = prefs.getAuthSession()
            if (session.revision == revision) prefs.setRideRecognitionEnabled(false, session)
        }
        stopService(Intent(this, RideRecognitionService::class.java))
    }

    private fun openLiveUpdateSettings() {
        if (Build.VERSION.SDK_INT >= 36) {
            try {
                startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
                return
            } catch (_: ActivityNotFoundException) {
                // Some manufacturers omit the promoted-notification settings page.
            }
        }
        try {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    private fun hasPreciseLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun locationServicesEnabled(): Boolean {
        val manager = getSystemService(LocationManager::class.java) ?: return false
        return LocationManagerCompat.isLocationEnabled(manager)
    }

    private fun requestGpsPermission(fromSettings: Boolean = false) {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || permissionRequestInFlight) return
        permissionAskedForStatusId = visibleTrackingStatusId
        if (hasPreciseLocationPermission()) {
            if (!locationServicesEnabled()) {
                startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            } else {
                permissionRevision.value += 1
            }
            return
        }
        if (fromSettings && hasRequestedLocationPermission &&
            !ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.ACCESS_FINE_LOCATION)
        ) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            return
        }
        permissionRequestInFlight = true
        hasRequestedLocationPermission = true
        lifecycleScope.launch { PreferencesManager(this@MainActivity).markLocationPermissionRequested() }
        locationPermissionLauncher.launch(arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ))
    }

    private fun startTripTracking(statusId: Int, enableGps: Boolean, sessionRevision: String) {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        val intent = Intent(this, TripTrackingService::class.java).apply {
            putExtra(TripTrackingService.EXTRA_STATUS_ID, statusId)
            putExtra(TripTrackingService.EXTRA_ENABLE_GPS, enableGps)
            putExtra(TripTrackingService.EXTRA_AUTH_SESSION_REVISION, sessionRevision)
        }
        try {
            ContextCompat.startForegroundService(this, intent)
        } catch (error: SecurityException) {
            android.util.Log.w("MainActivity", "Tracking start requires renewed permission", error)
        } catch (error: IllegalStateException) {
            android.util.Log.w("MainActivity", "Tracking start deferred until Activity is visible", error)
        }
    }
}
