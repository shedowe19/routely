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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import de.traewelling.app.data.model.User
import de.traewelling.app.ui.components.StateMessage
import de.traewelling.app.ui.components.StatusCard
import de.traewelling.app.ui.components.TraewellingTopAppBar
import de.traewelling.app.ui.theme.DeepIndigo
import de.traewelling.app.ui.theme.TealAccent
import de.traewelling.app.viewmodel.UserProfileViewModel

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
            lastVisible >= uiState.statuses.size - 3 && uiState.hasMore && !uiState.isLoading
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
                        viewModel.reset()
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
                            UserSectionTitle(Icons.Default.Route, "Fahrten", "Sichtbare Check-ins dieses Profils")
                        }
                        items(uiState.statuses, key = { it.id }) { status ->
                            StatusCard(
                                status = status,
                                onLike = {},
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

            Spacer(Modifier.height(16.dp))
            when {
                user.followPending == true -> {
                    OutlinedButton(
                        onClick = onToggleFollow,
                        enabled = !isFollowLoading,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.35f))
                    ) {
                        if (isFollowLoading) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
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
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.35f))
                    ) {
                        if (isFollowLoading) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
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
                        enabled = !isFollowLoading,
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = DeepIndigo)
                    ) {
                        if (isFollowLoading) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = DeepIndigo)
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
                UserStatChip("Distanz", "%.0f km".format((user.totalDistance ?: 0L) / 1000.0), Modifier.weight(1f))
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
private fun UserSectionTitle(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String) {
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

private fun formatUserDuration(minutes: Int): String {
    val hours = minutes / 60
    val mins  = minutes % 60
    return if (hours > 0) "${hours}h ${mins}m" else "${mins}m"
}
