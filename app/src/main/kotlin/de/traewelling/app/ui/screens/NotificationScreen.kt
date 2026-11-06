package de.traewelling.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.traewelling.app.data.model.Notification
import de.traewelling.app.ui.components.NotificationSkeletonList
import de.traewelling.app.ui.components.StateMessage
import de.traewelling.app.ui.components.TraewellingTopAppBar
import de.traewelling.app.ui.navigation.NotificationTarget
import de.traewelling.app.ui.navigation.NotificationTargetResolver
import de.traewelling.app.viewmodel.NotificationViewModel
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

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

    val notificationGroups = remember(uiState.notifications) {
        uiState.notifications.groupBy { notificationDay(it.createdAt) }
    }
    val renderedItemCount = uiState.notifications.size + notificationGroups.size

    // Auto-load more; the lazy-list index also counts the day headers.
    val shouldLoadMore = remember(renderedItemCount) {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible >= renderedItemCount - 3 && uiState.hasMore && !uiState.isLoading &&
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
                    NotificationSkeletonList()
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
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 4.dp)
                    ) {
                        notificationGroups.forEach { (day, dayNotifications) ->
                                item(key = "day-$day") {
                                    NotificationDayHeader(day)
                                }
                                itemsIndexed(
                                    dayNotifications,
                                    key = { _, notification -> notification.id }
                                ) { index, notification ->
                                    val target = remember(notification.link, serverUrl) {
                                        NotificationTargetResolver.resolve(notification.link, serverUrl)
                                    }
                                    NotificationItem(
                                        notification = notification,
                                        target = target,
                                        showDivider = index < dayNotifications.lastIndex,
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
}

@Composable
private fun NotificationDayHeader(label: String) {
    Text(
        label,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

private fun notificationDay(iso: String?): String {
    val timestamp = iso ?: return "Älter"
    val date = runCatching {
        ZonedDateTime.parse(timestamp)
            .withZoneSameInstant(ZoneId.systemDefault())
            .toLocalDate()
    }.getOrNull() ?: return "Älter"
    val today = LocalDate.now()
    return when (date) {
        today -> "Heute"
        today.minusDays(1) -> "Gestern"
        else -> date.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))
    }
}

@Composable
private fun NotificationItem(
    notification: Notification,
    target: NotificationTarget?,
    showDivider: Boolean,
    onClick: () -> Unit
) {
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

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    if (isUnread) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.045f)
                    } else {
                        Color.Transparent
                    }
                )
                .clickable(
                    enabled = isUnread || target != null,
                    onClickLabel = when (target) {
                        is NotificationTarget.Status -> "Fahrt öffnen"
                        is NotificationTarget.UserProfile -> "Profil öffnen"
                        null -> "Als gelesen markieren"
                    },
                    onClick = onClick
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .background(iconTint.copy(alpha = 0.12f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = iconTint, modifier = Modifier.size(20.dp))
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    notification.lead ?: "",
                    fontWeight = if (isUnread) FontWeight.SemiBold else FontWeight.Normal,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (!notification.notice.isNullOrBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        notification.notice,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    notification.createdAtForHumans ?: "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )
            }

            if (isUnread) {
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .padding(top = 6.dp)
                        .size(7.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                )
            }
            if (target != null) {
                Spacer(Modifier.width(4.dp))
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .size(18.dp)
                )
            }
        }
        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(start = 66.dp, end = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )
        }
    }
}
