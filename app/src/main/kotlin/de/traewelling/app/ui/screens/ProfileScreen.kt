package de.traewelling.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import de.traewelling.app.data.model.StatisticsData
import de.traewelling.app.data.model.User
import de.traewelling.app.ui.components.StateMessage
import de.traewelling.app.ui.components.StatusCard
import de.traewelling.app.ui.components.TraewellingTopAppBar
import de.traewelling.app.ui.theme.DeepIndigo
import de.traewelling.app.ui.theme.TealAccent
import de.traewelling.app.ui.theme.TealDark
import de.traewelling.app.viewmodel.AuthViewModel
import de.traewelling.app.viewmodel.ProfileViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    profileViewModel: ProfileViewModel,
    authViewModel: AuthViewModel,
    onStatusClick: (Int) -> Unit = {},
    onSettingsClick: () -> Unit = {}
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
                uiState.isLoading && uiState.user == null ->
                    StateMessage(
                        icon = Icons.Default.Person,
                        title = "Profil wird geladen",
                        message = "Statistiken und letzte Fahrten werden vorbereitet.",
                        loading = true
                    )
                uiState.error != null && uiState.user == null ->
                    StateMessage(
                        icon = Icons.Default.ErrorOutline,
                        title = "Profil konnte nicht geladen werden",
                        message = uiState.error,
                        iconTint = MaterialTheme.colorScheme.error,
                        actionLabel = "Erneut versuchen",
                        onAction = profileViewModel::refresh
                    )
                else -> {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 80.dp)
            ) {
                item {
                    uiState.user?.let { user -> ProfileHeader(user = user) }
                }
                item {
                    uiState.statistics?.let { stats -> StatisticsSection(stats) }
                }
                if (uiState.recentStatuses.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(8.dp))
                        SectionTitle(Icons.Default.Route, "Letzte Fahrten", "Deine neuesten Check-ins")
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
                        onClick = onSettingsClick,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = "Einstellungen")
                        Spacer(Modifier.width(8.dp))
                        Text("Einstellungen")
                    }

                    Spacer(Modifier.height(8.dp))
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
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Version ${de.traewelling.app.BuildConfig.VERSION_NAME}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        )
                    }
                }
            }
                }
            }
        }
    }
}

@Composable
private fun ProfileHeader(user: User) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        shape = RoundedCornerShape(28.dp),
        elevation = CardDefaults.cardElevation(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            DeepIndigo,
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.88f),
                            TealAccent.copy(alpha = 0.85f)
                        )
                    )
                )
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (user.profilePicture != null) {
                AsyncImage(
                    model = user.profilePicture,
                    contentDescription = "Avatar",
                    modifier = Modifier
                        .size(104.dp)
                        .clip(CircleShape)
                        .border(3.dp, Color.White.copy(alpha = 0.75f), CircleShape)
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(104.dp)
                        .background(Color.White.copy(alpha = 0.14f), CircleShape)
                        .border(3.dp, Color.White.copy(alpha = 0.35f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Person,
                        null,
                        modifier = Modifier.size(52.dp),
                        tint = Color.White.copy(alpha = 0.85f)
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                user.displayName ?: user.username,
                fontWeight = FontWeight.Bold,
                fontSize = 26.sp,
                color = Color.White
            )
            Surface(
                color = Color.White.copy(alpha = 0.14f),
                shape = RoundedCornerShape(999.dp),
                modifier = Modifier.padding(top = 6.dp)
            ) {
                Text(
                    "@${user.username}",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    color = Color.White.copy(alpha = 0.9f),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
            }
            user.bio?.takeIf { it.isNotBlank() }?.let { bio ->
                Spacer(Modifier.height(14.dp))
                Text(
                    bio,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.78f)
                )
            }
            Spacer(Modifier.height(24.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatChip("Distanz", "%.0f km".format((user.totalDistance ?: 0L) / 1000.0), Modifier.weight(1f))
                StatChip("Zeit", formatDuration(user.totalDuration ?: 0), Modifier.weight(1f))
                if (user.pointsEnabled != false) {
                    StatChip("Punkte", (user.points ?: 0).toString(), Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun StatChip(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = Color.White.copy(alpha = 0.13f),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f))
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text(value, fontWeight = FontWeight.Bold, fontSize = 18.sp,
                color = Color.White)
            Text(label, style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.72f))
        }
    }
}

@Composable
private fun StatisticsSection(stats: StatisticsData) {
    val categories = stats.categories ?: emptyList()
    if (categories.isEmpty()) return

    val totalCount    = categories.sumOf { it.count ?: 0 }
    val totalDuration = categories.sumOf { it.duration ?: 0 }

    Card(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        shape = RoundedCornerShape(22.dp),
        elevation = CardDefaults.cardElevation(5.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
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
                HorizontalDivider(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
                Spacer(Modifier.height(12.dp))
                Text("Verkehrsmittel", style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
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
private fun SectionTitle(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
            shape = CircleShape
        ) {
            Icon(icon, null, modifier = Modifier.padding(8.dp).size(18.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
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
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
            shape = CircleShape
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(8.dp).size(24.dp))
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
