package de.traewelling.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import de.traewelling.app.data.model.CheckinInfo
import de.traewelling.app.data.model.Status
import de.traewelling.app.ui.theme.AmberAccent
import de.traewelling.app.ui.theme.TransportColors
import de.traewelling.app.util.formatTimestamp
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun StatusCard(
    status: Status,
    onLike: (() -> Unit)?,
    onUserClick: (String) -> Unit = {},
    onStatusClick: () -> Unit = {}
) {
    val user = status.user
    val checkin = status.checkin
    val isLiked = status.liked == true
    val category = checkin?.category ?: checkin?.lineName?.split(" ")?.firstOrNull()
    val accentColor = TransportColors.forCategory(category)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp)
            .clickable { onStatusClick() },
        shape = RoundedCornerShape(18.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable {
                    user?.username?.let { onUserClick(it) }
                }
            ) {
                StatusAvatar(
                    imageUrl = user?.profilePicture,
                    displayName = user?.displayName ?: user?.username
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        user?.displayName ?: user?.username ?: "Unbekannt",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "@${user?.username ?: ""}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Text(
                            " · ${formatTimestamp(status.createdAt ?: "")}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }
            }

            if (checkin != null) {
                Spacer(Modifier.height(10.dp))
                JourneyPanel(checkin = checkin, accentColor = accentColor)
            }

            if (!status.body.isNullOrBlank()) {
                Spacer(Modifier.height(9.dp))
                Text(
                    status.body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.92f)
                )
            }

            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    modifier = Modifier
                        .heightIn(min = 32.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(
                            enabled = onLike != null && status.isLikable != false,
                            onClick = { onLike?.invoke() }
                        )
                        .padding(horizontal = 7.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = "Like",
                        tint = if (isLiked) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(17.dp)
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        (status.likes ?: 0).toString(),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isLiked) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }

                Spacer(Modifier.weight(1f))

                Row(
                    modifier = Modifier
                        .heightIn(min = 32.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onStatusClick() }
                        .padding(horizontal = 7.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Details",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.width(2.dp))
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusAvatar(imageUrl: String?, displayName: String?) {
    val initial = displayName?.trim()?.firstOrNull()?.uppercase() ?: "?"
    if (imageUrl != null) {
        AsyncImage(
            model = imageUrl,
            contentDescription = null,
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
        )
    } else {
        Box(
            modifier = Modifier
                .size(38.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                initial,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
private fun JourneyPanel(checkin: CheckinInfo, accentColor: Color) {
    val departure = formatClock(checkin.manualDeparture ?: checkin.origin?.effectiveDeparture)
    val arrival = formatClock(checkin.manualArrival ?: checkin.destination?.effectiveArrival)

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LineBadge(
                    lineName = checkin.lineName ?: "Fahrt",
                    category = checkin.category,
                    accentColor = accentColor
                )

                Spacer(Modifier.weight(1f))

                if ((checkin.points ?: 0) > 0) {
                    Icon(
                        Icons.Default.Star,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "${checkin.points} Pkt",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(9.dp))

            Row {
                Column(
                    modifier = Modifier
                        .width(12.dp)
                        .padding(top = 5.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .background(MaterialTheme.colorScheme.secondary, CircleShape)
                    )
                    Box(
                        Modifier
                            .width(2.dp)
                            .height(21.dp)
                            .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.35f))
                    )
                    Box(
                        Modifier
                            .size(8.dp)
                            .background(AmberAccent, CircleShape)
                    )
                }

                Spacer(Modifier.width(9.dp))

                Column(Modifier.weight(1f)) {
                    RouteLine(
                        station = checkin.origin?.stationName ?: "–",
                        time = departure
                    )
                    Spacer(Modifier.height(13.dp))
                    RouteLine(
                        station = checkin.destination?.stationName ?: "–",
                        time = arrival
                    )
                }
            }
        }
    }
}

@Composable
private fun RouteLine(station: String, time: String?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            station,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (time != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                time,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun LineBadge(lineName: String, category: String?, accentColor: Color) {
    Surface(
        color = accentColor,
        shape = RoundedCornerShape(7.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .widthIn(max = 190.dp)
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Icon(
                transportIcon(category),
                contentDescription = null,
                modifier = Modifier.size(13.dp),
                tint = Color.White
            )
            Spacer(Modifier.width(5.dp))
            Text(
                lineName,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun transportIcon(category: String?) = when (category) {
    "bus" -> Icons.Default.DirectionsBus
    "tram" -> Icons.Default.Tram
    "subway" -> Icons.Default.Subway
    "ferry" -> Icons.Default.DirectionsBoat
    else -> Icons.Default.Train
}

private fun formatClock(iso: String?): String? {
    if (iso.isNullOrBlank()) return null
    val formatter = DateTimeFormatter.ofPattern("HH:mm")
    return runCatching {
        OffsetDateTime.parse(iso)
            .atZoneSameInstant(ZoneId.systemDefault())
            .format(formatter)
    }.recoverCatching {
        LocalDateTime.parse(iso).format(formatter)
    }.getOrNull()
}
