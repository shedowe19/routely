package de.traewelling.app.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

@Composable
fun FeedSkeletonList() {
    val alpha = skeletonAlpha()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        repeat(3) {
            FeedSkeletonCard(alpha)
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun FeedSkeletonCard(alpha: Float) {
    Surface(
        modifier = Modifier.fillMaxWidth().alpha(alpha),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(38.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    SkeletonBar(width = 116.dp, height = 12.dp)
                    Spacer(Modifier.height(6.dp))
                    SkeletonBar(width = 84.dp, height = 9.dp)
                }
            }

            Spacer(Modifier.height(10.dp))

            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SkeletonBar(width = 54.dp, height = 22.dp, radius = 7.dp)
                    Spacer(Modifier.weight(1f))
                    SkeletonBar(width = 52.dp, height = 11.dp)
                }
                Spacer(Modifier.height(10.dp))
                Row {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(top = 3.dp)
                    ) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)
                        )
                        Box(
                            Modifier
                                .width(2.dp)
                                .height(22.dp)
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        )
                        Box(
                            Modifier
                                .size(8.dp)
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Row {
                            SkeletonBar(width = 158.dp, height = 12.dp)
                            Spacer(Modifier.weight(1f))
                            SkeletonBar(width = 34.dp, height = 10.dp)
                        }
                        Spacer(Modifier.height(15.dp))
                        Row {
                            SkeletonBar(width = 120.dp, height = 12.dp)
                            Spacer(Modifier.weight(1f))
                            SkeletonBar(width = 34.dp, height = 10.dp)
                        }
                    }
                }
            }

            Spacer(Modifier.height(9.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                SkeletonBar(width = 54.dp, height = 11.dp)
                Spacer(Modifier.weight(1f))
                SkeletonBar(width = 64.dp, height = 11.dp)
            }
        }
    }
}

@Composable
fun NotificationSkeletonList() {
    val alpha = skeletonAlpha()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .alpha(alpha)
    ) {
        repeat(6) { index ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(38.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    SkeletonBar(width = 190.dp, height = 12.dp)
                    Spacer(Modifier.height(6.dp))
                    SkeletonBar(width = 132.dp, height = 10.dp)
                    Spacer(Modifier.height(5.dp))
                    SkeletonBar(width = 58.dp, height = 9.dp)
                }
            }
            if (index < 5) {
                HorizontalDivider(
                    modifier = Modifier.padding(start = 66.dp, end = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                )
            }
        }
    }
}

@Composable
private fun SkeletonBar(
    width: androidx.compose.ui.unit.Dp,
    height: androidx.compose.ui.unit.Dp,
    radius: androidx.compose.ui.unit.Dp = 5.dp
) {
    Box(
        Modifier
            .width(width)
            .height(height)
            .clip(RoundedCornerShape(radius))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
    )
}

@Composable
private fun skeletonAlpha(): Float {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.72f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 850),
            repeatMode = RepeatMode.Reverse
        ),
        label = "skeletonAlpha"
    )
    return alpha
}
