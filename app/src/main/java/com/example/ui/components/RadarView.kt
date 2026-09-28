package com.example.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.example.data.model.NearbyUser
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun RadarView(
    users: List<NearbyUser>,
    onUserClick: (NearbyUser) -> Unit,
    modifier: Modifier = Modifier,
    isScanning: Boolean = true
) {
    val infiniteTransition = rememberInfiniteTransition(label = "radar_anim")

    // Sweep rotation angle
    val sweepAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "sweep_angle"
    )

    // Pulse wave radius
    val pulseProgress by infiniteTransition.animateFloat(
        initialValue = 0.1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulse_progress"
    )

    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.tertiary
    val surfaceColor = MaterialTheme.colorScheme.surfaceVariant
    val density = LocalDensity.current
    val touchThresholdPx = with(density) { 32.dp.toPx() }

    Box(
        modifier = modifier.size(260.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(users) {
                    detectTapGestures { tapOffset ->
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val maxRadius = size.width / 2f - with(density) { 16.dp.toPx() }

                        val clickedUser = users.firstOrNull { user ->
                            val distFraction = (user.estimatedDistanceMeters / 100f).coerceIn(0.15f, 0.95f)
                            val peerRadius = maxRadius * distFraction
                            val angleDeg = (user.userId.hashCode().toLong() and 0xFFFF) % 360
                            val angleRad = Math.toRadians(angleDeg.toDouble())
                            val peerX = center.x + peerRadius * cos(angleRad).toFloat()
                            val peerY = center.y + peerRadius * sin(angleRad).toFloat()
                            val dx = tapOffset.x - peerX
                            val dy = tapOffset.y - peerY
                            (dx * dx + dy * dy) <= (touchThresholdPx * touchThresholdPx)
                        }

                        if (clickedUser != null) {
                            onUserClick(clickedUser)
                        }
                    }
                }
        ) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val maxRadius = size.width / 2f - 16.dp.toPx()

            // Outer background circle
            drawCircle(
                color = surfaceColor.copy(alpha = 0.35f),
                radius = maxRadius,
                center = center
            )

            // Pulse wave
            if (isScanning) {
                drawCircle(
                    color = primaryColor.copy(alpha = (1f - pulseProgress) * 0.4f),
                    radius = maxRadius * pulseProgress,
                    center = center,
                    style = Stroke(width = 2.dp.toPx())
                )
            }

            // Concentric rings (25m, 50m, 75m, 100m)
            val ringFractions = listOf(0.25f, 0.5f, 0.75f, 1.0f)
            ringFractions.forEach { fraction ->
                drawCircle(
                    color = primaryColor.copy(alpha = 0.25f),
                    radius = maxRadius * fraction,
                    center = center,
                    style = Stroke(width = 1.dp.toPx())
                )
            }

            // Cross-hairs
            drawLine(
                color = primaryColor.copy(alpha = 0.2f),
                start = Offset(center.x - maxRadius, center.y),
                end = Offset(center.x + maxRadius, center.y),
                strokeWidth = 1.dp.toPx()
            )
            drawLine(
                color = primaryColor.copy(alpha = 0.2f),
                start = Offset(center.x, center.y - maxRadius),
                end = Offset(center.x, center.y + maxRadius),
                strokeWidth = 1.dp.toPx()
            )

            // Scanning sweep cone
            if (isScanning) {
                val rad = Math.toRadians(sweepAngle.toDouble())
                val sweepX = center.x + maxRadius * cos(rad).toFloat()
                val sweepY = center.y + maxRadius * sin(rad).toFloat()

                drawLine(
                    brush = Brush.linearGradient(
                        colors = listOf(primaryColor.copy(alpha = 0.1f), primaryColor.copy(alpha = 0.9f)),
                        start = center,
                        end = Offset(sweepX, sweepY)
                    ),
                    start = center,
                    end = Offset(sweepX, sweepY),
                    strokeWidth = 2.dp.toPx()
                )
            }

            // Center beacon (Me)
            drawCircle(
                color = primaryColor,
                radius = 7.dp.toPx(),
                center = center
            )
            drawCircle(
                color = Color.White,
                radius = 3.dp.toPx(),
                center = center
            )

            // Render discovered peer blips based on hash & estimated distance
            users.forEach { user ->
                val distFraction = (user.estimatedDistanceMeters / 100f).coerceIn(0.15f, 0.95f)
                val peerRadius = maxRadius * distFraction
                // Stable deterministic angle based on userId
                val angleDeg = (user.userId.hashCode().toLong() and 0xFFFF) % 360
                val angleRad = Math.toRadians(angleDeg.toDouble())

                val peerX = center.x + peerRadius * cos(angleRad).toFloat()
                val peerY = center.y + peerRadius * sin(angleRad).toFloat()
                val peerOffset = Offset(peerX, peerY)

                // Glow ring
                drawCircle(
                    color = secondaryColor.copy(alpha = 0.4f),
                    radius = 10.dp.toPx(),
                    center = peerOffset
                )
                // Core blip
                drawCircle(
                    color = secondaryColor,
                    radius = 6.dp.toPx(),
                    center = peerOffset
                )
                drawCircle(
                    color = Color.White,
                    radius = 2.5.dp.toPx(),
                    center = peerOffset
                )
            }
        }
    }
}
