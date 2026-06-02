package de.traewelling.app.ui.screens

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.traewelling.app.ui.components.TraewellingTopAppBar
import de.traewelling.app.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit
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
