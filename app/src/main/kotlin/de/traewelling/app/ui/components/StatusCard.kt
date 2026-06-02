package de.traewelling.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import de.traewelling.app.data.model.Status
import de.traewelling.app.ui.theme.*
import de.traewelling.app.util.formatTimestamp

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
    val accentColor = TransportColors.forCategory(checkin?.category)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clickable { onStatusClick() },
        shape = RoundedCornerShape(22.dp),
        elevation = CardDefaults.cardElevation(6.dp),
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.12f)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
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
                            .size(44.dp)
                            .clip(CircleShape)
                            .border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), CircleShape)
                    )
                    Spacer(Modifier.width(12.dp))
                } else {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Person, null,
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                    }
                    Spacer(Modifier.width(12.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        user?.displayName ?: user?.username ?: "Unbekannt",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.06f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                "@${user?.username ?: ""}",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "• " + formatTimestamp(status.createdAt ?: ""),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        )
                    }
                }
            }

            // Train/checkin info
            if (checkin != null) {
                Spacer(Modifier.height(12.dp))
                
                val category = checkin.category ?: checkin.lineName?.split(" ")?.firstOrNull() ?: ""
                val lineColors = TransportColors.forCategory(category)

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(
                                    lineColors.copy(alpha = 0.16f),
                                    MaterialTheme.colorScheme.secondary.copy(alpha = 0.08f),
                                    MaterialTheme.colorScheme.surface
                                )
                            )
                        )
                        .border(1.dp, lineColors.copy(alpha = 0.12f), RoundedCornerShape(16.dp))
                ) {
                    Column(Modifier.padding(14.dp)) {
                        // Line Header
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                color = lineColors,
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Train,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                        tint = Color.White
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        checkin.lineName ?: "Unbekannte Linie",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                }
                            }

                            Spacer(Modifier.weight(1f))

                            if (checkin.points != null && checkin.points > 0) {
                                StatPill(Icons.Default.Stars, "${checkin.points} Pkt", lineColors)
                            }
                        }

                        Spacer(Modifier.height(12.dp))

                        // Origin to Destination with gradient line
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            // Timeline visual
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(modifier = Modifier
                                    .size(12.dp)
                                    .background(TealDark, CircleShape))
                                Box(
                                    modifier = Modifier
                                        .width(3.dp)
                                        .height(26.dp)
                                        .background(
                                            Brush.verticalGradient(
                                                colors = listOf(TealDark, AmberDark)
                                            )
                                        )
                                )
                                Box(modifier = Modifier
                                    .size(12.dp)
                                    .background(AmberDark, CircleShape))
                            }
                            Spacer(Modifier.width(12.dp))
                            // Stations
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    checkin.origin?.name ?: "–",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    checkin.destination?.name ?: "–",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }

            // Status text
            if (!status.body.isNullOrBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    status.body,
                    style = MaterialTheme.typography.bodyMedium,
                    lineHeight = 20.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f)
                )
            }

            // Actions row (Like & Comments)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    onClick = onLike,
                    shape = RoundedCornerShape(16.dp),
                    color = if (isLiked) MaterialTheme.colorScheme.error.copy(alpha = 0.1f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = "Like",
                            tint = if (isLiked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            (status.likes ?: 0).toString(),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (isLiked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Details",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}
