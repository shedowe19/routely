package de.traewelling.app.ui.components

import android.app.DatePickerDialog
import android.app.Dialog
import android.app.TimePickerDialog
import android.content.DialogInterface
import android.text.format.DateFormat
import android.view.View
import android.view.ViewGroup
import android.widget.TimePicker
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.unit.dp
import de.traewelling.app.util.journeyDateTimeInstant
import de.traewelling.app.util.journeyDateTimeLabel
import de.traewelling.app.util.journeyDateTimeSelection
import de.traewelling.app.util.journeyDateTimeZoneLabel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Native date/time dialogs keep ISO/UTC conversion outside the editable user interface. */
@Composable
internal fun JourneyDateTimeField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    suggestedValue: String? = null
) {
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    val is24Hour = DateFormat.is24HourFormat(context)
    val locale = LocalLocale.current.platformLocale
    val currentValue by rememberUpdatedState(value)
    val currentEnabled by rememberUpdatedState(enabled)
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    var activeDialog by remember { mutableStateOf<Dialog?>(null) }
    DisposableEffect(Unit) { onDispose { activeDialog?.dismiss() } }
    LaunchedEffect(enabled) { if (!enabled) activeDialog?.dismiss() }

    fun showTime(date: LocalDate, originalValue: String, initial: java.time.ZonedDateTime) {
        val dialog = TimePickerDialog(context, { _, _, _ -> }, initial.hour, initial.minute, is24Hour)
        dialog.setTitle("$label: Uhrzeit")
        dialog.setOnDismissListener { if (activeDialog === dialog) activeDialog = null }
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                if (!currentEnabled || currentValue != originalValue) {
                    dialog.dismiss()
                    return@setOnClickListener
                }
                val picker = dialog.window?.decorView?.let(::findNativeTimePicker)
                if (picker == null) {
                    dialog.setMessage("Die Uhrzeit konnte nicht gelesen werden. Bitte öffne die Auswahl erneut.")
                    return@setOnClickListener
                }
                picker.clearFocus()
                try {
                    val selected = journeyDateTimeSelection(date, picker.hour, picker.minute, zone, originalValue)
                    currentOnValueChange(selected)
                    dialog.dismiss()
                } catch (invalid: IllegalArgumentException) {
                    dialog.setMessage(invalid.message ?: "Bitte wähle eine gültige Uhrzeit.")
                }
            }
        }
        activeDialog = dialog
        dialog.show()
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        OutlinedButton(
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                if (activeDialog != null) return@OutlinedButton
                val originalValue = currentValue
                val initial = (journeyDateTimeInstant(originalValue) ?: journeyDateTimeInstant(suggestedValue)
                    ?: Instant.now()).atZone(zone)
                val dialog = DatePickerDialog(context, { _, year, month, day ->
                    if (currentEnabled && currentValue == originalValue)
                        showTime(LocalDate.of(year, month + 1, day), originalValue, initial)
                }, initial.year, initial.monthValue - 1, initial.dayOfMonth)
                dialog.setTitle("$label: Datum")
                dialog.setOnDismissListener { if (activeDialog === dialog) activeDialog = null }
                activeDialog = dialog
                dialog.show()
            }
        ) {
            Text(journeyDateTimeLabel(value, zone, is24Hour, locale)
                ?: if (value.isBlank()) "Datum und Uhrzeit auswählen" else "Zeit erneut auswählen",
                modifier = Modifier.padding(vertical = 4.dp))
        }
        Text("Zeitzone: ${journeyDateTimeZoneLabel(value, zone)}",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (value.isNotBlank()) {
            TextButton(onClick = { currentOnValueChange("") }, enabled = enabled) {
                Text("Automatische Zeit verwenden")
            }
        }
    }
}

private fun findNativeTimePicker(view: View): TimePicker? {
    if (view is TimePicker) return view
    if (view is ViewGroup) {
        for (index in 0 until view.childCount) {
            findNativeTimePicker(view.getChildAt(index))?.let { return it }
        }
    }
    return null
}
