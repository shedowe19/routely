package de.traewelling.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import de.traewelling.app.data.model.StatisticsData
import de.traewelling.app.data.model.User
import de.traewelling.app.viewmodel.AuthViewModel
import de.traewelling.app.viewmodel.ProfileViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    profileViewModel: ProfileViewModel,
    authViewModel: AuthViewModel,
    onStatusClick: (Int) -> Unit = {}
) {
    val uiState by profileViewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        profileViewModel.loadProfile()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text("Profil", fontWeight = FontWeight.Bold) 
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { innerPadding ->
        Box(Modifier.padding(innerPadding).fillMaxSize()) {

    when {
        uiState.isLoading && uiState.user == null -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        uiState.error != null && uiState.user == null -> {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.ErrorOutline, null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(12.dp))
                    Text(uiState.error!!, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = profileViewModel::refresh) { Text("Erneut versuchen") }
                }
            }
        }
        else -> {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 80.dp)
            ) {
                item {
                    uiState.user?.let { user -> ProfileHeader(user = user) }
                }
                item {
                    uiState.statistics?.let { stats -> StatisticsSection(uiState.user, stats) }
                }
                if (uiState.recentStatuses.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Letzte Fahrten",
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    items(uiState.recentStatuses, key = { it.id }) { status ->
                        StatusCard(
                            status = status,
                            onLike = {},
                            onStatusClick = { onStatusClick(status.id) }
                        )
                    }
                }
                item {
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(
                        onClick = { authViewModel.logout() },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Logout, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Abmelden")
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}
}
}

@Composable
private fun ProfileHeader(user: User) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (user.profilePicture != null) {
            AsyncImage(
                model = user.profilePicture,
                contentDescription = "Avatar",
                modifier = Modifier.size(96.dp).clip(CircleShape)
            )
        } else {
            Icon(Icons.Default.AccountCircle, null,
                modifier = Modifier.size(96.dp),
                tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(12.dp))
        Text(
            user.displayName ?: user.username,
            fontWeight = FontWeight.Bold, fontSize = 22.sp
        )
        Text(
            "@${user.username}",
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            style = MaterialTheme.typography.bodyMedium
        )
        user.bio?.takeIf { it.isNotBlank() }?.let { bio ->
            Spacer(Modifier.height(8.dp))
            Text(bio, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
        }
        Spacer(Modifier.height(16.dp))
        // Key stats from the user profile object
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            StatChip("Distanz", "%.0f km".format((user.totalDistance ?: 0L) / 1000.0))
            StatChip("Zeit", formatDuration(user.totalDuration ?: 0))
            StatChip("Punkte", (user.points ?: 0).toString())
        }
    }
    HorizontalDivider()
}

@Composable
private fun StatChip(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontWeight = FontWeight.Bold, fontSize = 18.sp,
            color = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
    }
}

@Composable
private fun StatisticsSection(user: User?, stats: StatisticsData) {
    val categories = stats.categories ?: emptyList()
    if (categories.isEmpty()) return

    val totalCount    = categories.sumOf { it.count ?: 0 }
    val totalDuration = categories.sumOf { it.duration ?: 0 }

    Card(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Fahrten (letzte 28 Tage)",
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround) {
                StatItem(Icons.Default.Train,    "Fahrten",  totalCount.toString())
                StatItem(Icons.Default.Schedule, "Zeit",     formatDuration(totalDuration))
            }
            if (categories.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Text("Verkehrsmittel", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                Spacer(Modifier.height(4.dp))
                categories.take(4).forEach { cat ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            localiseCategory(cat.name ?: ""),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            "${cat.count ?: 0}×  ${formatDuration(cat.duration ?: 0)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(4.dp))
        Text(value, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
    }
}

private fun localiseCategory(cat: String) = when (cat) {
    "nationalExpress" -> "Fernverkehr (ICE/IC)"
    "national"        -> "Fernverkehr"
    "regionalExp"     -> "RegionalExpress"
    "regional"        -> "Regional (RE/RB)"
    "suburban"        -> "S-Bahn"
    "subway"          -> "U-Bahn"
    "tram"            -> "Straßenbahn"
    "bus"             -> "Bus"
    "ferry"           -> "Fähre"
    else              -> cat.replaceFirstChar { it.uppercase() }
}

private fun formatDuration(minutes: Int): String {
    val hours = minutes / 60
    val mins  = minutes % 60
    return if (hours > 0) "${hours}h ${mins}m" else "${mins}m"
}
