package de.traewelling.app

import android.Manifest
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
import de.traewelling.app.ui.navigation.MainNavigation
import de.traewelling.app.ui.screens.SetupScreen
import de.traewelling.app.ui.theme.TraewellingTheme
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import de.traewelling.app.util.PreferencesManager
import de.traewelling.app.viewmodel.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged

class MainActivity : ComponentActivity() {

    private val authViewModel:         AuthViewModel         by viewModels()
    private val feedViewModel:         FeedViewModel         by viewModels()
    private val checkInViewModel:      CheckInViewModel      by viewModels()
    private val profileViewModel:      ProfileViewModel      by viewModels()
    private val notificationViewModel: NotificationViewModel by viewModels()
    private val userProfileViewModel:  UserProfileViewModel  by viewModels()
    private val statusDetailViewModel: StatusDetailViewModel by viewModels()
    private val userSearchViewModel:   UserSearchViewModel   by viewModels()
    private val settingsViewModel:     SettingsViewModel     by viewModels()

    private val permissionRevision = MutableStateFlow(0)
    private var permissionRequestInFlight = false
    private var permissionAskedForStatusId: Int? = null
    private var visibleTrackingStatusId: Int? = null
    private var hasRequestedLocationPermission = false
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

        setContent {
            val settingsState by settingsViewModel.uiState.collectAsState()

            TraewellingTheme(theme = settingsState.appTheme) {
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
                            statusDetailViewModel = statusDetailViewModel,
                            userSearchViewModel   = userSearchViewModel,
                            settingsViewModel     = settingsViewModel,
                            onRequestGpsPermission = { requestGpsPermission(fromSettings = true) }
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
                combine(prefs.activeStatusId, prefs.gpsTrackingEnabled, permissionRevision) { id, enabled, revision ->
                    Triple(id, enabled, revision)
                }.distinctUntilChanged().collect { (statusId, gpsEnabled, _) ->
                    visibleTrackingStatusId = statusId
                    if (statusId == null) {
                        permissionAskedForStatusId = null
                        stopService(Intent(this@MainActivity, TripTrackingService::class.java))
                    } else {
                        val fineGranted = hasPreciseLocationPermission()
                        startTripTracking(statusId, gpsEnabled && fineGranted && locationServicesEnabled())
                        if (gpsEnabled && !fineGranted && permissionAskedForStatusId != statusId) {
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

    private fun startTripTracking(statusId: Int, enableGps: Boolean) {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        val intent = Intent(this, TripTrackingService::class.java).apply {
            putExtra(TripTrackingService.EXTRA_STATUS_ID, statusId)
            putExtra(TripTrackingService.EXTRA_ENABLE_GPS, enableGps)
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
