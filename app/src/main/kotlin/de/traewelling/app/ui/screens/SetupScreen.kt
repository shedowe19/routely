package de.traewelling.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.traewelling.app.viewmodel.AuthViewModel

@Composable
fun SetupScreen(viewModel: AuthViewModel) {
    val uiState    by viewModel.uiState.collectAsState()
    val uriHandler = LocalUriHandler.current
    var showToken  by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(48.dp))

        // Logo
        Icon(
            imageVector = Icons.Default.Train,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(72.dp)
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Träwelling",
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = "Dein Zug-Check-in Begleiter",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )

        Spacer(Modifier.height(48.dp))

        // Server URL
        OutlinedTextField(
            value = uiState.serverUrl,
            onValueChange = viewModel::updateServerUrl,
            label = { Text("Server-URL") },
            placeholder = { Text("https://traewelling.de") },
            leadingIcon = { Icon(Icons.Default.Language, null) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
        )

        Spacer(Modifier.height(12.dp))

        // Access Token
        OutlinedTextField(
            value = uiState.accessToken,
            onValueChange = viewModel::updateAccessToken,
            label = { Text("Access-Token") },
            placeholder = { Text("Dein persönlicher API-Token") },
            leadingIcon = { Icon(Icons.Default.Key, null) },
            trailingIcon = {
                IconButton(onClick = { showToken = !showToken }) {
                    Icon(
                        if (showToken) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (showToken) "Token verbergen" else "Token anzeigen"
                    )
                }
            },
            visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Spacer(Modifier.height(8.dp))

        // Info-Hinweis wo man den Token findet
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Info, null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.secondary)
                    Spacer(Modifier.width(6.dp))
                    Text("Wo finde ich meinen Token?",
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.labelLarge)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Melde dich im Browser auf deiner Träwelling-Instanz an, dann:",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Einstellungen → Sicherheit → API-Tokens → Neues Token erstellen",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = {
                        val url = uiState.serverUrl.trimEnd('/').ifBlank { "https://traewelling.de" }
                        uriHandler.openUri("$url/settings#security")
                    },
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Icon(Icons.Default.OpenInBrowser, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Token-Seite öffnen", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        // Fehlermeldung
        if (uiState.error != null) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Error, null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        uiState.error!!,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        // Login-Button
        Button(
            onClick = viewModel::loginWithToken,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            enabled = !uiState.isLoading
        ) {
            if (uiState.isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                Icon(Icons.Default.Login, null)
                Spacer(Modifier.width(8.dp))
                Text("Anmelden", fontSize = 16.sp)
            }
        }

        Spacer(Modifier.height(12.dp))

        // Schnell-Auswahl offizielle Instanz
        TextButton(onClick = {
            viewModel.updateServerUrl("https://traewelling.de")
        }) {
            Text("Offizielle Instanz (traewelling.de) verwenden")
        }
    }
}
