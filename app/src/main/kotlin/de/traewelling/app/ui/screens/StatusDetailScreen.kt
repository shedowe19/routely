package de.traewelling.app.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import de.traewelling.app.data.model.StopStation
import de.traewelling.app.data.model.SevStopInfo
import de.traewelling.app.data.model.Status
import de.traewelling.app.data.model.TravelReason
import de.traewelling.app.data.sev.SevStopResolver
import de.traewelling.app.service.GpsJourneyTimes
import de.traewelling.app.service.GpsTimeUnavailableReason
import de.traewelling.app.service.JourneyTime
import de.traewelling.app.service.JourneyTimeResolver
import de.traewelling.app.service.JourneyTimeSource
import de.traewelling.app.service.TrackingSource
import de.traewelling.app.ui.components.StateMessage
import de.traewelling.app.ui.components.TraewellingTopAppBar
import de.traewelling.app.ui.theme.*
import de.traewelling.app.viewmodel.StatusDetailViewModel
import de.traewelling.app.viewmodel.StatusDetailUiState
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusDetailScreen(
    statusId: Int,
    viewModel: StatusDetailViewModel,
    onBack: () -> Unit,
    onUserClick: (String) -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(statusId) {
        viewModel.loadStatusDetail(statusId)
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.reset() }
    }

    var showDeleteDialog by remember { mutableStateOf(false) }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Fahrt löschen") },
            text = { Text("Möchtest du diese Fahrt wirklich dauerhaft löschen? Diese Aktion kann nicht rückgängig gemacht werden.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        viewModel.deleteStatus(onSuccess = onBack)
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Löschen")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Abbrechen")
                }
            }
        )
    }

    if (uiState.isEditing) {
        EditStatusDialog(
            uiState = uiState,
            onDismiss = viewModel::stopEditing,
            onUpdateBody = viewModel::updateEditBody,
            onUpdateDeparture = viewModel::updateEditDeparture,
            onUpdateArrival = viewModel::updateEditArrival,
            onUpdateDestination = viewModel::updateEditDestination,
            onSave = viewModel::saveStatusEdit
        )
    }

    Scaffold(
        topBar = {
            TraewellingTopAppBar(
                title = "Fahrt-Details",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück")
                    }
                },
                actions = {
                    val isToday = remember(uiState.status) {
                        val createdAt = uiState.status?.createdAt
                        if (createdAt != null) {
                            try {
                                val zdt = ZonedDateTime.parse(createdAt)
                                val tripDate = zdt.toLocalDate()
                                val today = ZonedDateTime.now().toLocalDate()
                                tripDate == today
                            } catch (e: Exception) { false }
                        } else false
                    }

                    if (uiState.lastUpdated > 0 && isToday) {
                        val pulseAnim = rememberInfiniteTransition(label = "live")
                        val pulseAlpha by pulseAnim.animateFloat(
                            initialValue = 1f, targetValue = 0.3f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(800, easing = EaseInOutCubic),
                                repeatMode = RepeatMode.Reverse
                            ), label = "pulse"
                        )
                        Surface(
                            color = Color(0xFF00E676).copy(alpha = 0.2f),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            ) {
                                Box(
                                    Modifier
                                        .size(8.dp)
                                        .alpha(pulseAlpha)
                                        .background(Color(0xFF00E676), CircleShape)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "LIVE",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF00E676),
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp
                                )
                            }
                        }
                    }

                    if (uiState.isOwnStatus) {
                        if (uiState.isDeleting || uiState.isUpdating) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp).padding(4.dp),
                                color = MaterialTheme.colorScheme.onSurface,
                                strokeWidth = 2.dp
                            )
                        } else {
                            IconButton(onClick = { viewModel.startEditing() }) {
                                Icon(Icons.Default.Edit, "Bearbeiten")
                            }
                            IconButton(onClick = { showDeleteDialog = true }) {
                                Icon(Icons.Default.Delete, "Löschen")
                            }
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        AnimatedContent(
            targetState = when {
                uiState.isLoading && uiState.status == null -> "LOADING"
                uiState.error != null && uiState.status == null -> "ERROR"
                else -> "CONTENT"
            },
            transitionSpec = {
                fadeIn(animationSpec = tween(300)) togetherWith fadeOut(animationSpec = tween(300))
            },
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            label = "ContentTransition"
        ) { targetState ->
            when (targetState) {
                "LOADING" -> {
                    StateMessage(
                        icon = Icons.Default.Timeline,
                        title = "Fahrt-Details werden geladen",
                        message = "Timeline, Halte und Echtzeiten werden vorbereitet.",
                        loading = true
                    )
                }
                "ERROR" -> {
                    StateMessage(
                        icon = Icons.Default.ErrorOutline,
                        title = "Fahrt konnte nicht geladen werden",
                        message = uiState.error,
                        iconTint = MaterialTheme.colorScheme.error,
                        actionLabel = "Erneut versuchen",
                        onAction = { viewModel.refresh() }
                    )
                }
                "CONTENT" -> {
                    StatusDetailContent(
                        uiState = uiState,
                        onUserClick = onUserClick
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusDetailContent(
    uiState: StatusDetailUiState,
    onUserClick: (String) -> Unit
) {
    val status = uiState.status ?: return
    val checkin = status.checkin
    val stopovers = uiState.stopovers

    // Recheck GPS validity every second, even when the service stops publishing updates.
    var now by remember { mutableStateOf(ZonedDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = ZonedDateTime.now()
        }
    }

    // A GPS callback can arrive between ticks. Validate its timestamp against
    // the current clock, not the previous tick that may predate the new fix.
    val nowMillis = System.currentTimeMillis()
    val gpsTimes = uiState.trackingState?.takeIf { it.source == TrackingSource.GPS }?.gpsTimes
    val gpsTimeReasonMessage = uiState.trackingState?.let { tracking ->
        if (tracking.gpsTimes?.let { nowMillis > it.validUntilMillis } == true)
            "Die GPS-Zeitdaten sind abgelaufen."
        else tracking.gpsTimeUnavailableReason?.let(::gpsTimeUnavailableMessage)
    }
    val isReplacementBus = checkin?.let(SevStopResolver::isReplacementBus) == true
    // The active service owns the coordinates used for navigation. Its assignment
    // takes precedence; the detail lookup also supplies guidance before tracking starts.
    val sevStops = if (isReplacementBus) {
        uiState.sevStops + uiState.trackingState?.sevStops.orEmpty()
    } else emptyMap()

    val firstRealStopIndex = remember(stopovers) {
        stopovers.indexOfFirst { it.cancelled != true }
    }
    val lastRealStopIndex = remember(stopovers) {
        stopovers.indexOfLast { it.cancelled != true }
    }

    val origin = checkin?.origin
    val destination = checkin?.destination

    val originIdx = remember(stopovers, origin) {
        stopovers.indexOfFirst { it.matchesStopover(origin) }
    }
    val destinationIdx = remember(stopovers, destination) {
        stopovers.indexOfFirst { it.matchesStopover(destination) }
    }
    val clockStopovers = remember(stopovers, origin, destination, checkin?.manualDeparture, checkin?.manualArrival) {
        JourneyTimeResolver.manualTimelineStops(stopovers, origin, destination,
            checkin?.manualDeparture, checkin?.manualArrival)
    }
    val timelineProgress = remember(clockStopovers, now, nowMillis, uiState.trackingState, destinationIdx) {
        resolveStopTimelineProgress(clockStopovers, nowMillis, uiState.trackingState, destinationIdx)
    }

    val isLoading = uiState.isLoading
    val hasStopovers = stopovers.isNotEmpty()

    // State to trigger enter animations
    var isVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        isVisible = true
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 80.dp)
    ) {
        // Status header card
        item {
            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(animationSpec = tween(400)) + slideInVertically(
                    initialOffsetY = { 50 },
                    animationSpec = tween(400)
                )
            ) {
                StatusHeaderCard(status, onUserClick)
            }
        }

        // Trip info card
        if (checkin != null) {
            item {
                AnimatedVisibility(
                    visible = isVisible,
                    enter = fadeIn(animationSpec = tween(400, delayMillis = 100)) + slideInVertically(
                        initialOffsetY = { 50 },
                        animationSpec = tween(400, delayMillis = 100)
                    )
                ) {
                    TripInfoCard(status, gpsTimes, nowMillis, isReplacementBus,
                        sevStops.isNotEmpty(), uiState.isLoadingSevStops)
                }
            }
        }

        // Stopovers header
        if (hasStopovers) {
            item {
                AnimatedVisibility(
                    visible = isVisible,
                    enter = fadeIn(animationSpec = tween(400, delayMillis = 200)) + slideInVertically(
                        initialOffsetY = { 50 },
                        animationSpec = tween(400, delayMillis = 200)
                    )
                ) {
                    Column {
                        Spacer(Modifier.height(8.dp))
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .background(
                                    Brush.horizontalGradient(
                                        colors = listOf(
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                            MaterialTheme.colorScheme.secondary.copy(alpha = 0.08f),
                                            MaterialTheme.colorScheme.surface
                                        )
                                    )
                                )
                                .border(
                                    1.dp,
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                                    RoundedCornerShape(18.dp)
                                )
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Timeline, null,
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        "Haltestellenverlauf",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        when (timelineProgress.source) {
                                            TimelinePositionSource.GPS -> "Fortschritt per GPS"
                                            TimelinePositionSource.TIMETABLE -> "Fahrplan-Schätzung · ungefähre Position"
                                            TimelinePositionSource.WAITING -> "Position wird ermittelt"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                                    )
                                    gpsTimeReasonMessage?.let { reason ->
                                        Text(
                                            reason,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                                        )
                                    }
                                }
                                Surface(
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text(
                                        "${stopovers.size} Halte",
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                        if (isReplacementBus) {
                            RoadRouteAttribution()
                        }
                    }
                }
            }

            // Stopovers list
            items(stopovers.size) { index ->
                val stop = stopovers[index]
                val isOrigin = index == originIdx
                val isDestination = index == destinationIdx
                val isInRange = originIdx >= 0 && destinationIdx >= originIdx && index in originIdx..destinationIdx

                AnimatedVisibility(
                    visible = isVisible,
                    enter = fadeIn(
                        animationSpec = tween(400, delayMillis = 200 + (index * 50).coerceAtMost(1000))
                    ) + slideInVertically(
                        initialOffsetY = { 50 },
                        animationSpec = tween(400, delayMillis = 200 + (index * 50).coerceAtMost(1000))
                    )
                ) {
                    StopoverItem(
                        stop = stop,
                        sevInfo = sevStops[SevStopResolver.visitKey(stop)],
                        isReplacementBus = isReplacementBus,
                        arrivalTime = JourneyTimeResolver.arrival(stop, gpsTimes, nowMillis,
                            if (isDestination) checkin?.manualArrival else null),
                        departureTime = JourneyTimeResolver.departure(stop, gpsTimes, nowMillis,
                            if (isOrigin) checkin?.manualDeparture else null),
                        progress = timelineProgress,
                        index = index,
                        originIndex = originIdx,
                        destinationIndex = destinationIdx,
                        isFirst = index == firstRealStopIndex,
                        isActualFirst = index == 0,
                        isLast = index == lastRealStopIndex,
                        isActualLast = index == stopovers.lastIndex,
                        isOrigin = isOrigin,
                        isDestination = isDestination,
                        isInRange = isInRange
                    )
                }
            }
        }
        if (isLoading && !hasStopovers) {
            item {
                StateMessage(
                    icon = Icons.Default.Timeline,
                    title = "Halte werden geladen",
                    message = "Der Verlauf erscheint gleich in der Timeline.",
                    modifier = Modifier.fillMaxWidth().height(180.dp),
                    loading = true
                )
            }
        }
    }
}

@Composable
private fun StatusHeaderCard(status: Status, onUserClick: (String) -> Unit) {
    val user = status.user

    Card(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(3.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(16.dp)) {
            // User row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable(enabled = user?.username != null) {
                    user?.username?.let(onUserClick)
                }
            ) {
                if (user?.profilePicture != null) {
                    AsyncImage(
                        model = user.profilePicture,
                        contentDescription = null,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), CircleShape)
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Person, null,
                            modifier = Modifier.size(28.dp),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        user?.displayName ?: user?.username ?: "Unbekannt",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Surface(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.06f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            "@${user?.username ?: ""}",
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                        )
                    }
                }
            }

            // Status body
            if (!status.body.isNullOrBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(status.body, style = MaterialTheme.typography.bodyMedium,
                    lineHeight = 20.sp)
            }
        }
    }
}

