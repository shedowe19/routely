package de.traewelling.app.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import de.traewelling.app.data.model.StopStation
import de.traewelling.app.data.model.Status
import de.traewelling.app.ui.components.TraewellingTopAppBar
import de.traewelling.app.ui.theme.*
import de.traewelling.app.viewmodel.StatusDetailViewModel
import de.traewelling.app.viewmodel.StatusDetailUiState
import kotlinx.coroutines.delay
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
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "Lade Fahrt-Details…",
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
                "ERROR" -> {
                    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.ErrorOutline, null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(uiState.error!!, color = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.height(16.dp))
                            Button(onClick = { viewModel.refresh() }) { Text("Erneut versuchen") }
                        }
                    }
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

    // Real-time ticking for smooth progress bar updates
    var now by remember { mutableStateOf(ZonedDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = ZonedDateTime.now()
        }
    }

    val firstRealStopIndex = remember(stopovers) {
        stopovers.indexOfFirst { it.cancelled != true }
    }
    val lastRealStopIndex = remember(stopovers) {
        stopovers.indexOfLast { it.cancelled != true }
    }

    val originId = checkin?.origin?.id
    val destinationId = checkin?.destination?.id

    val originIdx = remember(stopovers, originId) {
        stopovers.indexOfFirst { it.id == originId }
    }
    val destinationIdx = remember(stopovers, destinationId) {
        stopovers.indexOfFirst { it.id == destinationId }
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
                    TripInfoCard(status)
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
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Timeline, null,
                                modifier = Modifier.size(18.dp),
                                tint = DeepIndigo.copy(alpha = 0.5f)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Haltestellenverlauf",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                            Surface(
                                color = DeepIndigo.copy(alpha = 0.08f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(
                                    "${stopovers.size} Halte",
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = DeepIndigo.copy(alpha = 0.6f),
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }

            // Stopovers list
            items(stopovers.size) { index ->
                val stop = stopovers[index]
                val isOrigin = stop.id == originId
                val isDestination = stop.id == destinationId
                val isInRange = isStopInRange(stopovers, index, originId, destinationId)

                val prevStop = stopovers.getOrNull(index - 1)
                val nextStop = stopovers.getOrNull(index + 1)

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
                        prevStop = prevStop,
                        nextStop = nextStop,
                        now = now,
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
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp))
                }
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
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(Modifier.padding(16.dp)) {
            // User row
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (user?.profilePicture != null) {
                    AsyncImage(
                        model = user.profilePicture,
                        contentDescription = null,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .border(2.dp, DeepIndigo.copy(alpha = 0.15f), CircleShape)
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .background(DeepIndigo.copy(alpha = 0.08f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Person, null,
                            modifier = Modifier.size(28.dp),
                            tint = DeepIndigo.copy(alpha = 0.5f))
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
                        color = DeepIndigo.copy(alpha = 0.06f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            "@${user?.username ?: ""}",
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = DeepIndigo.copy(alpha = 0.5f)
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
private fun TripInfoCard(status: Status) {
    val checkin = status.checkin ?: return
    val transportColor = TransportColors.forCategory(checkin.category)

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(4.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
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

            Spacer(Modifier.height(16.dp))

            // Origin → Destination with mini-timeline
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).background(TealAccent, CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(checkin.origin?.name ?: "–", fontWeight = FontWeight.SemiBold)
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
                Text(checkin.destination?.name ?: "–", fontWeight = FontWeight.SemiBold)
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
                    StatPill(Icons.Default.Schedule, "${checkin.duration} min", DeepIndigo)
                }
                if (checkin.points != null) {
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
                TimeRow("Abfahrt", origin.departurePlanned, origin.departureReal, origin.isDepartureDelayed)
            }
            if (dest != null) {
                TimeRow("Ankunft", dest.arrivalPlanned, dest.arrivalReal, dest.isArrivalDelayed)
            }
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
private fun TimeRow(label: String, planned: String?, real: String?, isDelayed: Boolean?) {
    val plannedTime = formatTimeFromIso(planned)
    val realTimeVal = real ?: planned
    val realTime = formatTimeFromIso(realTimeVal)
    val timeDiffers = plannedTime != realTime && plannedTime != "–"
    val delayMin = computeDelayMinutes(planned, realTimeVal)

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (timeDiffers) {
                Text(
                    plannedTime,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough
                )
                Spacer(Modifier.width(6.dp))
                
                val timeColor = if (delayMin > 0) WarningOrange else SuccessGreen
                
                Text(
                    realTime,
                    style = MaterialTheme.typography.bodySmall,
                    color = timeColor,
                    fontWeight = FontWeight.Bold
                )
            } else {
                Text(plannedTime, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun StopoverItem(
    stop: StopStation,
    prevStop: StopStation?,
    nextStop: StopStation?,
    now: ZonedDateTime,
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
    // Determine times for this stop
    val stopZdt = remember(stop) { 
        val timeStr = stop.departureReal ?: stop.departurePlanned ?: stop.arrivalReal ?: stop.arrivalPlanned ?: stop.arrival
        try { timeStr?.let { ZonedDateTime.parse(it) } } catch (e: Exception) { null }
    }

    // Determine times for next stop
    val nextZdt = remember(nextStop) { 
        val timeStr = nextStop?.arrivalReal ?: nextStop?.arrivalPlanned ?: nextStop?.arrival ?: nextStop?.departurePlanned
        try { timeStr?.let { ZonedDateTime.parse(it) } } catch (e: Exception) { null }
    }
    
    // Determine times for previous stop (to handle the incoming line)
    val prevZdt = remember(prevStop) {
        val timeStr = prevStop?.departureReal ?: prevStop?.departurePlanned ?: prevStop?.departure ?: prevStop?.arrivalReal
        try { timeStr?.let { ZonedDateTime.parse(it) } } catch (e: Exception) { null }
    }

    // Progress for the segment STARTING at this stop and going to the next
    var rawOutgoingProgress = 0f
    if (stopZdt != null && nextZdt != null) {
        if (now.isAfter(stopZdt) && now.isBefore(nextZdt)) {
            val total = java.time.Duration.between(stopZdt, nextZdt).toMillis()
            val elapsed = java.time.Duration.between(stopZdt, now).toMillis()
            rawOutgoingProgress = (elapsed.toFloat() / total.toFloat()).coerceIn(0f, 1f)
        } else if (now.isAfter(nextZdt)) {
            rawOutgoingProgress = 1f
        }
    }
    
    // Progress for the segment COMING FROM the previous stop to this one
    var rawIncomingProgress = 0f
    if (prevZdt != null && stopZdt != null) {
        if (now.isAfter(prevZdt) && now.isBefore(stopZdt)) {
            val total = java.time.Duration.between(prevZdt, stopZdt).toMillis()
            val elapsed = java.time.Duration.between(prevZdt, now).toMillis()
            rawIncomingProgress = (elapsed.toFloat() / total.toFloat()).coerceIn(0f, 1f)
        } else if (now.isAfter(stopZdt)) {
            rawIncomingProgress = 1f
        }
    }

    val outgoingProgress by animateFloatAsState(
        targetValue = rawOutgoingProgress,
        animationSpec = tween(durationMillis = 1000, easing = LinearEasing),
        label = "outgoingProgress"
    )

    val incomingProgress by animateFloatAsState(
        targetValue = rawIncomingProgress,
        animationSpec = tween(durationMillis = 1000, easing = LinearEasing),
        label = "incomingProgress"
    )

    val isPast = stopZdt?.isBefore(now) ?: false
    val trainIsHere = stopZdt != null && now.isAfter(stopZdt.minusMinutes(1)) && now.isBefore(stopZdt.plusMinutes(1))
    val isCurrentSegment = outgoingProgress > 0f && outgoingProgress < 1f

    val lineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
    val activeLineColor = TealAccent

    val dotColor = when {
        isOrigin -> TealAccent
        isDestination -> AmberAccent
        isPast || trainIsHere -> TealAccent.copy(alpha = 0.7f)
        isInRange -> TealAccent.copy(alpha = 0.35f)
        stop.cancelled == true -> ErrorRed
        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
    }

    val textAlpha = when {
        isOrigin || isDestination -> 1f
        isPast || trainIsHere -> 1f
        isInRange -> 0.8f
        else -> 0.45f
    }
    
    val isDelayed = stop.isArrivalDelayed == true || stop.isDepartureDelayed == true
    val isCancelled = stop.cancelled == true

    // Journeys range logic for lines
    val isTopTraveled = originIndex != -1 && destinationIndex != -1 && index > originIndex && index <= destinationIndex
    val isBottomTraveled = originIndex != -1 && destinationIndex != -1 && index >= originIndex && index < destinationIndex

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.Top
    ) {
        // Timeline column
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .width(24.dp)
                .fillMaxHeight()
        ) {
            // Top line (Incoming segment from previous stop)
            if (!isActualFirst) {
                Box(
                    Modifier
                        .width(if (isTopTraveled) 4.dp else 2.dp)
                        .height(16.dp)
                ) {
                    // Background track
                    Box(Modifier.fillMaxSize().background(if (isCancelled) lineColor.copy(alpha = 0.5f) else lineColor))
                    // Progress handling
                    if (incomingProgress > 0.8f) {
                        val partProgress = ((incomingProgress - 0.8f) / 0.2f).coerceIn(0f, 1f)
                        Box(Modifier.fillMaxWidth().fillMaxHeight(partProgress).background(activeLineColor))
                    } else if (isPast || trainIsHere) {
                        Box(Modifier.fillMaxSize().background(activeLineColor))
                    }
                }
            } else {
                Spacer(Modifier.height(16.dp))
            }
            
            // Dot with ring effect for important stops
            val isImportant = isOrigin || isDestination || isLast || isFirst || isActualFirst || isActualLast
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(if (isImportant) 18.dp else 14.dp)) {
                if (isImportant && !isCancelled) {
                    Box(
                        Modifier
                            .size(18.dp)
                            .background(dotColor.copy(alpha = 0.2f), CircleShape)
                    )
                }
                Box(
                    Modifier
                        .size(if (isImportant || trainIsHere) 12.dp else 8.dp)
                        .background(if (isCancelled) dotColor.copy(alpha = 0.5f) else dotColor, CircleShape)
                )
                if (trainIsHere) {
                    Box(Modifier.size(5.dp).background(Color.White, CircleShape))
                }
            }
            
            // Bottom line (Outgoing segment to next stop)
            if (!isActualLast) {
                Box(
                    Modifier
                        .width(if (isBottomTraveled) 4.dp else 2.dp)
                        .weight(1f) 
                ) {
                    // Background track
                    Box(Modifier.fillMaxSize().background(if (isCancelled) lineColor.copy(alpha = 0.5f) else lineColor))
                    
                    // Active progress track
                    if (outgoingProgress > 0f) {
                        val partProgress = (outgoingProgress / 0.8f).coerceIn(0f, 1f)
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .fillMaxHeight(partProgress)
                                .background(activeLineColor)
                        )
                    }
                }
            } else {
                Spacer(Modifier.height(16.dp))
            }
        }

        Spacer(Modifier.width(12.dp))

        // Content
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 12.dp) // More padding to give space, IntrinsicSize.Min will handle it
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stop.name ?: "–",
                    fontWeight = if (isOrigin || isDestination) FontWeight.Bold else FontWeight.SemiBold,
                    color = if (isCancelled) ErrorRed else MaterialTheme.colorScheme.onSurface.copy(alpha = textAlpha),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                    textDecoration = if (isCancelled) androidx.compose.ui.text.style.TextDecoration.LineThrough else null
                )

                // Time display
                val plannedDeparture = stop.departurePlanned ?: stop.departure
                val realDeparture = stop.departureReal ?: plannedDeparture
                val plannedArrival = stop.arrivalPlanned ?: stop.arrival
                val realArrival = stop.arrivalReal ?: plannedArrival

                val timeToShowPlanned = if (isOrigin) plannedDeparture ?: plannedArrival else if (isDestination) plannedArrival ?: plannedDeparture else null
                val timeToShowReal = if (isOrigin) realDeparture ?: realArrival else if (isDestination) realArrival ?: realDeparture else null

                if (isOrigin || isDestination) {
                    val plannedTimeStr = formatTimeFromIso(timeToShowPlanned)
                    val realTimeStr = formatTimeFromIso(timeToShowReal)

                    val delayMin = computeDelayMinutes(timeToShowPlanned, timeToShowReal)
                    val timeDiffers = plannedTimeStr != realTimeStr && plannedTimeStr != "–"

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (!isCancelled) {
                            if (timeDiffers) {
                                Text(
                                    plannedTimeStr,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                                    textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough
                                )
                                Spacer(Modifier.width(6.dp))
                                
                                if (delayMin != 0) {
                                    val badgeColor = if (delayMin > 0) WarningOrange else SuccessGreen
                                    val containerColor = if (delayMin > 0) WarningOrangeLight else SuccessGreenLight

                                    Surface(
                                        color = containerColor,
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        val prefix = if (delayMin > 0) "+" else ""
                                        Text(
                                            "$prefix$delayMin",
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = badgeColor,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Spacer(Modifier.width(6.dp))
                                }

                                Text(
                                    realTimeStr,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (delayMin > 0) WarningOrange else SuccessGreen,
                                    fontWeight = FontWeight.Bold
                                )
                            } else {
                                Text(
                                    text = plannedTimeStr,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = textAlpha)
                                )
                            }
                        }
                    }
                } else {
                    // For stops in between, show arrival and departure
                    Column(horizontalAlignment = Alignment.End) {
                        if (plannedArrival != null || realArrival != null) {
                            val plannedArrStr = formatTimeFromIso(plannedArrival)
                            val realArrStr = formatTimeFromIso(realArrival)
                            val delayArrMin = computeDelayMinutes(plannedArrival, realArrival)
                            val arrDiffers = plannedArrStr != realArrStr && plannedArrStr != "–"
                            
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("An: ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                                if (!isCancelled) {
                                    if (arrDiffers) {
                                        Text(
                                            plannedArrStr,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                                            textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            realArrStr,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = if (delayArrMin > 0) WarningOrange else SuccessGreen,
                                            fontWeight = FontWeight.Bold
                                        )
                                    } else {
                                        Text(
                                            text = plannedArrStr,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = textAlpha)
                                        )
                                    }
                                }
                            }
                        }
                        if (plannedDeparture != null || realDeparture != null) {
                            val plannedDepStr = formatTimeFromIso(plannedDeparture)
                            val realDepStr = formatTimeFromIso(realDeparture)
                            val delayDepMin = computeDelayMinutes(plannedDeparture, realDeparture)
                            val depDiffers = plannedDepStr != realDepStr && plannedDepStr != "–"

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Ab: ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                                if (!isCancelled) {
                                    if (depDiffers) {
                                        Text(
                                            plannedDepStr,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                                            textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            realDepStr,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = if (delayDepMin > 0) WarningOrange else SuccessGreen,
                                            fontWeight = FontWeight.Bold
                                        )
                                    } else {
                                        Text(
                                            text = plannedDepStr,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = textAlpha)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Platform + badges
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(top = 2.dp)
            ) {
                val rawPlat = stop.platform ?: stop.departurePlatformReal ?: stop.arrivalPlatformReal
                // Strip HAFAS sector prefix "9": "91"→"1", "911"→"11", "99"→"9"
                // Some DB stations encode tracks as sector(9) + number internally
                val plat = rawPlat?.let { p ->
                    if (p.length > 1 && p.startsWith("9") && p.drop(1).all { it.isDigit() }) p.drop(1) else p
                }
                if (plat != null) {
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
        }
    }
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

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

private fun computeDelayMinutes(planned: String?, real: String?): Int {
    if (planned == null || real == null) return 0
    return try {
        val p = ZonedDateTime.parse(planned)
        val r = ZonedDateTime.parse(real)
        java.time.Duration.between(p, r).toMinutes().toInt()
    } catch (_: Exception) {
        0
    }
}

private fun isStopInRange(
    stops: List<StopStation>,
    currentIndex: Int,
    originId: Int?,
    destinationId: Int?
): Boolean {
    if (originId == null || destinationId == null) return false
    val originIndex = stops.indexOfFirst { it.id == originId }
    val destIndex = stops.indexOfFirst { it.id == destinationId }
    if (originIndex < 0 || destIndex < 0) return false
    return currentIndex in originIndex..destIndex
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditStatusDialog(
    uiState: StatusDetailUiState,
    onDismiss: () -> Unit,
    onUpdateBody: (String) -> Unit,
    onUpdateDeparture: (String) -> Unit,
    onUpdateArrival: (String) -> Unit,
    onUpdateDestination: (Int) -> Unit,
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
                val selectedStop = uiState.stopovers.find { stop -> stop.id == uiState.editDestinationId }
                
                Text("Ausstieg", style = MaterialTheme.typography.labelMedium)
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { exp -> expanded = exp }
                ) {
                    OutlinedTextField(
                        value = selectedStop?.name ?: "Ziel auswählen",
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
                                text = { Text(stop.name ?: "") },
                                onClick = {
                                    stop.id?.let { sid -> onUpdateDestination(sid) }
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
