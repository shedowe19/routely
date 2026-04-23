package de.traewelling.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import de.traewelling.app.data.model.StopStation
import de.traewelling.app.data.model.Status
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
            TopAppBar(
                title = { 
                    Text(
                        "Fahrt-Details",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                },
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
                        Surface(
                            color = Color.White.copy(alpha = 0.2f),
                            shape = CircleShape,
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Box(
                                    Modifier
                                        .size(6.dp)
                                        .background(Color.White, CircleShape)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "Live",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    if (uiState.isOwnStatus) {
                        if (uiState.isDeleting || uiState.isUpdating) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp).padding(4.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
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
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { innerPadding ->
        Column(Modifier.fillMaxSize().padding(innerPadding)) {

        when {
            uiState.isLoading && uiState.status == null -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text("Lade Fahrt-Details…",
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    }
                }
            }
            uiState.error != null && uiState.status == null -> {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.ErrorOutline, null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(12.dp))
                        Text(uiState.error!!, color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { viewModel.refresh() }) { Text("Erneut versuchen") }
                    }
                }
            }
            else -> {
                val status = uiState.status
                if (status != null) {
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

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 80.dp)
                ) {
                    // Status header card
                    item {
                        StatusHeaderCard(status, onUserClick)
                    }

                    // Trip info card
                    if (checkin != null) {
                        item {
                            TripInfoCard(status)
                        }
                    }

                    // Stopovers header
                    if (stopovers.isNotEmpty()) {
                        item {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "Haltestellenverlauf",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    "${stopovers.size} Halte",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                )
                            }
                        }

                        // Stopovers list
                        itemsIndexed(stopovers) { index, stop ->
                            val originId = checkin?.origin?.id
                            val destinationId = checkin?.destination?.id
                            
                            val originIdx = remember(stopovers, originId) { 
                                stopovers.indexOfFirst { it.id == originId } 
                            }
                            val destinationIdx = remember(stopovers, destinationId) { 
                                stopovers.indexOfFirst { it.id == destinationId } 
                            }

                            val isOrigin = stop.id == originId
                            val isDestination = stop.id == destinationId
                            val isInRange = isStopInRange(stopovers, index, originId, destinationId)

                            val prevStop = stopovers.getOrNull(index - 1)
                            val nextStop = stopovers.getOrNull(index + 1)

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
                    } else if (uiState.isLoading) {
                        item {
                            Box(Modifier.fillMaxWidth().padding(32.dp),
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
}

@Composable
private fun StatusHeaderCard(status: Status, onUserClick: (String) -> Unit) {
    val user = status.user

    Card(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            // User row
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (user?.profilePicture != null) {
                    AsyncImage(
                        model = user.profilePicture,
                        contentDescription = null,
                        modifier = Modifier.size(44.dp).clip(CircleShape)
                    )
                } else {
                    Icon(Icons.Default.AccountCircle, null,
                        modifier = Modifier.size(44.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                }
                Spacer(Modifier.width(12.dp))
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
            }

            // Status body
            if (!status.body.isNullOrBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(status.body, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun TripInfoCard(status: Status) {
    val checkin = status.checkin ?: return

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        )
    ) {
        Column(Modifier.padding(16.dp)) {
            // Line name + category
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        checkin.lineName ?: "?",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        localiseCategory(checkin.category ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
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

            Spacer(Modifier.height(12.dp))

            // Origin → Destination
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.TripOrigin, null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(6.dp))
                Text(checkin.origin?.name ?: "–", fontWeight = FontWeight.Medium)
            }
            Row(modifier = Modifier.padding(start = 7.dp)) {
                Box(
                    Modifier
                        .width(2.dp)
                        .height(20.dp)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.LocationOn, null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(6.dp))
                Text(checkin.destination?.name ?: "–", fontWeight = FontWeight.Medium)
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))

            // Stats
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                if (checkin.distanceMeters != null) {
                    TripStatItem(Icons.Default.Route, "%.1f km".format(checkin.distanceMeters / 1000.0))
                }
                if (checkin.duration != null) {
                    TripStatItem(Icons.Default.Schedule, "${checkin.duration} min")
                }
                if (checkin.points != null) {
                    TripStatItem(Icons.Default.Stars, "${checkin.points} Pkt")
                }
            }

            // Departure / Arrival times with delays
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))

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
private fun TripStatItem(icon: androidx.compose.ui.graphics.vector.ImageVector, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(4.dp))
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
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
                
                val timeColor = if (delayMin > 0) MaterialTheme.colorScheme.error else Color(0xFF2E7D32)
                
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
    var outgoingProgress = 0f
    if (stopZdt != null && nextZdt != null) {
        if (now.isAfter(stopZdt) && now.isBefore(nextZdt)) {
            val total = java.time.Duration.between(stopZdt, nextZdt).toMillis()
            val elapsed = java.time.Duration.between(stopZdt, now).toMillis()
            outgoingProgress = (elapsed.toFloat() / total.toFloat()).coerceIn(0f, 1f)
        } else if (now.isAfter(nextZdt)) {
            outgoingProgress = 1f
        }
    }
    
    // Progress for the segment COMING FROM the previous stop to this one
    var incomingProgress = 0f
    if (prevZdt != null && stopZdt != null) {
        if (now.isAfter(prevZdt) && now.isBefore(stopZdt)) {
            val total = java.time.Duration.between(prevZdt, stopZdt).toMillis()
            val elapsed = java.time.Duration.between(prevZdt, now).toMillis()
            incomingProgress = (elapsed.toFloat() / total.toFloat()).coerceIn(0f, 1f)
        } else if (now.isAfter(stopZdt)) {
            incomingProgress = 1f
        }
    }

    val isPast = stopZdt?.isBefore(now) ?: false
    val trainIsHere = stopZdt != null && now.isAfter(stopZdt.minusMinutes(1)) && now.isBefore(stopZdt.plusMinutes(1))
    val isCurrentSegment = outgoingProgress > 0f && outgoingProgress < 1f

    val lineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
    val activeLineColor = MaterialTheme.colorScheme.primary

    val dotColor = when {
        isOrigin -> MaterialTheme.colorScheme.primary
        isDestination -> MaterialTheme.colorScheme.error
        isPast || trainIsHere -> MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
        isInRange -> MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
        stop.cancelled == true -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
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
                        .width(if (isTopTraveled) 6.dp else 3.dp)
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
            
            // Dot
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(14.dp)) {
                Box(
                    Modifier
                        .size(if (isOrigin || isDestination || isLast || isActualLast || isFirst || isActualFirst || trainIsHere) 14.dp else 10.dp)
                        .background(if (stop.cancelled == true) dotColor.copy(alpha = 0.5f) else dotColor, CircleShape)
                )
                if (trainIsHere) {
                    Box(Modifier.size(6.dp).background(Color.White, CircleShape))
                }
            }
            
            // Bottom line (Outgoing segment to next stop)
            if (!isActualLast) {
                Box(
                    Modifier
                        .width(if (isBottomTraveled) 6.dp else 3.dp)
                        .weight(1f) 
                ) {
                    // Background track
                    Box(Modifier.fillMaxSize().background(if (stop.cancelled == true) lineColor.copy(alpha = 0.5f) else lineColor))
                    
                    // Active progress track: This covers 0% to 80% of the segment A->B
                    if (outgoingProgress > 0f) {
                        val partProgress = (outgoingProgress / 0.8f).coerceIn(0f, 1f)
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .fillMaxHeight(partProgress)
                                .background(activeLineColor)
                        ) {
                            // Train icon indicator
                            if (isCurrentSegment && outgoingProgress <= 0.8f) {
                                Icon(
                                    Icons.Default.Train,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .size(16.dp)
                                        .align(Alignment.BottomCenter)
                                        .offset(y = 8.dp)
                                )
                            }
                        }
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
                    color = if (isCancelled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = textAlpha),
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

                val timeToShowPlanned = if (isOrigin || (plannedDeparture != null && !isDestination)) plannedDeparture else plannedArrival
                val timeToShowReal = if (isOrigin || (realDeparture != null && !isDestination)) realDeparture else realArrival
                
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
                                val badgeColor = if (delayMin > 0) MaterialTheme.colorScheme.error else Color(0xFF2E7D32)
                                val containerColor = if (delayMin > 0) MaterialTheme.colorScheme.errorContainer else Color(0xFFE8F5E9)
                                
                                Surface(
                                    color = containerColor,
                                    shape = MaterialTheme.shapes.small
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
                                color = if (delayMin > 0) MaterialTheme.colorScheme.error else Color(0xFF2E7D32),
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
            }

            // Platform + cancelled
            Row {
                val plat = stop.platform ?: stop.departurePlatformReal ?: stop.arrivalPlatformReal
                if (plat != null) {
                    val displayPlat = if (plat.startsWith("Gl", ignoreCase = true)) plat else "Gl. $plat"
                    Text(
                        displayPlat,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                }
                if (isCancelled) {
                    if (plat != null) Spacer(Modifier.width(8.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = MaterialTheme.shapes.extraSmall
                    ) {
                        Text(
                            "HALT ENTFÄLLT",
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Black
                        )
                    }
                }
                if (isFirst) {
                    if (plat != null || isCancelled) Spacer(Modifier.width(8.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = MaterialTheme.shapes.extraSmall
                    ) {
                        Text(
                            "STARTHALTESTELLE",
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            fontWeight = FontWeight.Black
                        )
                    }
                }
                if (isOrigin) {
                    if (plat != null || isCancelled || isFirst) Spacer(Modifier.width(8.dp))
                    Surface(
                        color = Color(0xFFFFF3E0), // Light Orange/Amber
                        shape = RoundedCornerShape(4.dp),
                        border = BorderStroke(1.dp, Color(0xFFFFB74D).copy(alpha = 0.5f))
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Icon(
                                Icons.Default.Login,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp),
                                tint = Color(0xFFE65100)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "DEIN EINSTIEG",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFFE65100),
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
                if (isDestination) {
                    if (plat != null || isCancelled || isFirst || isOrigin) Spacer(Modifier.width(8.dp))
                    Surface(
                        color = Color(0xFFFFF3E0), // Light Orange/Amber
                        shape = RoundedCornerShape(4.dp),
                        border = BorderStroke(1.dp, Color(0xFFFFB74D).copy(alpha = 0.5f))
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Icon(
                                Icons.Default.Logout,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp),
                                tint = Color(0xFFE65100)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "DEIN ZIEL",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFFE65100),
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
                if (isLast) {
                    if (plat != null || isCancelled || isOrigin || isDestination) Spacer(Modifier.width(8.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = MaterialTheme.shapes.extraSmall
                    ) {
                        Text(
                            "ENDSTATION",
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            fontWeight = FontWeight.Black
                        )
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
