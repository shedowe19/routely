package de.traewelling.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import coil.compose.AsyncImage
import de.traewelling.app.data.model.Status
import de.traewelling.app.viewmodel.FeedType
import de.traewelling.app.viewmodel.FeedViewModel
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(
    viewModel: FeedViewModel,
    onUserClick: (String) -> Unit = {},
    onStatusClick: (Int) -> Unit = {}
) {
    val uiState     by viewModel.uiState.collectAsState()
    val listState   = rememberLazyListState()
    val pullRefreshState = rememberPullToRefreshState()

    if (pullRefreshState.isRefreshing) {
        LaunchedEffect(true) {
            viewModel.refresh()
        }
    }
    LaunchedEffect(uiState.isRefreshing) {
        if (uiState.isRefreshing) {
            pullRefreshState.startRefresh()
        } else {
            pullRefreshState.endRefresh()
        }
    }

    // Trigger initial load
    LaunchedEffect(Unit) {
        if (uiState.statuses.isEmpty() && !uiState.isLoading) {
            viewModel.loadFeed()
        }
    }

    val shouldLoadMore = remember {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible >= uiState.statuses.size - 3 && uiState.hasMore && !uiState.isLoading
        }
    }
    LaunchedEffect(shouldLoadMore.value) {
        if (shouldLoadMore.value) viewModel.loadMore()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Träwelling", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
        // Tab row
        TabRow(
            selectedTabIndex = if (uiState.feedType == FeedType.DASHBOARD) 0 else 1
        ) {
            Tab(
                selected = uiState.feedType == FeedType.DASHBOARD,
                onClick  = { viewModel.switchFeedType(FeedType.DASHBOARD) },
                text     = { Text("Freunde") },
                icon     = { Icon(Icons.Default.People, null) }
            )
            Tab(
                selected = uiState.feedType == FeedType.GLOBAL,
                onClick  = { viewModel.switchFeedType(FeedType.GLOBAL) },
                text     = { Text("Global") },
                icon     = { Icon(Icons.Default.Public, null) }
            )
        }

        Box(modifier = Modifier.nestedScroll(pullRefreshState.nestedScrollConnection).fillMaxSize()) {
            when {
                uiState.isLoading && uiState.statuses.isEmpty() && !uiState.isRefreshing -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                uiState.error != null && uiState.statuses.isEmpty() -> {
                    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.ErrorOutline, null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.height(12.dp))
                            Text(uiState.error!!, color = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = { viewModel.refresh() }) { Text("Erneut versuchen") }
                        }
                    }
                }
                uiState.statuses.isEmpty() && !uiState.isLoading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Train, null,
                                modifier = Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f))
                            Spacer(Modifier.height(12.dp))
                            Text("Noch keine Einträge",
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                        }
                    }
                }
                else -> {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        items(uiState.statuses, key = { it.id }) { status ->
                            StatusCard(
                                status = status,
                                onLike = { viewModel.likeStatus(status.id) },
                                onUserClick = onUserClick,
                                onStatusClick = { onStatusClick(status.id) }
                            )
                        }
                        if (uiState.isLoading && !uiState.isRefreshing) {
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
            if (pullRefreshState.progress > 0 || pullRefreshState.isRefreshing || uiState.isRefreshing) {
                PullToRefreshContainer(
                    state = pullRefreshState,
                    modifier = Modifier.align(Alignment.TopCenter)
                )
            }
        }
    }
}
}

@Composable
fun StatusCard(
    status: Status,
    onLike: () -> Unit,
    onUserClick: (String) -> Unit = {},
    onStatusClick: () -> Unit = {}
) {
    val user    = status.user
    val checkin = status.checkin
    val isLiked = status.liked == true

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {

            // User row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable {
                    user?.username?.let { onUserClick(it) }
                }
            ) {
                if (user?.profilePicture != null) {
                    AsyncImage(
                        model = user.profilePicture,
                        contentDescription = null,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                    )
                    Spacer(Modifier.width(8.dp))
                } else {
                    Icon(Icons.Default.AccountCircle, null,
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                    Spacer(Modifier.width(8.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        user?.displayName ?: user?.username ?: "Unbekannt",
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "@${user?.username ?: ""}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
                Text(
                    formatTimestamp(status.createdAt ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }

            // Train/checkin info — clickable to open detail
            if (checkin != null) {
                Spacer(Modifier.height(10.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                    ),
                    modifier = Modifier.clickable { onStatusClick() }
                ) {
                    Column(Modifier.padding(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Train, null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                checkin.lineName ?: "Unbekannte Linie",
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(checkin.origin?.name ?: "–",
                                style = MaterialTheme.typography.bodySmall)
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, null,
                                modifier = Modifier.size(14.dp).padding(horizontal = 4.dp))
                            Text(checkin.destination?.name ?: "–",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        if (checkin.distanceMeters != null) {
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "%.1f km · %d min".format(
                                    checkin.distanceMeters / 1000.0,
                                    checkin.duration ?: 0
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
            }

            // Status text
            if (!status.body.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(status.body, style = MaterialTheme.typography.bodyMedium)
            }

            // Like action
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onLike, modifier = Modifier.size(36.dp)) {
                    Icon(
                        if (isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = "Like",
                        tint = if (isLiked) MaterialTheme.colorScheme.error
                               else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.size(20.dp)
                    )
                }
                Text(
                    (status.likes ?: 0).toString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                if (checkin?.points != null) {
                    Spacer(Modifier.weight(1f))
                    Icon(Icons.Default.Stars, null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(3.dp))
                    Text("${checkin.points} Punkte",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary)
                }
            }
        }
    }
}

private fun formatTimestamp(isoTimestamp: String): String {
    if (isoTimestamp.isBlank()) return ""
    return try {
        val zdt = ZonedDateTime.parse(isoTimestamp)
        val local = zdt.withZoneSameInstant(ZoneId.systemDefault())
        local.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT))
    } catch (_: Exception) {
        isoTimestamp.take(16).replace("T", " ")
    }
}