@Composable
private fun TripInfoCard(
    status: Status,
    gpsTimes: GpsJourneyTimes?,
    nowMillis: Long,
    isReplacementBus: Boolean,
    hasSevStops: Boolean,
    isLoadingSevStops: Boolean
) {
    val checkin = status.checkin ?: return
    val transportColor = TransportColors.forCategory(checkin.category)

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(16.dp)) {
            // Line name + category
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = transportColor,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        checkin.lineName ?: "?",
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        localiseCategory(checkin.category ?: ""),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                    )
                    checkin.operator?.name?.let { opName ->
                        Text(
                            opName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        )
                    }
                }
            }

            if (isReplacementBus) {
                Spacer(Modifier.height(12.dp))
                Surface(color = AmberAccent.copy(alpha = 0.10f), shape = RoundedCornerShape(10.dp)) {
                    Column(Modifier.fillMaxWidth().padding(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.DirectionsBus, null,
                                modifier = Modifier.size(16.dp), tint = AmberAccent)
                            Spacer(Modifier.width(6.dp))
                            Text("Schienenersatzverkehr", style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold, color = AmberAccent)
                        }
                        Text(
                            when {
                                hasSevStops -> "Ersatzhaltestellen und Wegbeschreibungen stehen bei den Halten."
                                isLoadingSevStops -> "Ersatzhaltestellen werden auf bahnhof.de gesucht."
                                else -> "Keine passenden Ersatzhaltestellen gefunden. Die Stationsdaten bleiben aktiv."
                            },
                            modifier = Modifier.padding(top = 4.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            TravelReasonInfo(status.business)

            Spacer(Modifier.height(16.dp))

            // Origin → Destination with mini-timeline
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).background(TealAccent, CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(checkin.origin?.stationName ?: "–", fontWeight = FontWeight.SemiBold)
            }
            Row(modifier = Modifier.padding(start = 5.dp)) {
                Box(
                    Modifier
                        .width(2.dp)
                        .height(24.dp)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(TealAccent, AmberAccent)
                            )
                        )
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).background(AmberAccent, CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(checkin.destination?.stationName ?: "–", fontWeight = FontWeight.SemiBold)
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            Spacer(Modifier.height(12.dp))

            // Stats as pill chips
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                if (checkin.distanceMeters != null) {
                    StatPill(Icons.Default.Route, "%.1f km".format(checkin.distanceMeters / 1000.0), TealAccent)
                }
                if (checkin.duration != null) {
                    StatPill(Icons.Default.Schedule, "${checkin.duration} min", MaterialTheme.colorScheme.primary)
                }
                if (checkin.points != null && checkin.points > 0) {
                    StatPill(Icons.Default.Stars, "${checkin.points} Pkt", AmberAccent)
                }
            }

            // Departure / Arrival times
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            Spacer(Modifier.height(10.dp))

            val origin = checkin.origin
            val dest = checkin.destination
            if (origin != null) {
                TimeRow("Abfahrt", JourneyTimeResolver.departure(origin, gpsTimes, nowMillis, checkin.manualDeparture))
            }
            if (dest != null) {
                TimeRow("Ankunft", JourneyTimeResolver.arrival(dest, gpsTimes, nowMillis, checkin.manualArrival))
            }
        }
    }
}

