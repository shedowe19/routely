package de.traewelling.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import androidx.annotation.RequiresPermission
import com.google.android.gms.tasks.CancellationTokenSource
import de.traewelling.app.data.model.*
import de.traewelling.app.ui.components.StateMessage
import de.traewelling.app.ui.components.TraewellingTopAppBar
import de.traewelling.app.service.RideRecognitionPhase
import de.traewelling.app.ui.theme.SuccessGreen
import de.traewelling.app.viewmodel.CheckInStep
import de.traewelling.app.viewmodel.CheckInUiState
import de.traewelling.app.viewmodel.CheckInViewModel
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

@Composable
fun CheckInScreen(
    viewModel: CheckInViewModel,
    onStartRideRecognition: () -> Unit = {},
    onStopRideRecognition: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    
    val title = when (uiState.step) {
        CheckInStep.STATION -> "Check-in"
        CheckInStep.DEPARTURES -> "Abfahrten — ${uiState.selectedStation?.name ?: ""}"
        CheckInStep.DESTINATION -> "Ziel wählen — ${uiState.selectedDeparture?.line?.name ?: ""}"
        CheckInStep.CONFIRM -> "Details bestätigen"
        CheckInStep.SUCCESS -> if ((uiState.checkInResult?.status?.id ?: 0) > 0) "Eingecheckt!" else "Check-in prüfen"
    }
    
    val showBack = uiState.step != CheckInStep.STATION && uiState.step != CheckInStep.SUCCESS

    Scaffold(
        topBar = {
            TraewellingTopAppBar(
                title = title,
                navigationIcon = {
                    if (showBack) {
                        IconButton(onClick = viewModel::goBack,
                            enabled = !(uiState.step == CheckInStep.CONFIRM && uiState.isLoading)) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück")
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(Modifier.padding(innerPadding).fillMaxSize()) {
            when (uiState.step) {
                CheckInStep.STATION     -> StationSearchStep(viewModel, uiState, onStartRideRecognition, onStopRideRecognition)
                CheckInStep.DEPARTURES  -> DeparturesStep(viewModel, uiState)
                CheckInStep.DESTINATION -> DestinationStep(viewModel, uiState)
                CheckInStep.CONFIRM     -> ConfirmStep(viewModel, uiState)
                CheckInStep.SUCCESS     -> SuccessStep(viewModel, uiState)
            }
        }
    }
}

// ─── Step 1: Bahnhof suchen ──────────────────────────────────────────────────

@Composable
private fun StationSearchStep(
    viewModel: CheckInViewModel,
    uiState: CheckInUiState,
    onStartRideRecognition: () -> Unit,
    onStopRideRecognition: () -> Unit
) {

    val context = LocalContext.current
    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }
    var pendingLocationRequest by remember { mutableStateOf<Long?>(null) }
    var locationCancellation by remember { mutableStateOf<CancellationTokenSource?>(null) }
    DisposableEffect(viewModel) {
        onDispose {
            locationCancellation?.cancel()
            pendingLocationRequest?.let(viewModel::cancelLocationLookup)
        }
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val request = pendingLocationRequest ?: return@rememberLauncherForActivityResult
        if (!viewModel.isLocationLookupCurrent(request)) return@rememberLauncherForActivityResult
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                      permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                @Suppress("MissingPermission")
                locationCancellation = fetchLocation(context, fusedLocationClient, viewModel, request)
            }
        } else {
            viewModel.finishLocationLookup(request, null, null, "Standortfreigabe wurde nicht erteilt. Bitte suche die Station manuell.")
        }
    }

    Column(Modifier.fillMaxSize()) {
        RideRecognitionCard(viewModel, uiState, onStartRideRecognition, onStopRideRecognition)
        Spacer(Modifier.height(16.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = uiState.stationQuery,
                onValueChange = viewModel::updateStationQuery,
                label = { Text("Bahnhof suchen") },
                placeholder = { Text("z.B. Berlin Hbf") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    when {
                        uiState.stationQuery.isNotEmpty() ->
                            IconButton(onClick = { viewModel.updateStationQuery("") }) {
                                Icon(Icons.Default.Clear, "Löschen")
                            }
                        uiState.isLoading ->
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                },
                modifier = Modifier
                    .weight(1f),
                singleLine = true
            )

            Spacer(modifier = Modifier.width(8.dp))

            FilledIconButton(
                onClick = {
                    locationCancellation?.cancel()
                    val request = viewModel.beginLocationLookup()
                    pendingLocationRequest = request
                    val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                    val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                    if (hasFine || hasCoarse) {
                        @Suppress("MissingPermission")
                        locationCancellation = fetchLocation(context, fusedLocationClient, viewModel, request)
                    } else {
                        locationPermissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION
                            )
                        )
                    }
                },
                modifier = Modifier.padding(top = 8.dp)
            ) {
                Icon(Icons.Default.LocationOn, contentDescription = "Standort")
            }
        }

        uiState.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }

        when {
            uiState.stationQuery.length < 2 && uiState.searchResults.isEmpty() && !uiState.isLoading ->
                StateMessage(
                    icon = Icons.Default.Train,
                    title = "Station suchen",
                    message = "Gib mindestens 2 Zeichen ein oder nutze deinen Standort."
                )
            uiState.searchResults.isEmpty() && !uiState.isLoading ->
                StateMessage(
                    icon = Icons.Default.Train,
                    title = "Kein Bahnhof gefunden",
                    message = "Prüfe die Schreibweise oder suche nach einer größeren Station."
                )
            else ->
                LazyColumn(Modifier.fillMaxSize()) {
                    items(uiState.searchResults.size) { index ->
                        val station = uiState.searchResults[index]
                        ListItem(
                            headlineContent = {
                                Text(station.name ?: "–", fontWeight = FontWeight.Medium)
                            },
                            supportingContent = station.identifier("de_db_ril100")?.let { { Text("RIL: $it") } },
                            leadingContent = {
                                Icon(Icons.Default.Train, null,
                                    tint = MaterialTheme.colorScheme.primary)
                            },
                            modifier = Modifier.clickable { viewModel.selectStation(station) }
                        )
                        HorizontalDivider()
                    }
                }
        }
    }
}

