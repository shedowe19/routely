package de.traewelling.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.traewelling.app.viewmodel.AuthViewModel
import de.traewelling.app.data.api.ApiServerUrl
import de.traewelling.app.R

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SetupScreen(viewModel: AuthViewModel) {
    val uiState    by viewModel.uiState.collectAsState()
    val uriHandler = LocalUriHandler.current
    var showToken  by remember { mutableStateOf(false) }
    var browserError by remember { mutableStateOf<String?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.8f),
                        MaterialTheme.colorScheme.background
                    )
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(64.dp))

            // Logo Section
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = CircleShape,
                border = BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                ),
                modifier = Modifier.size(96.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Train,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(42.dp)
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            Text(
                text = "Routely",
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = "Dein Zug-Check-in Begleiter",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(18.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LoginFeatureChip(Icons.Default.Train, "Check-ins")
                LoginFeatureChip(Icons.Default.Timeline, "Live")
                LoginFeatureChip(Icons.Default.People, "Community")
            }

            Spacer(Modifier.height(36.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                border = BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "Anmelden",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    
                    Spacer(Modifier.height(24.dp))

                    // Server URL
                    OutlinedTextField(
                        value = uiState.serverUrl,
                        onValueChange = viewModel::updateServerUrl,
                        label = { Text("Server-URL") },
                        placeholder = { Text("https://traewelling.de") },
                        leadingIcon = { Icon(Icons.Default.Language, null, tint = MaterialTheme.colorScheme.primary) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                    )

                    Spacer(Modifier.height(16.dp))

                    // Access Token
                    OutlinedTextField(
                        value = uiState.accessToken,
                        onValueChange = viewModel::updateAccessToken,
                        label = { Text("Access-Token") },
                        placeholder = { Text("Dein persönlicher API-Token") },
                        leadingIcon = { Icon(Icons.Default.Key, null, tint = MaterialTheme.colorScheme.primary) },
                        trailingIcon = {
                            IconButton(onClick = { showToken = !showToken }) {
                                Icon(
                                    if (showToken) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = stringResource(if (showToken) R.string.token_hide else R.string.token_show),
                                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                                )
                            }
                        },
                        visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp)
                    )

                    Spacer(Modifier.height(12.dp))

                    // Info Card
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Info, null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(6.dp))
                                Text("Wo finde ich meinen Token?",
                                    fontWeight = FontWeight.SemiBold,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurface)
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Einstellungen → Sicherheit → API-Tokens",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            TextButton(
                                onClick = {
                                    browserError = try {
                                        val url = ApiServerUrl.normalize(uiState.serverUrl)
                                        uriHandler.openUri("$url/settings#security")
                                        null
                                    } catch (e: IllegalArgumentException) {
                                        e.message ?: "Bitte eine gültige HTTPS-Server-URL eingeben."
                                    } catch (_: android.content.ActivityNotFoundException) {
                                        "Es ist keine App zum Öffnen dieses Links verfügbar."
                                    }
                                },
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text("Im Browser öffnen", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                                Spacer(Modifier.width(4.dp))
                                Icon(Icons.AutoMirrored.Filled.OpenInNew, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.secondary)
                            }
                            browserError?.let { error ->
                                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }

                    Spacer(Modifier.height(24.dp))

                    // Login-Button
                    Button(
                        onClick = viewModel::loginWithToken,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        enabled = !uiState.isLoading,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        if (uiState.isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("Anmelden", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(8.dp))
                            Icon(Icons.AutoMirrored.Filled.Login, null)
                        }
                    }
                    
                    if (uiState.error != null) {
                        Spacer(Modifier.height(16.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.08f),
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.16f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.ErrorOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    uiState.error!!,
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // Quick Selection
            TextButton(
                onClick = { viewModel.updateServerUrl("https://traewelling.de") },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary)
            ) {
                Text("Offizielle Instanz (traewelling.de) verwenden", style = MaterialTheme.typography.bodyMedium)
            }
            
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun LoginFeatureChip(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(6.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
