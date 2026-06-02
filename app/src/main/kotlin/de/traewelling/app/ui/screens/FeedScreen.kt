package de.traewelling.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import de.traewelling.app.ui.components.StateMessage
import de.traewelling.app.ui.components.StatusCard
import de.traewelling.app.ui.components.TraewellingTopAppBar
import de.traewelling.app.viewmodel.FeedType
import de.traewelling.app.viewmodel.FeedViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(
    viewModel: FeedViewModel,
    onUserClick: (String) -> Unit = {},
    onStatusClick: (Int) -> Unit = {},
    onSearchUsersClick: () -> Unit = {}
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
            TraewellingTopAppBar(
                title = "Routely",
                actions = {
                    IconButton(onClick = onSearchUsersClick) {
                        Icon(Icons.Default.Search, "Benutzer suchen")
                    }
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Default.Refresh, "Aktualisieren")
                    }
                }
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
                uiState.isLoading && uiState.statuses.isEmpty() && !uiState.isRefreshing ->
                    StateMessage(
                        icon = Icons.Default.Train,
                        title = "Feed wird geladen",
                        message = "Wir holen die neuesten Fahrten für dich.",
                        loading = true
                    )
                uiState.error != null && uiState.statuses.isEmpty() ->
                    StateMessage(
                        icon = Icons.Default.ErrorOutline,
                        title = "Feed konnte nicht geladen werden",
                        message = uiState.error,
                        iconTint = MaterialTheme.colorScheme.error,
                        actionLabel = "Erneut versuchen",
                        onAction = { viewModel.refresh() }
                    )
                uiState.statuses.isEmpty() && !uiState.isLoading ->
                    StateMessage(
                        icon = Icons.Default.Train,
                        title = "Noch keine Fahrten",
                        message = "Zieh nach unten zum Aktualisieren oder wechsle in den globalen Feed."
                    )
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