@Composable
private fun RideRecognitionCard(
    viewModel: CheckInViewModel,
    uiState: CheckInUiState,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    Card(Modifier.padding(horizontal = 16.dp, vertical = 12.dp).fillMaxWidth().heightIn(max = 340.dp)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.MyLocation, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("Fahrt erkennen", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                when {
                    uiState.activeRidePresent -> "Du hast bereits eine aktive Fahrt. Die Fahrtsuche pausiert während der Begleitung."
                    !uiState.rideRecognitionEnabled -> "Routely kann anhand von GPS und passenden Abfahrten Fahrten vorschlagen. Du prüfst die Linie und wählst dein Ziel selbst."
                    uiState.rideRecognition.phase == RideRecognitionPhase.OFF -> "Die Suche ist pausiert. Prüfe die präzise Standortfreigabe und die Ortung deines Geräts."
                    else -> uiState.rideRecognition.message
                }, style = MaterialTheme.typography.bodySmall
            )
            if (!uiState.rideRecognitionEnabled) {
                Spacer(Modifier.height(6.dp))
                Text("Zur Suche naher Stationen wird dein Standort an den konfigurierten Träwelling-Server gesendet. Es wird kein GPS-Verlauf gespeichert und kein Check-in automatisch veröffentlicht.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            uiState.rideRecognition.candidates.forEach { candidate ->
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                val departure = candidate.ride.departure
                Text("${departure.line?.name ?: "Unbekannte Linie"} → ${departure.direction ?: candidate.nextStationName}",
                    fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                Text("Ab ${candidate.ride.origin?.stationName ?: "Station"} · ${formatLocalTime(departure.realWhen ?: departure.plannedWhen ?: "")}",
                    style = MaterialTheme.typography.bodySmall)
                Text("Nächster Halt im Verlauf: ${candidate.nextStationName}", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { viewModel.acceptRecognizedRide(candidate) }) { Text("Fahrt prüfen und Ziel wählen") }
            }
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!uiState.rideRecognitionEnabled || uiState.rideRecognition.phase == RideRecognitionPhase.OFF) {
                    Button(onClick = onStart, enabled = !uiState.activeRidePresent, modifier = Modifier.fillMaxWidth()) {
                        Text(if (uiState.rideRecognitionEnabled) "Standortfreigabe prüfen" else "Fahrtsuche aktivieren")
                    }
                }
                if (uiState.rideRecognitionEnabled) {
                    OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) { Text("Suche stoppen") }
                }
            }
        }
    }
}

