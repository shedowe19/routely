package de.traewelling.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import de.traewelling.app.data.model.User
import de.traewelling.app.ui.components.StateMessage
import de.traewelling.app.ui.components.StatusCard
import de.traewelling.app.ui.components.TraewellingTopAppBar
import de.traewelling.app.viewmodel.UserProfileViewModel
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserProfileScreen(
    username: String,
    viewModel: UserProfileViewModel,
    onBack: () -> Unit,
    onStatusClick: (Int) -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()

    LaunchedEffect(username) {
        viewModel.loadUserProfile(username)
    }

    // Auto-load more statuses
    val shouldLoadMore = remember {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible >= uiState.statuses.size - 3 && uiState.hasMore && !uiState.isLoading && uiState.error == null
        }
    }
    LaunchedEffect(shouldLoadMore.value) {
        if (shouldLoadMore.value) viewModel.loadMoreStatuses()
    }

    Scaffold(
        topBar = {
            TraewellingTopAppBar(
                title = "@$username",
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.reset(username)
                        onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(Modifier.fillMaxSize().padding(innerPadding)) {
            when {
                uiState.isLoading && uiState.user == null ->
                    StateMessage(
                        icon = Icons.Default.PersonSearch,
                        title = "Profil wird geladen",
                        message = "Nutzerprofil und sichtbare Fahrten werden vorbereitet.",
                        loading = true
                    )
                uiState.error != null && uiState.user == null ->
                    StateMessage(
                        icon = Icons.Default.ErrorOutline,
                        title = "Profil konnte nicht geladen werden",
                        message = uiState.error,
                        iconTint = MaterialTheme.colorScheme.error,
                        actionLabel = "Erneut versuchen",
                        onAction = viewModel::refresh
                    )
                else -> {
                uiState.error?.let { error ->
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(error, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = viewModel::refresh) { Text("Erneut versuchen") }
                    }
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 80.dp)
                ) {
                    // Profile header
                    item {
                        uiState.user?.let { user ->
                            UserProfileHeader(
                                user = user,
                                isFollowLoading = uiState.isFollowLoading,
                                onToggleFollow = viewModel::toggleFollow
                            )
                        }
                    }
                    // Status list header
                    if (uiState.statuses.isNotEmpty()) {
                        item {
                            Spacer(Modifier.height(8.dp))
                            UserSectionTitle("Fahrten", "Sichtbare Check-ins dieses Profils")
                        }
                        items(uiState.statuses, key = { it.id }) { status ->
                            StatusCard(
                                status = status,
                                onLike = null,
                                onStatusClick = { onStatusClick(status.id) }
                            )
                        }
                    } else if (!uiState.isLoading) {
                        item {
                            StateMessage(
                                icon = Icons.Default.Train,
                                title = "Keine Fahrten sichtbar",
                                message = "Dieses Profil hat aktuell keine sichtbaren Fahrten.",
                                modifier = Modifier.fillMaxWidth().height(220.dp)
                            )
                        }
                    }
                    // Loading indicator at bottom
                    if (uiState.isLoading && uiState.statuses.isNotEmpty()) {
                        item {
                            Box(Modifier.fillMaxWidth().padding(16.dp),
                                contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(modifier = Modifier.size(32.dp))
                            }
                        }
                    }
                }
                }
            }
        }
    }
}

@Composable
private fun UserProfileHeader(
    user: User,
    isFollowLoading: Boolean,
    onToggleFollow: () -> Unit
) {
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
                color = MaterialTheme.colorScheme.onSurface
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
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }

            Spacer(Modifier.height(14.dp))
            when {
                user.followPending == true -> {
                    OutlinedButton(
                        onClick = onToggleFollow,
                        enabled = false,
                        border = BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant
                        )
                    ) {
                        if (isFollowLoading) {
                            CircularProgressIndicator(
                                Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Icon(Icons.Default.HourglassTop, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Angefragt")
                        }
                    }
                }
                user.following == true -> {
                    OutlinedButton(
                        onClick = onToggleFollow,
                        enabled = !isFollowLoading,
                        border = BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant
                        )
                    ) {
                        if (isFollowLoading) {
                            CircularProgressIndicator(
                                Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Icon(Icons.Default.PersonRemove, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Entfolgen")
                        }
                    }
                }
                else -> {
                    Button(
                        onClick = onToggleFollow,
                        enabled = !isFollowLoading
                    ) {
                        if (isFollowLoading) {
                            CircularProgressIndicator(
                                Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Icon(Icons.Default.PersonAdd, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Folgen")
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                UserStatChip("Distanz", formatUserDistanceKm(user.totalDistance ?: 0L), Modifier.weight(1f))
                UserStatChip("Zeit", formatUserDuration(user.totalDuration ?: 0), Modifier.weight(1f))
                if (user.pointsEnabled != false) {
                    UserStatChip("Punkte", (user.points ?: 0).toString(), Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun UserStatChip(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.75f),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
        )
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)
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
}

@Composable
private fun UserSectionTitle(title: String, subtitle: String) {
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

private fun formatUserDuration(minutes: Int): String {
    val hours = minutes / 60
    val mins  = minutes % 60
    return if (hours > 0) "${hours}h ${mins}m" else "${mins}m"
}

private fun formatUserDistanceKm(meters: Long): String =
    String.format(Locale.GERMANY, "%,.0f km", meters / 1000.0)
