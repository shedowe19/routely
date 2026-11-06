package de.traewelling.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import de.traewelling.app.ui.components.FeedSkeletonList
import de.traewelling.app.ui.components.StateMessage
import de.traewelling.app.ui.components.StatusCard
import de.traewelling.app.ui.components.TraewellingTopAppBar
import de.traewelling.app.viewmodel.FeedType
import de.traewelling.app.viewmodel.FeedViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FeedScreen(
    viewModel: FeedViewModel,
    onUserClick: (String) -> Unit = {},
    onStatusClick: (Int) -> Unit = {},
    onSearchUsersClick: () -> Unit = {}
) {
    val uiState     by viewModel.uiState.collectAsState()
    val listState   = rememberLazyListState()
    var showFilters by rememberSaveable { mutableStateOf(false) }
    var selectedCategory by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedCategories = selectedCategory?.let { FeedCategoryFilters[it] }
    val filteredStatuses = remember(uiState.statuses, selectedCategories) {
        selectedCategories?.let { categories ->
            uiState.statuses.filter { it.checkin?.category in categories }
        } ?: uiState.statuses
    }
    // Trigger initial load
    LaunchedEffect(Unit) {
        if (uiState.statuses.isEmpty() && !uiState.isLoading) {
            viewModel.loadFeed()
        }
    }

    val shouldLoadMore = remember(filteredStatuses) {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            uiState.hasMore && !uiState.isLoading && !uiState.isRefreshing &&
                uiState.error == null &&
                (filteredStatuses.isEmpty() || lastVisible >= filteredStatuses.size - 3)
        }
    }
    LaunchedEffect(shouldLoadMore.value) {
        if (shouldLoadMore.value) viewModel.loadMore()
    }

    Scaffold(
        topBar = {
            TraewellingTopAppBar(
                title = "Routely",
                showBrandMark = true,
                actions = {
                    IconButton(onClick = { showFilters = true }) {
                        BadgedBox(
                            badge = {
                                if (selectedCategory != null) {
                                    Badge(containerColor = MaterialTheme.colorScheme.primary)
                                }
                            }
                        ) {
                            Icon(Icons.Default.FilterList, "Feed filtern")
                        }
                    }
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
        FeedTypeSwitcher(
            selected = uiState.feedType,
            onSelect = viewModel::switchFeedType
        )

        if (uiState.statuses.isNotEmpty()) {
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
                (uiState.isLoading || uiState.isRefreshing) && uiState.statuses.isEmpty() ->
                    FeedSkeletonList()
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
                filteredStatuses.isEmpty() && uiState.isLoading ->
                    StateMessage(
                        icon = Icons.Default.FilterList,
                        title = "Weitere Fahrten werden geladen",
                        message = "Wir suchen nach passenden Fahrten für diesen Filter.",
                        loading = true
                    )
                filteredStatuses.isEmpty() ->
                    StateMessage(
                        icon = Icons.Default.FilterList,
                        title = "Keine Fahrten für diesen Filter",
                        message = "Wähle ein anderes Verkehrsmittel oder setze den Filter zurück.",
                        actionLabel = "Filter zurücksetzen",
                        onAction = { selectedCategory = null }
                    )
                else -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = 2.dp, bottom = 16.dp)
                    ) {
                        items(filteredStatuses, key = { it.id }) { status ->
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
        }
    }

    if (showFilters) {
        FeedFilterSheet(
            selectedCategory = selectedCategory,
            onSelect = {
                selectedCategory = it
                showFilters = false
            },
            onDismiss = { showFilters = false }
        )
    }
}
}

private val FeedCategoryFilters = mapOf(
    "national" to setOf("nationalExpress", "national"),
    "regional" to setOf("regionalExp", "regional"),
    "suburban" to setOf("suburban"),
    "subway" to setOf("subway"),
    "tram" to setOf("tram"),
    "bus" to setOf("bus"),
    "ferry" to setOf("ferry")
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun FeedFilterSheet(
    selectedCategory: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    val categories = listOf(
        null to "Alle",
        "national" to "Fernverkehr",
        "regional" to "Regional",
        "suburban" to "S-Bahn",
        "subway" to "U-Bahn",
        "tram" to "Tram",
        "bus" to "Bus",
        "ferry" to "Fähre"
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            Text("Feed filtern", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                "Zeige nur Fahrten eines Verkehrsmittels.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(14.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                categories.forEach { (id, label) ->
                    FilterChip(
                        selected = selectedCategory == id,
                        onClick = { onSelect(id) },
                        label = { Text(label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun FeedTypeSwitcher(
    selected: FeedType,
    onSelect: (FeedType) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .selectableGroup()
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(14.dp))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        FeedTypeTab(
            label = "Freunde",
            selected = selected == FeedType.DASHBOARD,
            modifier = Modifier.weight(1f),
            onClick = { onSelect(FeedType.DASHBOARD) }
        )
        FeedTypeTab(
            label = "Global",
            selected = selected == FeedType.GLOBAL,
            modifier = Modifier.weight(1f),
            onClick = { onSelect(FeedType.GLOBAL) }
        )
    }
}

@Composable
private fun FeedTypeTab(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier
            .height(36.dp)
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.Tab
            ),
        shape = RoundedCornerShape(11.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
            )
        }
    }
}
