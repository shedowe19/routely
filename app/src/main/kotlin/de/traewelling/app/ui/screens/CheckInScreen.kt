package de.traewelling.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.traewelling.app.data.model.*
import de.traewelling.app.ui.components.TraewellingTopAppBar
import de.traewelling.app.viewmodel.CheckInStep
import de.traewelling.app.viewmodel.CheckInUiState
import de.traewelling.app.viewmodel.CheckInViewModel
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

@Composable
fun CheckInScreen(viewModel: CheckInViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    
    val title = when (uiState.step) {
        CheckInStep.STATION -> "Check-in"
        CheckInStep.DEPARTURES -> "Abfahrten — ${uiState.selectedStation?.name ?: ""}"
        CheckInStep.DESTINATION -> "Ziel wählen — ${uiState.selectedDeparture?.line?.name ?: ""}"
        CheckInStep.CONFIRM -> "Details bestätigen"
        CheckInStep.SUCCESS -> "Eingecheckt!"
    }
    
    val showBack = uiState.step != CheckInStep.STATION && uiState.step != CheckInStep.SUCCESS

    Scaffold(
        topBar = {
            TraewellingTopAppBar(
                title = title,
                navigationIcon = {
                    if (showBack) {
                        IconButton(onClick = viewModel::goBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück")
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(Modifier.padding(innerPadding).fillMaxSize()) {
            when (uiState.step) {
                CheckInStep.STATION     -> StationSearchStep(viewModel, uiState)
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
private fun StationSearchStep(viewModel: CheckInViewModel, uiState: CheckInUiState) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.height(16.dp))

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
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .focusRequester(focusRequester),
            singleLine = true
        )

        uiState.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }

        when {
            uiState.stationQuery.length < 2 ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Train, null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f))
                        Spacer(Modifier.height(12.dp))
                        Text("Mindestens 2 Zeichen eingeben",
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                    }
                }
            uiState.searchResults.isEmpty() && !uiState.isLoading ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Kein Bahnhof gefunden",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                }
            else ->
                LazyColumn(Modifier.fillMaxSize()) {
                    items(uiState.searchResults.size) { index ->
                        val station = uiState.searchResults[index]
                        ListItem(
                            headlineContent = {
                                Text(station.name ?: "–", fontWeight = FontWeight.Medium)
                            },
                            supportingContent = station.rilIdentifier?.let { { Text("RIL: $it") } },
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

// ─── Step 2: Abfahrten ───────────────────────────────────────────────────────

@Composable
private fun DeparturesStep(viewModel: CheckInViewModel, uiState: CheckInUiState) {
    Column(Modifier.fillMaxSize()) {

        when {
            uiState.isLoading ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text("Lade Abfahrten…",
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    }
                }
            uiState.error != null ->
                ErrorBox(uiState.error!!, viewModel::goBack)
            uiState.departures.isEmpty() ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Keine Abfahrten gefunden")
                }
            else ->
                LazyColumn(Modifier.fillMaxSize()) {
                    items(uiState.departures, key = { it.tripId }) { departure ->
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
    val timeRaw   = departure.plannedWhen ?: ""
    val time      = formatLocalTime(timeRaw)
    val delayed   = departure.delay != null && departure.delay > 0
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
                    Text(time, style = MaterialTheme.typography.bodySmall,
                        color = if (delayed) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    if (delayed && departure.delay != null) {
                        Spacer(Modifier.width(4.dp))
                        Text("+${departure.delay}min",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error)
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

// ─── Step 3: Ziel wählen (flat StopStation) ──────────────────────────────────

@Composable
private fun DestinationStep(viewModel: CheckInViewModel, uiState: CheckInUiState) {
    val stopovers = uiState.filteredDestinations
    val lineName  = uiState.selectedDeparture?.line?.name ?: ""

    Column(Modifier.fillMaxSize()) {

        when {
            uiState.isLoading ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            stopovers.isEmpty() ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Keine Zwischenhalte verfügbar")
                }
            else ->
                LazyColumn(Modifier.fillMaxSize()) {
                    items(stopovers, key = { "${it.id}_${it.name}" }) { stop ->
                        ListItem(
                            headlineContent = {
                                Text(stop.name ?: "–")    // name is directly on StopStation!
                            },
                            supportingContent = {
                                val arr = stop.arrivalPlanned ?: stop.arrival
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
    val depTime = formatLocalTime(dep?.plannedWhen ?: "")

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    InfoRow(Icons.Default.Train,       "Linie",   dep?.line?.name ?: "–")
                    InfoRow(Icons.Default.TripOrigin,  "Von",     uiState.selectedStation?.name ?: "–")
                    InfoRow(Icons.Default.LocationOn,  "Nach",    uiState.selectedDestination?.name ?: "–")
                    InfoRow(Icons.Default.Schedule,    "Abfahrt", depTime)
                    dep?.platform?.takeIf { it.isNotBlank() }?.let {
                        InfoRow(Icons.Default.ConfirmationNumber, "Gleis", it)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = uiState.statusBody,
                onValueChange = viewModel::updateStatusBody,
                label = { Text("Statusmeldung (optional)") },
                placeholder = { Text("Was machst du auf dieser Reise?") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2
            )
            Spacer(Modifier.height(16.dp))
            
            Text("Zeiten anpassen (ISO-Format)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = uiState.manualDeparture,
                    onValueChange = viewModel::updateManualDeparture,
                    label = { Text("Abfahrt") },
                    modifier = Modifier.weight(1f),
                    textStyle = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = uiState.manualArrival,
                    onValueChange = viewModel::updateManualArrival,
                    label = { Text("Ankunft") },
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
            Spacer(Modifier.weight(1f))
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
        Modifier.fillMaxSize().statusBarsPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.CheckCircle, null, modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text("Erfolgreich eingecheckt!",
            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        uiState.checkInResult?.points?.points?.let { pts ->
            Spacer(Modifier.height(8.dp))
            Text("+$pts Punkte erhalten!",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.secondary)
        }
        Spacer(Modifier.height(32.dp))
        Button(onClick = viewModel::reset, modifier = Modifier.fillMaxWidth()) {
            Text("Neuer Check-in")
        }
    }
}

// ─── Helpers ─────────────────────────────────────────────────────────────────



@Composable
private fun ErrorBox(message: String, onBack: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.ErrorOutline, null, modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(12.dp))
            Text(message, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onBack) { Text("Zurück") }
        }
    }
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