@Composable
private fun TravelReasonInfo(business: Int?) {
    val reason = travelReasonFromValue(business)
    Surface(
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = travelReasonIcon(reason),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "Reisegrund:",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                travelReasonTitle(reason),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun StatPill(icon: androidx.compose.ui.graphics.vector.ImageVector, value: String, color: Color) {
    Surface(
        color = color.copy(alpha = 0.1f),
        shape = RoundedCornerShape(20.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Icon(icon, null, modifier = Modifier.size(14.dp), tint = color)
            Spacer(Modifier.width(5.dp))
            Text(value, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = color)
        }
    }
}

@Composable
private fun TimeRow(label: String, time: JourneyTime?) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        JourneyTimeValue(time)
    }
}

@Composable
private fun JourneyTimeValue(time: JourneyTime?, prefix: String = "", showDelayBadge: Boolean = false,
                             textAlpha: Float = 1f) {
    val actual = formatJourneyTime(time?.millis)
    val planned = formatJourneyTime(time?.plannedMillis)
    val differs = actual != planned && planned != "–"
    val delay = time?.delayMinutes ?: 0L
    val timeColor = when {
        !differs -> MaterialTheme.colorScheme.onSurface.copy(alpha = textAlpha)
        delay > 0 -> WarningOrange
        else -> SuccessGreen
    }
    Column(horizontalAlignment = Alignment.End) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (prefix.isNotBlank()) {
                Text(prefix, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
            if (differs) {
                Text(planned, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough)
                Spacer(Modifier.width(4.dp))
                if (showDelayBadge && delay != 0L) {
                    Surface(color = if (delay > 0) WarningOrangeLight else SuccessGreenLight,
                        shape = RoundedCornerShape(10.dp)) {
                        Text("${if (delay > 0) "+" else ""}$delay",
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = timeColor, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(4.dp))
                }
            }
            Text(actual, style = MaterialTheme.typography.bodyMedium, color = timeColor,
                fontWeight = if (differs) FontWeight.Bold else FontWeight.Medium)
        }
        time?.let {
            Text(it.sourceLabel, style = MaterialTheme.typography.labelSmall,
                color = if (it.source == JourneyTimeSource.GPS_ESTIMATE || it.source == JourneyTimeSource.GPS_OBSERVED)
                    TealAccent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
        }
    }
}

private fun formatJourneyTime(millis: Long?): String = millis?.let {
    Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))
} ?: "–"

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StopoverItem(
    stop: StopStation,
    sevInfo: SevStopInfo?,
    isReplacementBus: Boolean,
    arrivalTime: JourneyTime?,
    departureTime: JourneyTime?,
    progress: StopTimelineProgress,
    index: Int,
    originIndex: Int,
    destinationIndex: Int,
    isFirst: Boolean,
    isActualFirst: Boolean,
    isLast: Boolean,
    isActualLast: Boolean,
    isOrigin: Boolean,
    isDestination: Boolean,
    isInRange: Boolean
) {
    val isPast = index <= progress.passedThroughIndex
    val badge = progress.badgeFor(index)
    val isHighlighted = badge != null
    var headerHeightPx by remember { mutableIntStateOf(0) }

    val lineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
    val activeLineColor = TealAccent

    val dotColor = when {
        isOrigin -> TealAccent
        isDestination -> AmberAccent
        isPast || isHighlighted -> TealAccent.copy(alpha = 0.7f)
        isInRange -> TealAccent.copy(alpha = 0.35f)
        stop.cancelled == true -> ErrorRed
        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
    }

    val textAlpha = when {
        isOrigin || isDestination -> 1f
        isPast || isHighlighted -> 1f
        isInRange -> 0.8f
        else -> 0.45f
    }
    val stopContainerColor = when {
        isHighlighted -> TealAccent.copy(alpha = 0.12f)
        isOrigin -> TealAccent.copy(alpha = 0.08f)
        isDestination -> AmberAccent.copy(alpha = 0.10f)
        isInRange -> MaterialTheme.colorScheme.primary.copy(alpha = 0.035f)
        else -> Color.Transparent
    }
    val stopBorderColor = when {
        isHighlighted -> TealAccent.copy(alpha = 0.35f)
        isOrigin -> TealAccent.copy(alpha = 0.20f)
        isDestination -> AmberAccent.copy(alpha = 0.25f)
        else -> Color.Transparent
    }
    
    val isCancelled = stop.cancelled == true

    // Journeys range logic for lines
    val isTopTraveled = originIndex != -1 && destinationIndex != -1 && index > originIndex && index <= destinationIndex
    val isBottomTraveled = originIndex != -1 && destinationIndex != -1 && index >= originIndex && index < destinationIndex

    val isImportant = isOrigin || isDestination || isLast || isFirst || isActualFirst || isActualLast
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .drawBehind {
                val x = 12.dp.toPx()
                // Follow the actual header height so larger fonts and expanded
                // arrival/departure rows cannot detach a dot from its station.
                val y = (16.dp.toPx() + headerHeightPx / 2f).coerceIn(0f, size.height)
                if (!isActualFirst) {
                    drawLine(
                        color = if (index <= progress.passedThroughIndex) activeLineColor else lineColor,
                        start = Offset(x, 0f),
                        end = Offset(x, y),
                        strokeWidth = if (isTopTraveled) 3.dp.toPx() else 2.dp.toPx()
                    )
                }
                if (!isActualLast) {
                    drawLine(
                        color = if (index < progress.passedThroughIndex) activeLineColor else lineColor,
                        start = Offset(x, y),
                        end = Offset(x, size.height),
                        strokeWidth = if (isBottomTraveled) 3.dp.toPx() else 2.dp.toPx()
                    )
                }
                if ((isImportant || isHighlighted) && !isCancelled) {
                    drawCircle(dotColor.copy(alpha = 0.22f), 9.dp.toPx(), Offset(x, y))
                }
                drawCircle(
                    color = if (isCancelled) dotColor.copy(alpha = 0.5f) else dotColor,
                    radius = if (isImportant || isHighlighted) 6.dp.toPx() else 4.dp.toPx(),
                    center = Offset(x, y)
                )
                if (isHighlighted) {
                    drawCircle(Color.White, 2.5.dp.toPx(), Offset(x, y))
                }
            },
        verticalAlignment = Alignment.Top
    ) {
        Spacer(Modifier.width(24.dp))

        Spacer(Modifier.width(12.dp))

        // Content
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 6.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(stopContainerColor)
                .border(1.dp, stopBorderColor, RoundedCornerShape(16.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().onSizeChanged { headerHeightPx = it.height },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stop.stationName ?: "–",
                    fontWeight = if (isOrigin || isDestination) FontWeight.Bold else FontWeight.SemiBold,
                    color = if (isCancelled) ErrorRed else MaterialTheme.colorScheme.onSurface.copy(alpha = textAlpha),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                    textDecoration = if (isCancelled) androidx.compose.ui.text.style.TextDecoration.LineThrough else null
                )

                // Display-only GPS/API/manual values; the stopover identity stays unchanged.
                if (!isCancelled) {
                    Column(horizontalAlignment = Alignment.End) {
                        if (isOrigin || isDestination) {
                            JourneyTimeValue(
                                if (isOrigin) departureTime ?: arrivalTime else arrivalTime ?: departureTime,
                                showDelayBadge = true, textAlpha = textAlpha
                            )
                        } else {
                            arrivalTime?.let { JourneyTimeValue(it, prefix = "An: ", textAlpha = textAlpha) }
                            departureTime?.let { JourneyTimeValue(it, prefix = "Ab: ", textAlpha = textAlpha) }
                        }
                    }
                }
            }

            // Platform + badges
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(top = 2.dp)
            ) {
                if (isHighlighted) {
                    Surface(
                        color = TealAccent.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(4.dp),
                        border = BorderStroke(0.5.dp, TealAccent.copy(alpha = 0.35f))
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Icon(
                                Icons.Default.Navigation,
                                contentDescription = null,
                                modifier = Modifier.size(10.dp),
                                tint = TealDark
                            )
                            Spacer(Modifier.width(3.dp))
                            Text(
                                badge.orEmpty(),
                                style = MaterialTheme.typography.labelSmall,
                                color = TealDark,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                val rawPlat = stop.platform ?: stop.departurePlatformReal ?: stop.arrivalPlatformReal
                // Strip HAFAS sector prefix "9": "91"→"1", "911"→"11", "99"→"9"
                // Some DB stations encode tracks as sector(9) + number internally
                val plat = rawPlat?.let { p ->
                    if (p.length > 1 && p.startsWith("9") && p.drop(1).all { it.isDigit() }) p.drop(1) else p
                }
                if (plat != null && !isReplacementBus) {
                    val displayPlat = if (plat.startsWith("Gl", ignoreCase = true)) plat else "Gl. $plat"
                    Surface(
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            displayPlat,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                }
                if (isCancelled) {
                    Surface(
                        color = ErrorRedLight,
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                modifier = Modifier.size(10.dp),
                                tint = ErrorRed
                            )
                            Spacer(Modifier.width(3.dp))
                            Text(
                                "HALT ENTFÄLLT",
                                style = MaterialTheme.typography.labelSmall,
                                color = ErrorRed,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                if (isFirst) {
                    Surface(
                        color = TealLight,
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Icon(
                                Icons.Default.FirstPage,
                                contentDescription = null,
                                modifier = Modifier.size(10.dp),
                                tint = TealDark
                            )
                            Spacer(Modifier.width(3.dp))
                            Text(
                                "STARTHALTESTELLE",
                                style = MaterialTheme.typography.labelSmall,
                                color = TealDark,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                if (isOrigin) {
                    Surface(
                        color = AmberLight,
                        shape = RoundedCornerShape(4.dp),
                        border = BorderStroke(0.5.dp, AmberAccent.copy(alpha = 0.3f))
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Login,
                                contentDescription = null,
                                modifier = Modifier.size(10.dp),
                                tint = AmberDark
                            )
                            Spacer(Modifier.width(3.dp))
                            Text(
                                "DEIN EINSTIEG",
                                style = MaterialTheme.typography.labelSmall,
                                color = AmberDark,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
                if (isDestination) {
                    Surface(
                        color = AmberLight,
                        shape = RoundedCornerShape(4.dp),
                        border = BorderStroke(0.5.dp, AmberAccent.copy(alpha = 0.3f))
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Logout,
                                contentDescription = null,
                                modifier = Modifier.size(10.dp),
                                tint = AmberDark
                            )
                            Spacer(Modifier.width(3.dp))
                            Text(
                                "DEIN ZIEL",
                                style = MaterialTheme.typography.labelSmall,
                                color = AmberDark,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
                if (isLast) {
                    Surface(
                        color = TealLight,
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.LastPage,
                                contentDescription = null,
                                modifier = Modifier.size(10.dp),
                                tint = TealDark
                            )
                            Spacer(Modifier.width(3.dp))
                            Text(
                                "ENDSTATION",
                                style = MaterialTheme.typography.labelSmall,
                                color = TealDark,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
            if (!isCancelled && sevInfo != null) {
                SevStopGuidance(sevInfo)
            }
        }
    }
}

@Composable
private fun SevStopGuidance(info: SevStopInfo) {
    var expanded by remember(info) { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    val accent = if (info.hasCoordinates) TealAccent else AmberAccent
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.DirectionsBus, null, modifier = Modifier.size(15.dp), tint = accent)
            Spacer(Modifier.width(5.dp))
            Text("SEV-Haltestelle", style = MaterialTheme.typography.labelMedium,
                color = accent, fontWeight = FontWeight.SemiBold)
        }
        info.label?.takeIf { it.isNotBlank() }?.let { label ->
            Text(label, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f))
        }
        info.reason?.takeIf { it.isNotBlank() }?.let { reason ->
            Text(reason, style = MaterialTheme.typography.bodySmall, color = AmberAccent)
        }
        if (info.guidance.isNotBlank()) {
            Text(info.guidance, style = MaterialTheme.typography.bodySmall,
                maxLines = if (expanded) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f))
            TextButton(onClick = { expanded = !expanded },
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)) {
                Text(if (expanded) "Wegbeschreibung einklappen" else "Wegbeschreibung anzeigen",
                    style = MaterialTheme.typography.labelSmall)
            }
        }
        if (info.sourceUrl.startsWith("https://www.bahnhof.de/")) {
            TextButton(onClick = { runCatching { uriHandler.openUri(info.sourceUrl) } },
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)) {
                Text("Lageplan auf bahnhof.de", style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Default.OpenInNew, null, modifier = Modifier.size(13.dp))
            }
        }
    }
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

@Composable
private fun RoadRouteAttribution() {
    val uriHandler = LocalUriHandler.current
    Column(Modifier.padding(horizontal = 24.dp, vertical = 2.dp)) {
        Text(
            "Straßenmodell: OSRM · © OpenStreetMap-Mitwirkende",
            modifier = Modifier.clickable {
                runCatching { uriHandler.openUri("https://routing.openstreetmap.de/about.html") }
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            "Kartendaten verbessern ↗",
            modifier = Modifier.padding(vertical = 4.dp).clickable {
                runCatching { uriHandler.openUri("https://www.openstreetmap.org/fixthemap") }
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

private fun gpsTimeUnavailableMessage(reason: GpsTimeUnavailableReason): String =
    when (reason) {
        GpsTimeUnavailableReason.NO_FRESH_LOCATION ->
            "Zeitprognose wartet auf frisches GPS."
        GpsTimeUnavailableReason.INACCURATE_LOCATION ->
            "GPS ist für eine Zeitprognose zu ungenau."
        GpsTimeUnavailableReason.VISIT_UNCONFIRMED ->
            "Der GPS-Halt ist noch nicht sicher zugeordnet."
        GpsTimeUnavailableReason.ROUTE_UNSUPPORTED ->
            "Für die Zeitprognose fehlen passende Streckendaten."
        GpsTimeUnavailableReason.ROUTE_GEOMETRY_UNAVAILABLE ->
            "Für die GPS-Prognose ist noch keine passende Straßenroute verfügbar."
        GpsTimeUnavailableReason.AMBIGUOUS_ROUTE ->
            "Der GPS-Fortschritt ist auf den möglichen Fahrwegen noch nicht eindeutig."
        GpsTimeUnavailableReason.WAITING_AT_ORIGIN ->
            "Die Zeitprognose wartet auf bestätigte Fahrbewegung nach dem Einstieg."
        GpsTimeUnavailableReason.OUTSIDE_CORRIDOR ->
            "Die GPS-Position liegt außerhalb des berechneten Fahrtwegs."
        GpsTimeUnavailableReason.INSUFFICIENT_MOVEMENT ->
            "Für die Zeitprognose wird weitere Fahrbewegung benötigt."
        GpsTimeUnavailableReason.UNPLAUSIBLE_MOVEMENT ->
            "Die GPS-Bewegung ist für eine Zeitprognose noch nicht eindeutig."
    }

private fun formatTimeFromIso(isoTimestamp: String?): String {
    if (isoTimestamp.isNullOrBlank()) return "–"
    return try {
        val zdt = ZonedDateTime.parse(isoTimestamp)
        val local = zdt.withZoneSameInstant(ZoneId.systemDefault())
        local.format(DateTimeFormatter.ofPattern("HH:mm"))
    } catch (_: Exception) {
        isoTimestamp.substringAfter("T").take(5).ifBlank { "–" }
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
    else              -> cat
}

private fun travelReasonFromValue(value: Int?): TravelReason = when (value) {
    TravelReason.BUSINESS.apiValue -> TravelReason.BUSINESS
    TravelReason.COMMUTE.apiValue -> TravelReason.COMMUTE
    else -> TravelReason.PRIVATE
}

private fun travelReasonIcon(reason: TravelReason) = when (reason) {
    TravelReason.PRIVATE -> Icons.Default.Person
    TravelReason.BUSINESS -> Icons.Default.Work
    TravelReason.COMMUTE -> Icons.Default.Home
}

private fun travelReasonTitle(reason: TravelReason): String = when (reason) {
    TravelReason.PRIVATE -> "Privat"
    TravelReason.BUSINESS -> "Geschäftlich"
    TravelReason.COMMUTE -> "Arbeitsweg"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditStatusDialog(
    uiState: StatusDetailUiState,
    onDismiss: () -> Unit,
    onUpdateBody: (String) -> Unit,
    onUpdateDeparture: (String) -> Unit,
    onUpdateArrival: (String) -> Unit,
    onUpdateDestination: (StopStation) -> Unit,
    onSave: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(
                onClick = onSave,
                enabled = !uiState.isUpdating
            ) {
                if (uiState.isUpdating) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("Speichern")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !uiState.isUpdating) {
                Text("Abbrechen")
            }
        },
        title = { Text("Fahrt bearbeiten") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Destination selection
                var expanded by remember { mutableStateOf(false) }
                val selectedStop = uiState.editDestinationStop
                
                Text("Ausstieg", style = MaterialTheme.typography.labelMedium)
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { exp -> expanded = exp }
                ) {
                    OutlinedTextField(
                        value = selectedStop?.let {
                            "${it.stationName ?: "–"} · ${formatTimeFromIso(it.arrivalPlanned)}"
                        } ?: "Ziel auswählen",
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        for (stop in uiState.stopovers) {
                            DropdownMenuItem(
                                text = {
                                    Text("${stop.stationName ?: "–"} · ${formatTimeFromIso(stop.arrivalPlanned)}")
                                },
                                enabled = stop.stationId != null && !stop.arrivalPlanned.isNullOrBlank(),
                                onClick = {
                                    onUpdateDestination(stop)
                                    expanded = false
                                }
                            )
                        }
                    }
                }

                // Times
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = uiState.editDeparture,
                        onValueChange = onUpdateDeparture,
                        label = { Text("Abfahrt real") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = uiState.editArrival,
                        onValueChange = onUpdateArrival,
                        label = { Text("Ankunft real") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }

                // Status text
                OutlinedTextField(
                    value = uiState.editBody,
                    onValueChange = onUpdateBody,
                    label = { Text("Status-Text") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3
                )
            }
        }
    )
}
