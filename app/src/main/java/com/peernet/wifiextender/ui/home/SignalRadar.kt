package com.peernet.wifiextender.ui.home

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun SignalRadar(
    signalStrength: Float,
    signalLabel: String,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition()
    val sweepAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        )
    )

    val primaryColor = MaterialTheme.colorScheme.primary
    val ringColor = Color.LightGray.copy(alpha = 0.4f)

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Canvas(modifier = Modifier.size(150.dp)) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = size.width / 2f - 4.dp.toPx()

            for (i in 1..4) {
                drawCircle(
                    color = ringColor,
                    radius = radius * i / 4f,
                    center = center,
                    style = Stroke(width = 1.dp.toPx())
                )
            }

            drawLine(
                ringColor,
                Offset(center.x, center.y - radius),
                Offset(center.x, center.y + radius),
                1.dp.toPx()
            )
            drawLine(
                ringColor,
                Offset(center.x - radius, center.y),
                Offset(center.x + radius, center.y),
                1.dp.toPx()
            )

            rotate(sweepAngle, center) {
                drawArc(
                    color = primaryColor.copy(alpha = 0.15f),
                    startAngle = -30f,
                    sweepAngle = 30f,
                    useCenter = true,
                    topLeft = Offset.Zero,
                    size = size
                )
                drawLine(
                    color = primaryColor,
                    start = center,
                    end = Offset(center.x + radius, center.y),
                    strokeWidth = 2.dp.toPx()
                )
            }

            if (signalStrength > 0f) {
                val blipDistance = radius * (0.3f + 0.7f * (1f - signalStrength.coerceIn(0f, 1f)))
                val blipAngleRad = Math.toRadians(45.0)
                val blipPos = Offset(
                    center.x + blipDistance * cos(blipAngleRad).toFloat(),
                    center.y - blipDistance * sin(blipAngleRad).toFloat()
                )
                val blipColor = when {
                    signalStrength > 0.7f -> Color(0xFF1E8E3E)
                    signalStrength > 0.4f -> Color(0xFFF9AB00)
                    else -> Color(0xFFD93025)
                }
                drawCircle(color = blipColor.copy(alpha = 0.25f), radius = 14.dp.toPx(), center = blipPos)
                drawCircle(color = blipColor, radius = 5.dp.toPx(), center = blipPos)
            }
        }

        Spacer(Modifier.height(6.dp))
        Text(
            signalLabel,
            style = MaterialTheme.typography.bodySmall,
            color = when {
                signalStrength > 0.7f -> Color(0xFF1E8E3E)
                signalStrength > 0.4f -> Color(0xFFF9AB00)
                else -> Color(0xFF5F6368)
            },
            fontWeight = FontWeight.Medium
        )
    }
}
