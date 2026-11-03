package de.traewelling.app.ui.screens

import androidx.compose.ui.unit.sp

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.traewelling.app.data.model.Notification
import de.traewelling.app.ui.components.StateMessage
import de.traewelling.app.ui.components.TraewellingTopAppBar
import de.traewelling.app.ui.navigation.NotificationTarget
import de.traewelling.app.ui.navigation.NotificationTargetResolver
import de.traewelling.app.ui.theme.TealDark
import de.traewelling.app.viewmodel.NotificationViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationScreen(
    viewModel: NotificationViewModel,
    serverUrl: String,
    onStatusClick: (Int) -> Unit = {},
    onUserClick: (String) -> Unit = {}
) {
    val uiState     by viewModel.uiState.collectAsState()
    val listState   = rememberLazyListState()
    // Trigger initial load
    LaunchedEffect(Unit) {
        if (uiState.notifications.isEmpty() && !uiState.isLoading) {
            viewModel.loadNotifications(refresh = true)
        }
    }

    // Auto-load more
    val shouldLoadMore = remember {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible >= uiState.notifications.size - 3 && uiState.hasMore && !uiState.isLoading &&
                !uiState.isRefreshing && uiState.error == null
        }
    }
    LaunchedEffect(shouldLoadMore.value) {
        if (shouldLoadMore.value) viewModel.loadMore()
    }

    Scaffold(
        topBar = {
            TraewellingTopAppBar(
                title = "Meldungen",
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Default.Refresh, "Aktualisieren")
                    }
                    if (uiState.unreadCount > 0) {
                        TextButton(onClick = { viewModel.markAllAsRead() }) {
                            Icon(Icons.Default.DoneAll, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Alle gelesen")
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
        if (uiState.notifications.isNotEmpty()) {
            uiState.error?.let { error ->
                Text(error, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
        }
        PullToRefreshBox(
            isRefreshing = uiState.isRefreshing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier.fillMaxSize()
        ) {
            when {
                (uiState.isLoading || uiState.isRefreshing) && uiState.notifications.isEmpty() ->
                    StateMessage(
                        icon = Icons.Default.Notifications,
                        title = "Meldungen werden geladen",
                        message = "Neue Benachrichtigungen werden abgefragt.",
                        loading = true
                    )
                uiState.error != null && uiState.notifications.isEmpty() ->
                    StateMessage(
                        icon = Icons.Default.ErrorOutline,
                        title = "Meldungen konnten nicht geladen werden",
                        message = uiState.error,
                        iconTint = MaterialTheme.colorScheme.error,
                        actionLabel = "Erneut versuchen",
                        onAction = { viewModel.refresh() }
                    )
                uiState.notifications.isEmpty() ->
                    StateMessage(
                        icon = Icons.Default.NotificationsNone,
                        title = "Alles erledigt",
                        message = "Du hast aktuell keine Benachrichtigungen."
                    )
                else -> {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        items(uiState.notifications, key = { it.id }) { notification ->
                            val target = remember(notification.link, serverUrl) {
                                NotificationTargetResolver.resolve(notification.link, serverUrl)
                            }
                            NotificationItem(
                                notification = notification,
                                target = target,
                                onClick = {
                                    if (notification.readAt == null) {
                                        viewModel.markAsRead(notification.id)
                                    }
                                    when (target) {
                                        is NotificationTarget.Status -> onStatusClick(target.id)
                                        is NotificationTarget.UserProfile -> onUserClick(target.username)
                                        null -> Unit
                                    }
                                }
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
private fun NotificationItem(notification: Notification, target: NotificationTarget?, onClick: () -> Unit) {
    val isUnread = notification.readAt == null
    val icon = when {
        notification.type?.contains("Liked", ignoreCase = true) == true -> Icons.Default.Favorite
        notification.type?.contains("Follow", ignoreCase = true) == true -> Icons.Default.PersonAdd
        notification.type?.contains("Connection", ignoreCase = true) == true -> Icons.Default.Train
        notification.type?.contains("Mention", ignoreCase = true) == true -> Icons.Default.AlternateEmail
        else -> Icons.Default.Notifications
    }
    val iconTint = when {
        notification.type?.contains("Liked", ignoreCase = true) == true -> MaterialTheme.colorScheme.error
        notification.type?.contains("Follow", ignoreCase = true) == true -> MaterialTheme.colorScheme.primary
        notification.type?.contains("Connection", ignoreCase = true) == true -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.primary
    }

    val bgColor = if (isUnread) MaterialTheme.colorScheme.primary.copy(alpha = 0.05f) else MaterialTheme.colorScheme.surface

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clickable(
                enabled = isUnread || target != null,
                onClickLabel = when (target) {
                    is NotificationTarget.Status -> "Fahrt öffnen"
                    is NotificationTarget.UserProfile -> "Profil öffnen"
                    null -> "Als gelesen markieren"
                },
                onClick = onClick
            ),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(if (isUnread) 5.dp else 2.dp),
        border = BorderStroke(1.dp, iconTint.copy(alpha = if (isUnread) 0.20f else 0.08f)),
        colors = CardDefaults.cardColors(containerColor = bgColor)
    ) {
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(if (isUnread) 4.dp else 0.dp)
                    .background(iconTint.copy(alpha = 0.8f))
            )
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.Top
            ) {
                Surface(
                    color = iconTint.copy(alpha = 0.12f),
                    shape = androidx.compose.foundation.shape.CircleShape
                ) {
                    Icon(icon, null, tint = iconTint, modifier = Modifier.padding(10.dp).size(24.dp))
                }
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        notification.lead ?: "",
                        fontWeight = if (isUnread) FontWeight.Bold else FontWeight.Medium,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isUnread) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f)
                    )
                    if (!notification.notice.isNullOrBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            notification.notice,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            lineHeight = 16.sp,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        notification.createdAtForHumans ?: "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
                if (isUnread || target != null) {
                    Spacer(Modifier.width(8.dp))
                    Column(horizontalAlignment = Alignment.End) {
                        if (isUnread) {
                            Surface(
                                color = TealDark.copy(alpha = 0.12f),
                                shape = RoundedCornerShape(999.dp)
                            ) {
                                Text(
                                    "Neu",
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TealDark,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        if (target != null) {
                            Icon(
                                Icons.Default.ChevronRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = if (isUnread) 8.dp else 0.dp).size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
