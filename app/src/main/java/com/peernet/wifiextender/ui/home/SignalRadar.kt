package com.peernet.wifiextender.ui.home

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SignalRadar(
    signalStrength: Float,
    signalLabel: String,
    packetsPerSec: Long = 0,
    bytesPerSec: Long = 0,
    modifier: Modifier = Modifier
) {
    val animatedStrength by animateFloatAsState(
        targetValue = signalStrength.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 300, easing = LinearEasing),
        label = "signal"
    )

    val ringColor = when {
        animatedStrength > 0.7f -> Color(0xFF1E8E3E)
        animatedStrength > 0.4f -> Color(0xFFF9AB00)
        else -> Color(0xFFD93025)
    }

    val trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Canvas(modifier = Modifier.size(180.dp)) {
            val strokeWidth = 10.dp.toPx()
            val padding = strokeWidth / 2 + 4.dp.toPx()
            val arcSize = Size(size.width - strokeWidth - 8.dp.toPx(), size.height - strokeWidth - 8.dp.toPx())
            val topLeft = Offset(padding, padding)

            // Background track
            drawArc(
                color = trackColor,
                startAngle = 135f,
                sweepAngle = 270f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )

            // Filled arc based on signal strength
            drawArc(
                color = ringColor,
                startAngle = 135f,
                sweepAngle = 270f * animatedStrength,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )

            // Center dot pulsing when active
            val center = Offset(size.width / 2f, size.height / 2f)
            if (animatedStrength > 0f) {
                val pulseRadius = 6.dp.toPx() + animatedStrength * 4.dp.toPx()
                drawCircle(color = ringColor.copy(alpha = 0.15f), radius = pulseRadius + 8.dp.toPx(), center = center)
                drawCircle(color = ringColor, radius = pulseRadius, center = center)
            } else {
                drawCircle(color = trackColor, radius = 6.dp.toPx(), center = center)
            }
        }

        Spacer(Modifier.height(4.dp))

        // Throughput display
        val throughputText = when {
            bytesPerSec > 1_048_576 -> "%.1f MB/s".format(bytesPerSec / 1_048_576.0)
            bytesPerSec > 1024 -> "%.0f KB/s".format(bytesPerSec / 1024.0)
            bytesPerSec > 0 -> "${bytesPerSec} B/s"
            packetsPerSec > 0 -> "${packetsPerSec} pkt/s"
            else -> "No traffic"
        }
        Text(
            throughputText,
            style = MaterialTheme.typography.titleMedium,
            color = ringColor,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp
        )

        Spacer(Modifier.height(2.dp))

        Text(
            signalLabel,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium
        )
    }
}
