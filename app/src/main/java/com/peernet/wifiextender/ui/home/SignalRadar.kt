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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

data class RadarBlip(
    val angle: Float,
    val distance: Float,
    val strength: Float,
    val label: String = ""
)

@Composable
fun SignalRadar(
    signalStrength: Float,
    signalLabel: String,
    packetsPerSec: Long = 0,
    bytesPerSec: Long = 0,
    blips: List<RadarBlip> = emptyList(),
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition()
    val sweepAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        )
    )

    val animatedStrength by animateFloatAsState(
        targetValue = signalStrength.coerceIn(0f, 1f),
        animationSpec = tween(400, easing = LinearEasing),
        label = "strength"
    )

    val primaryColor = Color(0xFF1A73E8)
    val strongColor = Color(0xFF1E8E3E)
    val mediumColor = Color(0xFFF9AB00)
    val weakColor = Color(0xFFD93025)

    val ringColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.15f)

    val dotColor = when {
        animatedStrength > 0.7f -> strongColor
        animatedStrength > 0.4f -> mediumColor
        else -> weakColor
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Canvas(modifier = Modifier.size(220.dp)) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val radius = size.width / 2f - 6.dp.toPx()
            val center = Offset(cx, cy)

            // Grid rings (4 concentric circles)
            for (i in 1..4) {
                drawCircle(
                    color = ringColor,
                    radius = radius * i / 4f,
                    center = center,
                    style = Stroke(width = 0.8.dp.toPx())
                )
            }

            // Cross-hairs
            drawLine(gridColor, Offset(cx, cy - radius), Offset(cx, cy + radius), 0.8.dp.toPx())
            drawLine(gridColor, Offset(cx - radius, cy), Offset(cx + radius, cy), 0.8.dp.toPx())
            // Diagonal lines
            val diag = radius * 0.707f
            drawLine(gridColor, Offset(cx - diag, cy - diag), Offset(cx + diag, cy + diag), 0.5.dp.toPx())
            drawLine(gridColor, Offset(cx + diag, cy - diag), Offset(cx - diag, cy + diag), 0.5.dp.toPx())

            // Rotating sweep with gradient trail
            rotate(sweepAngle, center) {
                // Sweep trail (fading arc behind the line)
                for (i in 0..24) {
                    val trailAngle = -i * 3f
                    val alpha = (0.25f * (1f - i / 25f))
                    drawArc(
                        color = primaryColor.copy(alpha = alpha),
                        startAngle = trailAngle - 2f,
                        sweepAngle = 4f,
                        useCenter = true,
                        topLeft = Offset.Zero,
                        size = size,
                        style = Stroke(width = 1.dp.toPx())
                    )
                }

                // Solid sweep line
                drawLine(
                    color = primaryColor,
                    start = center,
                    end = Offset(cx + radius, cy),
                    strokeWidth = 2.dp.toPx()
                )

                // Bright tip at the sweep end
                drawCircle(
                    color = primaryColor.copy(alpha = 0.6f),
                    radius = 3.dp.toPx(),
                    center = Offset(cx + radius, cy)
                )
            }

            // Blips from actual traffic
            for (blip in blips) {
                val blipRadius = radius * blip.distance.coerceIn(0.15f, 0.95f)
                val bx = cx + blipRadius * cos(Math.toRadians(blip.angle.toDouble())).toFloat()
                val by = cy - blipRadius * sin(Math.toRadians(blip.angle.toDouble())).toFloat()
                val blipCenter = Offset(bx, by)

                val blipColor = when {
                    blip.strength > 0.7f -> strongColor
                    blip.strength > 0.4f -> mediumColor
                    else -> weakColor
                }

                // Outer glow
                drawCircle(
                    color = blipColor.copy(alpha = 0.12f),
                    radius = 12.dp.toPx(),
                    center = blipCenter
                )
                // Inner glow
                drawCircle(
                    color = blipColor.copy(alpha = 0.3f),
                    radius = 6.dp.toPx(),
                    center = blipCenter
                )
                // Core dot
                drawCircle(
                    color = blipColor,
                    radius = 2.5.dp.toPx(),
                    center = blipCenter
                )
            }

            // Center dot
            if (animatedStrength > 0f) {
                drawCircle(color = dotColor.copy(alpha = 0.15f), radius = 10.dp.toPx(), center = center)
            }
            drawCircle(color = dotColor, radius = 4.dp.toPx(), center = center)
        }

        Spacer(Modifier.height(6.dp))

        val throughputText = when {
            bytesPerSec > 1_048_576 -> "%.1f MB/s".format(bytesPerSec / 1_048_576.0)
            bytesPerSec > 1024 -> "%.0f KB/s".format(bytesPerSec / 1024.0)
            bytesPerSec > 0 -> "${bytesPerSec} B/s"
            packetsPerSec > 0 -> "${packetsPerSec} pkt/s"
            else -> "\u2014"
        }

        Text(
            throughputText,
            style = MaterialTheme.typography.titleMedium,
            color = dotColor,
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
