package de.traewelling.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import de.traewelling.app.util.PreferenceStoragePhase
import de.traewelling.app.util.PreferenceStorageState
import de.traewelling.app.util.canConfirmStorageReset

/** A separate modal window also blocks touches, Back and accessibility focus below it. */
@Composable
internal fun StorageRecoveryDialog(
    state: PreferenceStorageState,
    actionInProgress: Boolean,
    onRetry: () -> Unit,
    onReset: (Long) -> Unit
) {
    var resetFailureId by remember { mutableStateOf<Long?>(null) }
    val busy = state.isBusy || state.isReady || actionInProgress
    LaunchedEffect(state.phase, state.failureId) {
        if (!canConfirmStorageReset(resetFailureId, state)) resetFailureId = null
    }
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = true
        )
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp)
                    .semantics { paneTitle = "Lokale App-Daten" },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically)
            ) {
                if (busy) CircularProgressIndicator()
                Text(
                    text = when {
                        state.phase == PreferenceStoragePhase.CORRUPTED -> "App-Daten sind beschädigt"
                        !busy -> "App-Daten gerade nicht verfügbar"
                        state.phase == PreferenceStoragePhase.RETRYING -> "App-Daten werden erneut geprüft"
                        else -> "App wird vorbereitet"
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { heading() }
                )
                Text(
                    text = when {
                        state.phase == PreferenceStoragePhase.CORRUPTED ->
                            "Die gespeicherten Daten lassen sich nicht sicher lesen. Du kannst es erneut versuchen. Es wurde nichts automatisch gelöscht."
                        !busy && state.hasConfirmedSnapshot ->
                            "Deine zuletzt gelesenen Einstellungen bleiben erhalten. Änderungen und Begleitung sind pausiert, bis die App wieder sicher auf ihre Daten zugreifen kann."
                        !busy ->
                            "Routely kann die gespeicherten Daten gerade nicht sicher lesen oder speichern. Versuche es erneut, sobald wieder genügend Speicher verfügbar ist."
                        else -> "Deine gespeicherten Daten werden geprüft."
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center
                )
                if (!state.isReady && !state.isBusy) {
                    Button(onClick = onRetry, enabled = !actionInProgress) { Text("Erneut versuchen") }
                    if (state.canReset) {
                        TextButton(
                            onClick = { resetFailureId = state.failureId },
                            enabled = !actionInProgress
                        ) { Text("Lokale App-Daten zurücksetzen") }
                    }
                }
            }
        }
        if (canConfirmStorageReset(resetFailureId, state)) {
            val expectedFailureId = resetFailureId!!
            AlertDialog(
                onDismissRequest = { resetFailureId = null },
                title = { Text("Lokale App-Daten zurücksetzen?") },
                text = {
                    Text("Dabei werden deine Anmeldung, Einstellungen und die lokale aktive Begleitung entfernt. Du musst dich danach erneut anmelden. Deine auf Träwelling gespeicherten Fahrten bleiben erhalten.")
                },
                confirmButton = {
                    TextButton(
                        onClick = { resetFailureId = null; onReset(expectedFailureId) },
                        enabled = !actionInProgress
                    ) { Text("Zurücksetzen") }
                },
                dismissButton = { TextButton(onClick = { resetFailureId = null }) { Text("Abbrechen") } }
            )
        }
    }
}
