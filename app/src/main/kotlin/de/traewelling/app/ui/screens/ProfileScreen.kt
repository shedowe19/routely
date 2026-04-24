package de.traewelling.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import de.traewelling.app.ui.components.StatusCard
import de.traewelling.app.ui.components.TraewellingTopAppBar
import de.traewelling.app.ui.theme.DeepIndigo
import de.traewelling.app.ui.theme.TealDark
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
            TraewellingTopAppBar(
                title = "Profil",
                actions = {
                    IconButton(onClick = authViewModel::logout) {
                        Icon(Icons.AutoMirrored.Filled.Logout, "Abmelden")
                    }
                }
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
                modifier = Modifier
                    .size(96.dp)
                    .clip(CircleShape)
                    .border(3.dp, DeepIndigo.copy(alpha = 0.2f), CircleShape)
            )
        } else {
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .background(DeepIndigo.copy(alpha = 0.08f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Person, null,
                    modifier = Modifier.size(48.dp),
                    tint = DeepIndigo.copy(alpha = 0.5f))
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            user.displayName ?: user.username,
            fontWeight = FontWeight.Bold, fontSize = 24.sp
        )
        Surface(
            color = DeepIndigo.copy(alpha = 0.08f),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
            modifier = Modifier.padding(top = 4.dp)
        ) {
            Text(
                "@${user.username}",
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                color = DeepIndigo.copy(alpha = 0.8f),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
        }
        user.bio?.takeIf { it.isNotBlank() }?.let { bio ->
            Spacer(Modifier.height(12.dp))
            Text(bio, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
        }
        Spacer(Modifier.height(24.dp))
        // Key stats from the user profile object
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            StatChip("Distanz", "%.0f km".format((user.totalDistance ?: 0L) / 1000.0))
            StatChip("Zeit", formatDuration(user.totalDuration ?: 0))
            StatChip("Punkte", (user.points ?: 0).toString())
        }
    }
}

@Composable
private fun StatChip(label: String, value: String) {
    Surface(
        color = DeepIndigo.copy(alpha = 0.05f),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text(value, fontWeight = FontWeight.Bold, fontSize = 18.sp,
                color = DeepIndigo)
            Text(label, style = MaterialTheme.typography.labelSmall,
                color = DeepIndigo.copy(alpha = 0.6f))
        }
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
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(3.dp),
        colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.White)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Fahrten (letzte 28 Tage)",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround) {
                StatItem(Icons.Default.Train,    "Fahrten",  totalCount.toString())
                StatItem(Icons.Default.Schedule, "Zeit",     formatDuration(totalDuration))
            }
            if (categories.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = DeepIndigo.copy(alpha = 0.1f))
                Spacer(Modifier.height(12.dp))
                Text("Verkehrsmittel", style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = DeepIndigo.copy(alpha = 0.6f))
                Spacer(Modifier.height(8.dp))
                categories.take(4).forEach { cat ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            localiseCategory(cat.name ?: ""),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            "${cat.count ?: 0}×  ${formatDuration(cat.duration ?: 0)}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = TealDark
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
        Surface(
            color = DeepIndigo.copy(alpha = 0.1f),
            shape = CircleShape
        ) {
            Icon(icon, null, tint = DeepIndigo, modifier = Modifier.padding(8.dp).size(24.dp))
        }
        Spacer(Modifier.height(8.dp))
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
