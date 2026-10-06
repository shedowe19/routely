package de.traewelling.app.ui.screens

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import de.traewelling.app.ui.components.TraewellingTopAppBar
import de.traewelling.app.util.BatteryOptimization
import de.traewelling.app.viewmodel.SettingsViewModel

@Composable
private fun CompanionToggle(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onRequestGpsPermission: () -> Unit,
    onStartRideRecognition: () -> Unit,
    onStopRideRecognition: () -> Unit,
    onOpenLiveUpdateSettings: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TraewellingTopAppBar(
                title = "Einstellungen",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text("Reisebegleitung", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        CompanionToggle(
                            title = "Fahrten automatisch erkennen",
                            description = "Sucht passende Fahrten, bis du einen Check-in startest. Du bestätigst Fahrt und Ziel selbst.",
                            checked = uiState.rideRecognitionEnabled,
                            onCheckedChange = { if (it) onStartRideRecognition() else onStopRideRecognition() }
                        )
                        Text(
                            "Die Suche verwendet präzisen Standort, auch bei ausgeschaltetem Display. Für Stationen in der Nähe wird dein Standort an deinen Träwelling-Server gesendet. Du kannst die Suche jederzeit beenden.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        HorizontalDivider()
                        CompanionToggle(
                            "Reiseänderungen melden",
                            "Informiert über Gleiswechsel, entfallene Halte und größere Verspätungsänderungen.",
                            uiState.tripChangeAlertsEnabled,
                            viewModel::setTripChangeAlertsEnabled
                        )
                        CompanionToggle(
                            "Änderungen auch ansagen",
                            "Spricht wichtige Änderungen, wenn „Haltestellen ansagen“ aktiviert ist.",
                            uiState.tripChangeSpeechEnabled,
                            viewModel::setTripChangeSpeechEnabled,
                            enabled = uiState.tripChangeAlertsEnabled
                        )
                        HorizontalDivider()
                        CompanionToggle(
                            "Live-Reisefortschritt",
                            "Zeigt nächste Station und verbleibende Halte. Auf unterstützten Geräten als Android Live Update.",
                            uiState.liveProgressEnabled,
                            viewModel::setLiveProgressEnabled
                        )
                        CompanionToggle(
                            "Reisedetails auf dem Sperrbildschirm",
                            "Zeigt Linie, Ziel, nächsten Halt und Gleis auch auf dem gesperrten Gerät.",
                            uiState.lockScreenDetailsEnabled,
                            viewModel::setLockScreenDetailsEnabled
                        )
                        OutlinedButton(onClick = onOpenLiveUpdateSettings, modifier = Modifier.fillMaxWidth()) {
                            Text("Android-Anzeigeeinstellungen")
                        }
                    }
                }
            }
            item {
                Text(
                    text = "Erscheinungsbild",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    elevation = CardDefaults.cardElevation(5.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Palette, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "App-Theme",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "Passe Routely an deine Umgebung an.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))

                        ThemeSelectionDropdown(
                            selectedTheme = uiState.appTheme,
                            onThemeSelected = { viewModel.setAppTheme(it) }
                        )
                    }
                }
            }

            item {
                Text(
                    text = "Stationsansagen mit GPS",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(8.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(Icons.Default.MyLocation, null, tint = MaterialTheme.colorScheme.primary)
                            Column(Modifier.weight(1f)) {
                                Text("GPS verwenden", fontWeight = FontWeight.SemiBold)
                                Text(
                                    "Erkennt deine Annäherung an den nächsten Halt. Ohne brauchbaren Standort wird der Fahrplan verwendet.",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Switch(
                                checked = uiState.gpsTrackingEnabled,
                                onCheckedChange = { enabled ->
                                    viewModel.setGpsTrackingEnabled(enabled)
                                    if (enabled) onRequestGpsPermission()
                                }
                            )
                        }
                        if (uiState.gpsTrackingEnabled) {
                            Spacer(Modifier.height(12.dp))
                            Text("Entfernung für die Ansage", style = MaterialTheme.typography.labelMedium)
                            SettingsDropdownMenu(
                                items = listOf(300, 500, 1000, 2000).map { it.toString() to "$it Meter" },
                                selectedItem = uiState.announcementRadiusMeters.toString(),
                                onItemSelected = { viewModel.setAnnouncementRadiusMeters(it.toIntOrNull() ?: 0) },
                                defaultLabel = "Automatisch (300–2.000 Meter)"
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Die Automatik passt die Entfernung an deine Geschwindigkeit an. Für GPS ist die präzise Standortfreigabe erforderlich.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            OutlinedButton(onClick = onRequestGpsPermission, modifier = Modifier.fillMaxWidth()) {
                                Text("Standort freigeben")
                            }
                        }
                        Text(
                            "Aktiviere unten „Haltestellen ansagen“ für die Sprachausgabe.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item { BackgroundTrackingBatteryCard() }

            item {
                Text(
                    text = "Sprachausgabe (TTS)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    elevation = CardDefaults.cardElevation(5.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.10f)),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.RecordVoiceOver, null, tint = MaterialTheme.colorScheme.secondary)
                                    Spacer(Modifier.width(10.dp))
                                    Column {
                                        Text(
                                            "Haltestellen ansagen",
                                            fontWeight = FontWeight.SemiBold,
                                            style = MaterialTheme.typography.titleMedium
                                        )
                                        Text(
                                            "Nächste Haltestelle kurz vor Ankunft vorlesen",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                            Switch(
                                checked = uiState.isTtsEnabled,
                                onCheckedChange = { viewModel.toggleTts(it) }
                            )
                        }

                        if (uiState.isTtsEnabled) {
                            Spacer(Modifier.height(16.dp))

                            Text("TTS Engine", style = MaterialTheme.typography.labelMedium)
                            SettingsDropdownMenu(
                                items = uiState.availableTtsEngines.map { it.name to it.label },
                                selectedItem = uiState.selectedTtsEngine,
                                onItemSelected = { viewModel.selectTtsEngine(it) },
                                defaultLabel = "System-Standard"
                            )

                            Spacer(Modifier.height(8.dp))

                            Text("Sprache", style = MaterialTheme.typography.labelMedium)
                            SettingsDropdownMenu(
                                items = uiState.availableLanguages.map { it.toLanguageTag() to it.displayName },
                                selectedItem = uiState.selectedTtsLanguage,
                                onItemSelected = { viewModel.selectTtsLanguage(it) },
                                defaultLabel = "System-Standard"
                            )

                            Spacer(Modifier.height(8.dp))

                            Text("Stimme", style = MaterialTheme.typography.labelMedium)
                            SettingsDropdownMenu(
                                items = uiState.availableVoices.map { it.name to (it.name.split("-").lastOrNull() ?: it.name) },
                                selectedItem = uiState.selectedTtsVoice,
                                onItemSelected = { viewModel.selectTtsVoice(it) },
                                defaultLabel = "Standardstimme"
                            )

                            Spacer(Modifier.height(16.dp))
                            Button(
                                onClick = { viewModel.testTts() },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = "Test TTS")
                                Spacer(Modifier.width(8.dp))
                                Text("Stimme testen")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BackgroundTrackingBatteryCard() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var isExempt by remember(context) { mutableStateOf(BatteryOptimization.isExempt(context)) }
    var settingsUnavailable by remember { mutableStateOf(false) }

    DisposableEffect(context, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isExempt = BatteryOptimization.isExempt(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        // The card can also enter the composition after the Activity has already resumed.
        isExempt = BatteryOptimization.isExempt(context)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Text(
        "Begleitung bei ausgeschaltetem Display",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary
    )
    Spacer(Modifier.height(8.dp))
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    if (isExempt == true) Icons.Default.BatteryFull else Icons.Default.BatterySaver,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    when (isExempt) {
                        true -> "Von Android-Akkuoptimierung ausgenommen"
                        false -> "Android-Akkuoptimierung aktiv"
                        null -> "Akkuoptimierungsstatus nicht verfügbar"
                    },
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text(
                "Damit GPS und Ansagen während deiner Fahrt auch bei ausgeschaltetem Display weiterlaufen, erlaube Routely uneingeschränkte Akkunutzung.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (isExempt != true) {
                Button(
                    onClick = {
                        isExempt = BatteryOptimization.isExempt(context)
                        if (isExempt != true) {
                            settingsUnavailable = !BatteryOptimization.requestExemption(context)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Akkuoptimierung aufheben")
                }
            }
            OutlinedButton(
                onClick = { settingsUnavailable = !BatteryOptimization.openAppSettings(context) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Android-App-Einstellungen")
            }
            Text(
                if (Build.MANUFACTURER.equals("samsung", ignoreCase = true)) {
                    "Bei Samsung zusätzlich die Akkunutzung auf „Uneingeschränkt“ setzen und Routely zu den Apps hinzufügen, die nie im Standby sind. Die Menünamen können je nach Gerät abweichen."
                } else {
                    "Prüfe in den Android-App-Einstellungen auch weitere Akku-Beschränkungen deines Geräteherstellers."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "Kann während der Reisebegleitung mehr Akku verbrauchen. Das Display bleibt ausgeschaltet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (settingsUnavailable) {
                Text(
                    "Die Android-Einstellungen konnten nicht geöffnet werden. Öffne sie manuell und suche nach Routely unter Apps und Akku.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ThemeSelectionDropdown(
    selectedTheme: String,
    onThemeSelected: (String) -> Unit
) {
    val themes = listOf(
        "LIGHT" to "Hell",
        "DARK" to "Dunkel",
        "AMOLED" to "AMOLED (Schwarz)"
    )

    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        themes.forEach { (id, label) ->
            FilterChip(
                selected = selectedTheme == id,
                onClick = { onThemeSelected(id) },
                label = { Text(label) },
                leadingIcon = {
                    Icon(
                        imageVector = when (id) {
                            "DARK" -> Icons.Default.DarkMode
                            "AMOLED" -> Icons.Default.Contrast
                            else -> Icons.Default.LightMode
                        },
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                    selectedLabelColor = MaterialTheme.colorScheme.primary,
                    selectedLeadingIconColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsDropdownMenu(
    items: List<Pair<String, String>>,
    selectedItem: String?,
    onItemSelected: (String) -> Unit,
    defaultLabel: String
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = items.find { it.first == selectedItem }?.second ?: defaultLabel

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            DropdownMenuItem(
                text = { Text(defaultLabel) },
                onClick = {
                    onItemSelected("")
                    expanded = false
                }
            )
            items.forEach { (id, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onItemSelected(id)
                        expanded = false
                    }
                )
            }
        }
    }
}