@RequiresPermission(anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
private fun fetchLocation(
    context: Context,
    fusedLocationClient: com.google.android.gms.location.FusedLocationProviderClient,
    viewModel: CheckInViewModel,
    request: Long
): CancellationTokenSource {
    val cancellation = CancellationTokenSource()
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    ) {
        try {
        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancellation.token)
            .addOnSuccessListener { location ->
                if (!cancellation.token.isCancellationRequested) {
                    viewModel.finishLocationLookup(request, location?.latitude, location?.longitude)
                }
            }.addOnFailureListener {
                if (!cancellation.token.isCancellationRequested) {
                    viewModel.finishLocationLookup(request, null, null, "Standort konnte nicht ermittelt werden. Bitte suche die Station manuell.")
                }
            }
        } catch (_: SecurityException) {
            viewModel.finishLocationLookup(request, null, null, "Standortfreigabe fehlt. Bitte suche die Station manuell.")
        }
    } else {
        viewModel.finishLocationLookup(request, null, null, "Standortfreigabe fehlt. Bitte suche die Station manuell.")
    }
    return cancellation
}

// ─── Step 2: Abfahrten ───────────────────────────────────────────────────────

@Composable
private fun DeparturesStep(viewModel: CheckInViewModel, uiState: CheckInUiState) {
    Column(Modifier.fillMaxSize()) {

        when {
            uiState.isLoading ->
                StateMessage(
                    icon = Icons.Default.Schedule,
                    title = "Lade Abfahrten",
                    message = "Wir suchen passende Verbindungen ab deiner Station.",
                    loading = true
                )
            uiState.error != null ->
                ErrorBox(uiState.error, viewModel::goBack)
            uiState.departures.isEmpty() ->
                StateMessage(
                    icon = Icons.Default.Train,
                    title = "Keine Abfahrten gefunden",
                    message = "Für diese Station wurden aktuell keine passenden Fahrten gefunden."
                )
            else ->
                LazyColumn(Modifier.fillMaxSize()) {
                    items(uiState.departures) { departure ->
                        DepartureListItem(departure) { viewModel.selectTrip(departure) }
                        HorizontalDivider()
                    }
                }
        }
    }
}

@Composable
private fun DepartureListItem(departure: DepartureTrip, onClick: () -> Unit) {
    val lineName  = departure.line?.name ?: "?"
    val direction = departure.direction ?: "–"
    val times = departureTimePresentation(departure)
    val cancelled = departure.cancelled == true

    ListItem(
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(lineName, fontWeight = FontWeight.SemiBold)
                if (cancelled) {
                    Spacer(Modifier.width(8.dp))
                    Text("AUSFALL", color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall)
                }
            }
        },
        supportingContent = {
            Column {
                Text("→ $direction")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(times.time, style = MaterialTheme.typography.bodySmall,
                        color = when {
                            times.delayed -> MaterialTheme.colorScheme.error
                            times.earlier -> SuccessGreen
                            else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        })
                    times.plannedTime?.let { planned ->
                        Spacer(Modifier.width(4.dp))
                        Text("(Plan $planned)", style = MaterialTheme.typography.bodySmall)
                    }
                    times.deviation?.let { deviation ->
                        Spacer(Modifier.width(4.dp))
                        Text(deviation,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (times.earlier) SuccessGreen else MaterialTheme.colorScheme.error)
                    }
                    val plat = departure.platform?.takeIf { it.isNotBlank() }
                    if (plat != null) {
                        Spacer(Modifier.width(8.dp))
                        Text("Gleis $plat", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                    }
                }
            }
        },
        leadingContent = {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = MaterialTheme.shapes.extraSmall
            ) {
                Text(
                    when (departure.line?.product) {
                        "nationalExpress" -> "ICE"
                        "national"        -> "IC"
                        "regionalExp"     -> "RE"
                        "regional"        -> "RB"
                        "suburban"        -> "S"
                        "subway"          -> "U"
                        "tram"            -> "T"
                        "bus"             -> "Bus"
                        "ferry"           -> "F"
                        else              -> lineName.take(4)
                    },
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        },
        modifier = Modifier.clickable(enabled = !cancelled) { onClick() }
    )
}

