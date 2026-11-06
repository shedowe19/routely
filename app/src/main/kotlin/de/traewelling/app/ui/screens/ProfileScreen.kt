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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import de.traewelling.app.data.model.StatisticsData
import de.traewelling.app.data.model.User
import de.traewelling.app.ui.components.StateMessage
import de.traewelling.app.ui.components.StatusCard
import de.traewelling.app.ui.components.TraewellingTopAppBar
import de.traewelling.app.viewmodel.AuthViewModel
import de.traewelling.app.viewmodel.ProfileViewModel
import java.util.Locale

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
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Default.Settings, "Einstellungen")
                    }
                    IconButton(onClick = { authViewModel.logout() }) {
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
                uiState.error?.let { error ->
                    item {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Text(error, color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = profileViewModel::refresh) { Text("Erneut versuchen") }
                        }
                    }
                }
                item {
                    uiState.user?.let { user -> ProfileHeader(user = user) }
                }
                item {
                    uiState.statistics?.let { stats -> StatisticsSection(stats) }
                }
                if (uiState.recentStatuses.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(6.dp))
                        SectionTitle("Letzte Fahrten", "Deine neuesten Check-ins")
                    }
                    items(uiState.recentStatuses, key = { it.id }) { status ->
                        StatusCard(
                            status = status,
                            onLike = null,
                            onStatusClick = { onStatusClick(status.id) }
                        )
                    }
                }
                item {
                    Spacer(Modifier.height(14.dp))
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
        shape = RoundedCornerShape(22.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
                            Color.Transparent
                        )
                    )
                )
                .padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (user.profilePicture != null) {
                AsyncImage(
                    model = user.profilePicture,
                    contentDescription = "Avatar",
                    modifier = Modifier
                        .size(86.dp)
                        .clip(CircleShape)
                        .border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f), CircleShape)
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(86.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
                        .border(
                            2.dp,
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Person,
                        null,
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                user.displayName ?: user.username,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(999.dp),
                modifier = Modifier.padding(top = 6.dp)
            ) {
                Text(
                    "@${user.username}",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium
                )
            }
            user.bio?.takeIf { it.isNotBlank() }?.let { bio ->
                Spacer(Modifier.height(10.dp))
                Text(
                    bio,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
            Spacer(Modifier.height(14.dp))

            val stats = buildList {
                add("Distanz" to formatDistanceKm(user.totalDistance ?: 0L))
                add("Zeit" to formatDuration(user.totalDuration ?: 0))
                if (user.pointsEnabled != false) add("Punkte" to (user.points ?: 0).toString())
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.75f),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                )
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    stats.forEachIndexed { index, (label, value) ->
                        if (index > 0) {
                            VerticalDivider(
                                modifier = Modifier.height(26.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                            )
                        }
                        StatCell(label = label, value = value, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun StatCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.padding(horizontal = 6.dp)
    ) {
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun StatisticsSection(stats: StatisticsData) {
    val categories = stats.categories ?: emptyList()
    if (categories.isEmpty()) return

    val totalCount    = categories.sumOf { it.count ?: 0 }
    val totalDuration = categories.sumOf { it.duration ?: 0 }

    val maxCount = categories.maxOfOrNull { it.count ?: 0 }?.coerceAtLeast(1) ?: 1

    Card(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        shape = RoundedCornerShape(18.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "Fahrten (letzte 28 Tage)",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                StatItem(Icons.Default.Train, "Fahrten", totalCount.toString())
                StatItem(Icons.Default.Schedule, "Zeit", formatDuration(totalDuration))
            }
            Spacer(Modifier.height(14.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Spacer(Modifier.height(12.dp))
            Text(
                "Verkehrsmittel",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            categories.take(4).forEach { cat ->
                val count = cat.count ?: 0
                Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            localiseCategory(cat.name ?: ""),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "${count}× · ${formatDuration(cat.duration ?: 0)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(5.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .background(
                                MaterialTheme.colorScheme.surfaceContainerHigh,
                                RoundedCornerShape(2.dp)
                            )
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth((count.toFloat() / maxCount).coerceIn(0f, 1f))
                                .height(4.dp)
                                .background(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
                                    RoundedCornerShape(2.dp)
                                )
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 9.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
            shape = CircleShape
        ) {
            Icon(
                icon,
                null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(7.dp).size(20.dp)
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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

private fun formatDistanceKm(meters: Long): String =
    String.format(Locale.GERMANY, "%,.0f km", meters / 1000.0)
