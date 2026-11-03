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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.clearAndSetSemantics
import de.traewelling.app.service.TripTrackingService
import de.traewelling.app.service.RideRecognitionService
import de.traewelling.app.ui.navigation.NavigationRequest
import de.traewelling.app.ui.navigation.MainNavigation
import de.traewelling.app.ui.screens.SetupScreen
import de.traewelling.app.ui.components.StorageRecoveryDialog
import de.traewelling.app.ui.theme.TraewellingTheme
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.ViewModelProvider
import de.traewelling.app.util.PreferencesManager
import de.traewelling.app.util.ConfirmedStorageSession
import de.traewelling.app.util.PreferenceStorageException
import de.traewelling.app.util.TrackingConfiguration
import de.traewelling.app.util.canConfirmStorageReset
import de.traewelling.app.util.canResumeStoredServices
import de.traewelling.app.util.canUseStoredSession
import de.traewelling.app.util.SessionViewModelStore
import de.traewelling.app.util.SystemSettingsNavigation
import de.traewelling.app.viewmodel.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_OPEN_STATUS_ID = "open_status_id"
    }

    private val authViewModel:         AuthViewModel         by viewModels()
    private val sessionViewModels:     SessionViewModelStore by viewModels()
    private val settingsViewModel:     SettingsViewModel     by viewModels()
    private val preferences by lazy { PreferencesManager(applicationContext) }

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
        checkAndResumeServices()
        receiveNavigationIntent(intent)

        setContent {
            val settingsState by settingsViewModel.uiState.collectAsState()
            val storageState by preferences.storageState.collectAsState()

            TraewellingTheme(theme = settingsState.appTheme) {
                val snackbarHostState = remember { SnackbarHostState() }
                val scope             = rememberCoroutineScope()
                val authState         by authViewModel.uiState.collectAsState()
                val requestedNavigation by navigationRequest.collectAsState()
                val keyboard = LocalSoftwareKeyboardController.current
                var confirmedStorageSession by remember { mutableStateOf<ConfirmedStorageSession?>(null) }
                var hasRenderedContent by remember { mutableStateOf(false) }
                var recoveryActionInProgress by remember { mutableStateOf(false) }
                val canInteract = canUseStoredSession(storageState, authState.hasLoadedSession,
                    authState.sessionRevision, authState.isLoggedIn, confirmedStorageSession)

                // Ready can arrive before the separate AuthViewModel collector hydrates/reset completes.
                LaunchedEffect(storageState.isReady, storageState.revision,
                    authState.hasLoadedSession, authState.sessionRevision) {
                    confirmedStorageSession = null
                    if (storageState.isReady) {
                        try {
                            val session = preferences.getAuthSession()
                            confirmedStorageSession = ConfirmedStorageSession(session.revision, session.accessToken != null,
                                storageState.revision)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: PreferenceStorageException) {
                            // The shared storage state owns the visible recovery action.
                        }
                    }
                }
                SideEffect { if (canInteract) hasRenderedContent = true }
                LaunchedEffect(canInteract) { if (!canInteract) keyboard?.hide() }

                LaunchedEffect(canInteract, authState.isLoggedIn, authState.sessionRevision) {
                    if (canInteract && !authState.isLoggedIn) sessionViewModels.clearSession()
                }

                // Show "Willkommen @user" once after login / startup validation
                LaunchedEffect(canInteract, authState.welcomeMessage) {
                    authState.welcomeMessage?.takeIf { canInteract }?.let { msg ->
                        snackbarHostState.showSnackbar(message = msg, duration = SnackbarDuration.Short)
                        authViewModel.clearWelcomeMessage()
                    }
                }

                Scaffold(
                    modifier      = Modifier.fillMaxSize(),
                    snackbarHost  = { SnackbarHost(snackbarHostState) },
                    containerColor = MaterialTheme.colorScheme.background
                ) { innerPadding ->
                    Box(modifier = Modifier.fillMaxSize().then(
                        if (canInteract) Modifier else Modifier.clearAndSetSemantics {}
                    )) {
                        if ((canInteract || hasRenderedContent) && authState.isLoggedIn) {
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
                        } else if (canInteract || hasRenderedContent) {
                            Box(modifier = Modifier.padding(innerPadding)) {
                                SetupScreen(viewModel = authViewModel)
                            }
                        }
                    }
                }
                if (!canInteract || recoveryActionInProgress) {
                    StorageRecoveryDialog(
                        state = storageState,
                        actionInProgress = recoveryActionInProgress,
                        onRetry = {
                            if (!recoveryActionInProgress) {
                                recoveryActionInProgress = true
                                scope.launch {
                                    try { preferences.retryStorage() }
                                    finally { recoveryActionInProgress = false }
                                }
                            }
                        },
                        onReset = { failureId ->
                            if (!recoveryActionInProgress && canConfirmStorageReset(failureId, preferences.storageState.value)) {
                                recoveryActionInProgress = true
                                scope.launch {
                                    try { preferences.resetCorruptedStorage(failureId) }
                                    finally { recoveryActionInProgress = false }
                                }
                            }
                        }
                    )
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

    private fun checkAndResumeServices() {
        lifecycleScope.launch {
            // Location foreground services must start while the Activity is visible.
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                combine(preferences.storageState, preferences.trackingConfiguration, permissionRevision,
                    TripTrackingService.storagePauseInProgress, RideRecognitionService.storagePauseInProgress
                ) { storage, configuration, revision, tripPausing, recognitionPausing ->
                    ServiceResumeSignal(configuration, storage.isReady, storage.revision, revision,
                        tripPausing, recognitionPausing)
                }.distinctUntilChanged().collectLatest { signal ->
                    if (!signal.storageReady) {
                        visibleTrackingStatusId = null
                        // Each service owns its non-destructive storage pause and paused widget.
                        return@collectLatest
                    }
                    storageAction {
                        val config = preferences.getTrackingConfiguration()
                        hasRequestedLocationPermission = preferences.hasRequestedLocationPermission()
                        if (!canResumeStoredServices(signal.storageRevision, preferences.storageState.value, false))
                            return@storageAction
                        resumeTrip(config, signal)
                        resumeRecognition(config, signal)
                    }
                }
            }
        }
    }

    private fun resumeTrip(config: TrackingConfiguration, signal: ServiceResumeSignal) {
        if (!canResumeStoredServices(signal.storageRevision, preferences.storageState.value,
                TripTrackingService.storagePauseInProgress.value)) return
        val statusId = config.activeStatusId
        visibleTrackingStatusId = statusId
        if (statusId == null || config.session.accessToken == null) {
            permissionAskedForStatusId = null
            stopService(Intent(this, TripTrackingService::class.java))
            return
        }
        val fineGranted = hasPreciseLocationPermission()
        startTripTracking(statusId, config.gpsEnabled && fineGranted && locationServicesEnabled(), config.session.revision)
        if (config.gpsEnabled && !fineGranted && permissionAskedForStatusId != statusId) {
            permissionAskedForStatusId = statusId
            requestGpsPermission()
        }
    }

    private fun resumeRecognition(config: TrackingConfiguration, signal: ServiceResumeSignal) {
        if (!canResumeStoredServices(signal.storageRevision, preferences.storageState.value,
                RideRecognitionService.storagePauseInProgress.value)) return
        if (config.recognitionEnabled && config.session.accessToken != null && config.activeStatusId == null &&
            hasPreciseLocationPermission() && locationServicesEnabled()) {
            try {
                ContextCompat.startForegroundService(this,
                    RideRecognitionService.startIntent(this).putExtra(
                        RideRecognitionService.EXTRA_AUTH_SESSION_REVISION, config.session.revision))
            } catch (error: RuntimeException) {
                android.util.Log.w("MainActivity", "Fahrterkennung konnte nicht gestartet werden", error)
            }
        } else stopService(Intent(this, RideRecognitionService::class.java))
    }

    private data class ServiceResumeSignal(
        val configuration: TrackingConfiguration,
        val storageReady: Boolean,
        val storageRevision: Long,
        val permissionRevision: Int,
        val tripPausing: Boolean,
        val recognitionPausing: Boolean
    )

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

    private fun requestRideRecognitionStart() {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || !preferences.storageState.value.isReady) return
        val revision = authViewModel.uiState.value.sessionRevision
        lifecycleScope.launch {
            storageAction {
                val session = preferences.getAuthSession()
                if (session.revision != revision || session.accessToken == null ||
                    !preferences.setRideRecognitionEnabled(true, session)) return@storageAction
                requestNotificationPermission()
                requestGpsPermission(fromSettings = true)
                permissionRevision.value += 1
            }
        }
    }

    private fun stopRideRecognition() {
        val revision = authViewModel.uiState.value.sessionRevision
        lifecycleScope.launch {
            storageAction {
                val session = preferences.getAuthSession()
                if (session.revision == revision) preferences.setRideRecognitionEnabled(false, session)
            }
        }
        stopService(Intent(this, RideRecognitionService::class.java))
    }

    private suspend fun storageAction(action: suspend () -> Unit) {
        try {
            action()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: PreferenceStorageException) {
            // Recovery is displayed by the shared root gate; never replace stored values here.
        }
    }

    private fun openLiveUpdateSettings() {
        if (!SystemSettingsNavigation.liveUpdates(Build.VERSION.SDK_INT, ::launchSystemSettings)) {
            showSettingsHelp("Öffne die Android-Einstellungen und wähle Apps → Routely → Benachrichtigungen.")
        }
    }

    private fun launchSystemSettings(page: SystemSettingsNavigation.Page): Boolean {
        val intent = when (page) {
            SystemSettingsNavigation.Page.LIVE_UPDATES -> {
                if (Build.VERSION.SDK_INT < 36) return false
                Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            }
            SystemSettingsNavigation.Page.NOTIFICATIONS -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            SystemSettingsNavigation.Page.APP_DETAILS ->
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            SystemSettingsNavigation.Page.LOCATION -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            SystemSettingsNavigation.Page.GENERAL -> Intent(Settings.ACTION_SETTINGS)
        }
        return try {
            startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    private fun showSettingsHelp(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
    }

    private fun hasPreciseLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun locationServicesEnabled(): Boolean {
        val manager = getSystemService(LocationManager::class.java) ?: return false
        return LocationManagerCompat.isLocationEnabled(manager)
    }

    private fun requestGpsPermission(fromSettings: Boolean = false) {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || permissionRequestInFlight ||
            !preferences.storageState.value.isReady) return
        permissionAskedForStatusId = visibleTrackingStatusId
        if (hasPreciseLocationPermission()) {
            if (!locationServicesEnabled()) {
                if (!SystemSettingsNavigation.location(::launchSystemSettings)) {
                    showSettingsHelp("Öffne die Android-Einstellungen und aktiviere den Standort.")
                }
            } else {
                permissionRevision.value += 1
            }
            return
        }
        if (fromSettings && hasRequestedLocationPermission &&
            !ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.ACCESS_FINE_LOCATION)
        ) {
            if (!SystemSettingsNavigation.permissions(::launchSystemSettings)) {
                showSettingsHelp("Öffne die Android-Einstellungen und erlaube unter Apps → Routely den genauen Standort.")
            }
            return
        }
        permissionRequestInFlight = true
        lifecycleScope.launch {
            var launched = false
            try {
                storageAction {
                    preferences.markLocationPermissionRequested()
                    hasRequestedLocationPermission = true
                    if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && preferences.storageState.value.isReady) {
                        locationPermissionLauncher.launch(arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        ))
                        launched = true
                    }
                }
            } finally {
                if (!launched) {
                    permissionRequestInFlight = false
                    permissionAskedForStatusId = null
                }
            }
        }
    }

    private fun startTripTracking(statusId: Int, enableGps: Boolean, sessionRevision: String) {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || !preferences.storageState.value.isReady) return
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