// ─── Step 3: Ziel wählen ──────────────────────────────────

@Composable
private fun DestinationStep(viewModel: CheckInViewModel, uiState: CheckInUiState) {
    val stopovers = uiState.filteredDestinations

    Column(Modifier.fillMaxSize()) {

        when {
            uiState.isLoading ->
                StateMessage(
                    icon = Icons.Default.Route,
                    title = "Lade Halte",
                    message = "Der Fahrtverlauf wird vorbereitet.",
                    loading = true
                )
            stopovers.isEmpty() ->
                StateMessage(
                    icon = Icons.Default.LocationOn,
                    title = "Keine Zwischenhalte verfügbar",
                    message = "Für diese Fahrt konnten keine möglichen Ziele geladen werden."
                )
            else ->
                LazyColumn(Modifier.fillMaxSize()) {
                    items(stopovers) { stop ->
                        ListItem(
                            headlineContent = {
                                Text(stop.stationName ?: "–")
                            },
                            supportingContent = {
                                val arr = stop.effectiveArrival
                                if (arr != null) {
                                    Text("Ankunft: ${formatLocalTime(arr)}")
                                }
                            },
                            leadingContent = {
                                Icon(Icons.Default.LocationOn, null,
                                    tint = MaterialTheme.colorScheme.secondary)
                            },
                            modifier = Modifier.clickable { viewModel.selectDestination(stop) }
                        )
                        HorizontalDivider()
                    }
                }
        }
    }
}

// ─── Step 4: Bestätigen ──────────────────────────────────────────────────────

