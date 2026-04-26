package de.traewelling.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape

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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.traewelling.app.ui.theme.DeepIndigo
import de.traewelling.app.ui.theme.TealAccent
import de.traewelling.app.viewmodel.AuthViewModel

@Composable
fun SetupScreen(viewModel: AuthViewModel) {
    val uiState    by viewModel.uiState.collectAsState()
    val uriHandler = LocalUriHandler.current
    var showToken  by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(DeepIndigo, Color(0xFF1A237E))
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
                color = Color.White.copy(alpha = 0.1f),
                shape = CircleShape,
                modifier = Modifier.size(100.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Train,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(48.dp)
                    )
                }
            }
            
            Spacer(Modifier.height(16.dp))
            
            Text(
                text = "Träwelling",
                fontSize = 36.sp,
                fontWeight = FontWeight.Black,
                color = Color.White,
                letterSpacing = 1.sp
            )
            Text(
                text = "Dein Zug-Check-in Begleiter",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.7f)
            )

            Spacer(Modifier.height(48.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "Anmelden",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = DeepIndigo
                    )
                    
                    Spacer(Modifier.height(24.dp))

                    // Server URL
                    OutlinedTextField(
                        value = uiState.serverUrl,
                        onValueChange = viewModel::updateServerUrl,
                        label = { Text("Server-URL") },
                        placeholder = { Text("https://traewelling.de") },
                        leadingIcon = { Icon(Icons.Default.Language, null, tint = DeepIndigo) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                    )

                    Spacer(Modifier.height(16.dp))

                    // Access Token
                    OutlinedTextField(
                        value = uiState.accessToken,
                        onValueChange = viewModel::updateAccessToken,
                        label = { Text("Access-Token") },
                        placeholder = { Text("Dein persönlicher API-Token") },
                        leadingIcon = { Icon(Icons.Default.Key, null, tint = DeepIndigo) },
                        trailingIcon = {
                            IconButton(onClick = { showToken = !showToken }) {
                                Icon(
                                    if (showToken) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = null,
                                    tint = DeepIndigo.copy(alpha = 0.5f)
                                )
                            }
                        },
                        visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                    )

                    Spacer(Modifier.height(12.dp))

                    // Info Card
                    Surface(
                        color = DeepIndigo.copy(alpha = 0.05f),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Info, null,
                                    modifier = Modifier.size(16.dp),
                                    tint = DeepIndigo)
                                Spacer(Modifier.width(6.dp))
                                Text("Wo finde ich meinen Token?",
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = DeepIndigo)
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Einstellungen → Sicherheit → API-Tokens",
                                style = MaterialTheme.typography.bodySmall,
                                color = DeepIndigo.copy(alpha = 0.7f)
                            )
                            TextButton(
                                onClick = {
                                    val url = uiState.serverUrl.trimEnd('/').ifBlank { "https://traewelling.de" }
                                    uriHandler.openUri("$url/settings#security")
                                },
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text("Im Browser öffnen", style = MaterialTheme.typography.labelMedium, color = TealAccent)
                                Spacer(Modifier.width(4.dp))
                                Icon(Icons.Default.OpenInNew, null, modifier = Modifier.size(14.dp), tint = TealAccent)
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
                        colors = ButtonDefaults.buttonColors(containerColor = DeepIndigo)
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
                            Icon(Icons.Default.Login, null)
                        }
                    }
                    
                    if (uiState.error != null) {
                        Spacer(Modifier.height(16.dp))
                        Text(
                            uiState.error!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // Quick Selection
            TextButton(
                onClick = { viewModel.updateServerUrl("https://traewelling.de") },
                colors = ButtonDefaults.textButtonColors(contentColor = Color.White.copy(alpha = 0.8f))
            ) {
                Text("Offizielle Instanz (traewelling.de) verwenden", style = MaterialTheme.typography.bodyMedium)
            }
            
            Spacer(Modifier.height(32.dp))
        }
    }
}
