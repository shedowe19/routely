package de.traewelling.app.ui.components

import android.text.format.DateFormat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import de.traewelling.app.viewmodel.DepartureSearchTime
import java.time.Instant
import java.time.ZoneId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DepartureSearchTimeSelector(
    timeMillis: Long?,
    onSelected: (Long?) -> Unit,
    enabled: Boolean = true
) {
    val zone = ZoneId.systemDefault()
    val context = LocalContext.current
    var editingMillis by remember { mutableLongStateOf(0L) }
    var selectedDateMillis by remember { mutableLongStateOf(0L) }
    var showDate by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }

    Surface(
        onClick = {
            editingMillis = timeMillis ?: System.currentTimeMillis()
            showDate = true
        },
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = RoundedCornerShape(10.dp)
            ) {
                Box(
                    modifier = Modifier.size(34.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.CalendarMonth, null, modifier = Modifier.size(18.dp))
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "Abfahrt ab",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    DepartureSearchTime.label(timeMillis, zone),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            if (timeMillis != null) {
                TextButton(
                    enabled = enabled,
                    onClick = { onSelected(null) },
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Text("Jetzt")
                }
            }
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    }

    if (showDate) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = DepartureSearchTime.datePickerMillis(editingMillis, zone)
        )
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(enabled = state.selectedDateMillis != null && enabled, onClick = {
                    state.selectedDateMillis?.let {
                        selectedDateMillis = it
                        showDate = false
                        showTime = true
                    }
                }) { Text("Weiter") }
            },
            dismissButton = { TextButton(onClick = { showDate = false }) { Text("Abbrechen") } }
        ) {
            DatePicker(state, title = { Text("Datum der Abfahrt", Modifier.padding(24.dp)) })
        }
    }
    if (showTime) {
        val initial = Instant.ofEpochMilli(editingMillis).atZone(zone)
        val state = rememberTimePickerState(initial.hour, initial.minute, DateFormat.is24HourFormat(context))
        var error by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { showTime = false },
            title = { Text("Uhrzeit der Abfahrt") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("Zeitzone: ${zone.id}", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(12.dp))
                    TimePicker(state)
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(enabled = enabled, onClick = {
                    // Keep an already chosen occurrence of a repeated clock time. New choices use the earlier offset.
                    val sameSelection = timeMillis != null &&
                        selectedDateMillis == DepartureSearchTime.datePickerMillis(editingMillis, zone) &&
                        state.hour == initial.hour && state.minute == initial.minute
                    runCatching {
                        DepartureSearchTime.fromLocalSelection(selectedDateMillis, state.hour, state.minute,
                            zone, initial.offset.takeIf { sameSelection })
                    }.onSuccess {
                        onSelected(it)
                        showTime = false
                    }.onFailure { error = it.message ?: "Bitte wähle eine gültige Uhrzeit." }
                }) { Text("Abfahrten suchen") }
            },
            dismissButton = { TextButton(onClick = { showTime = false }) { Text("Abbrechen") } }
        )
    }
}