@Composable
private fun ConfirmStep(viewModel: CheckInViewModel, uiState: CheckInUiState) {
    val dep     = uiState.selectedDeparture
    val times = departureTimePresentation(dep?.copy(
        plannedWhen = uiState.resolvedOriginStop?.departurePlanned ?: dep.plannedWhen,
        realWhen = uiState.resolvedOriginStop?.departureReal ?: dep.realWhen
    ))
    val depTime = listOfNotNull(times.time, times.plannedTime?.let { "Plan $it" }, times.deviation).joinToString(" · ")

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        InfoRow(Icons.Default.Train,       "Linie",   dep?.line?.name ?: "–")
                        InfoRow(Icons.Default.TripOrigin,  "Von",     uiState.selectedStation?.name ?: "–")
                        InfoRow(Icons.Default.LocationOn,  "Nach",    uiState.selectedDestination?.stationName ?: "–")
                        InfoRow(Icons.Default.Schedule,    "Abfahrt", depTime)
                        dep?.platform?.takeIf { it.isNotBlank() }?.let {
                            InfoRow(Icons.Default.ConfirmationNumber, "Gleis", it)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                TravelReasonSelector(
                    selected = uiState.travelReason,
                    onSelected = viewModel::updateTravelReason,
                    enabled = !uiState.isLoading
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = uiState.statusBody,
                    enabled = !uiState.isLoading,
                    onValueChange = viewModel::updateStatusBody,
                    label = { Text("Statusmeldung (optional)") },
                    placeholder = { Text("Was machst du auf dieser Reise?") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )
                Spacer(Modifier.height(16.dp))

                Text("Tatsächliche Zeiten korrigieren (optional)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text("Leer lassen für API-/Fahrplanzeiten. Eingaben werden nach dem Check-in als manuelle Zeiten gespeichert. Nutze ISO-Format mit Zeitzone, z. B. 2026-10-06T18:05:00+02:00.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = uiState.manualDeparture,
                        enabled = !uiState.isLoading,
                        onValueChange = viewModel::updateManualDeparture,
                        label = { Text("Abfahrt real") },
                        modifier = Modifier.weight(1f),
                        textStyle = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = uiState.manualArrival,
                        enabled = !uiState.isLoading,
                        onValueChange = viewModel::updateManualArrival,
                        label = { Text("Ankunft real") },
                        modifier = Modifier.weight(1f),
                        textStyle = MaterialTheme.typography.bodySmall
                    )
                }
                Spacer(Modifier.height(16.dp))
                uiState.error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = viewModel::confirmCheckIn,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                enabled = !uiState.isLoading
            ) {
                if (uiState.isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Icon(Icons.Default.CheckCircle, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Jetzt einchecken!", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

// ─── Step 5: Erfolg ──────────────────────────────────────────────────────────

@Composable
private fun SuccessStep(viewModel: CheckInViewModel, uiState: CheckInUiState) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        val hasConfirmedStatus = (uiState.checkInResult?.status?.id ?: 0) > 0
        Icon(if (hasConfirmedStatus) Icons.Default.CheckCircle else Icons.Default.Info, null, modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text(if (hasConfirmedStatus) "Erfolgreich eingecheckt!" else "Check-in angenommen – bitte prüfen",
            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        if (uiState.isLoading) {
            Spacer(Modifier.height(16.dp))
            CircularProgressIndicator(Modifier.size(28.dp))
            Text("Fahrt wird für die Begleitung aktiviert; eingegebene Zeitkorrekturen werden gespeichert.",
                style = MaterialTheme.typography.bodySmall)
        }
        uiState.completionWarning?.let { warning ->
            Spacer(Modifier.height(16.dp))
            Text(warning, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        uiState.checkInResult?.points?.points?.let { pts ->
            if (pts > 0) {
                Spacer(Modifier.height(8.dp))
                Text("+$pts Punkte erhalten!",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.secondary)
            }
        }
        Spacer(Modifier.height(32.dp))
        Button(onClick = viewModel::reset, enabled = !uiState.isLoading, modifier = Modifier.fillMaxWidth()) {
            Text("Neuer Check-in")
        }
    }
}

// ─── Helpers ─────────────────────────────────────────────────────────────────



@Composable
private fun ErrorBox(message: String, onBack: () -> Unit) {
    StateMessage(
        icon = Icons.Default.ErrorOutline,
        title = "Aktion fehlgeschlagen",
        message = message,
        iconTint = MaterialTheme.colorScheme.error,
        actionLabel = "Zurück",
        onAction = onBack
    )
}

@Composable
private fun InfoRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        Text("$label: ", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TravelReasonSelector(
    selected: TravelReason,
    onSelected: (TravelReason) -> Unit,
    enabled: Boolean = true
) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            "Reisegrund",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(8.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TravelReason.entries.forEach { reason ->
                FilterChip(
                    enabled = enabled,
                    selected = selected == reason,
                    onClick = { onSelected(reason) },
                    label = { Text(travelReasonTitle(reason)) },
                    leadingIcon = { TravelReasonIcon(reason) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                        selectedLabelColor = MaterialTheme.colorScheme.primary,
                        selectedLeadingIconColor = MaterialTheme.colorScheme.primary
                    )
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            travelReasonDescription(selected) ?: "Private Reise ohne besondere Kennzeichnung.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
    }
}

@Composable
private fun TravelReasonIcon(reason: TravelReason) {
    Icon(
        imageVector = when (reason) {
            TravelReason.PRIVATE -> Icons.Default.Person
            TravelReason.BUSINESS -> Icons.Default.Work
            TravelReason.COMMUTE -> Icons.Default.Home
        },
        contentDescription = null
    )
}

private fun travelReasonTitle(reason: TravelReason): String = when (reason) {
    TravelReason.PRIVATE -> "Privat"
    TravelReason.BUSINESS -> "Geschäftlich"
    TravelReason.COMMUTE -> "Arbeitsweg"
}

private fun travelReasonDescription(reason: TravelReason): String? = when (reason) {
    TravelReason.PRIVATE -> null
    TravelReason.BUSINESS -> "Dienstfahrten"
    TravelReason.COMMUTE -> "Weg zwischen Wohnort und Arbeitsplatz"
}

/** Convert an ISO-8601 timestamp (usually UTC from the API) to the device's local time. */
private fun formatLocalTime(isoTimestamp: String): String {
    if (isoTimestamp.isBlank()) return "–"
    return try {
        val zdt = ZonedDateTime.parse(isoTimestamp)
        val local = zdt.withZoneSameInstant(ZoneId.systemDefault())
        local.format(DateTimeFormatter.ofPattern("HH:mm"))
    } catch (_: Exception) {
        // Fallback: try simple string slice
        isoTimestamp.substringAfter("T").take(5).ifBlank { "–" }
    }
}
