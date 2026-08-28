package com.example.phonemic.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun AudioMeter(
    amplitude: Float,
    waveform: FloatArray,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF1E293B))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "AUDIO INPUT LEVEL",
                style = MaterialTheme.typography.labelMedium,
                color = Color(0xFF94A3B8),
                fontSize = 11.sp
            )
            Text(
                text = "${(amplitude * 100).toInt()}%",
                style = MaterialTheme.typography.labelLarge,
                color = if (amplitude > 0.85f) Color(0xFFEF4444) else Color(0xFF38BDF8)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Live Waveform Canvas
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
        ) {
            val width = size.width
            val height = size.height
            val barCount = waveform.size
            val gap = 6.dp.toPx()
            val totalGaps = (barCount - 1) * gap
            val barWidth = (width - totalGaps) / barCount

            for (i in waveform.indices) {
                val barHeight = (waveform[i] * height).coerceAtLeast(4.dp.toPx())
                val x = i * (barWidth + gap)
                val y = (height - barHeight) / 2f

                val barColor = when {
                    waveform[i] > 0.8f -> Color(0xFFEF4444) // Red overload
                    waveform[i] > 0.5f -> Color(0xFFF59E0B) // Amber
                    else -> Color(0xFF38BDF8)               // Sky Cyan
                }

                drawRoundRect(
                    color = barColor,
                    topLeft = Offset(x, y),
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Horizontal Volume Level Bar
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
        ) {
            // Background track
            drawRoundRect(
                color = Color(0xFF0F172A),
                size = size,
                cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
            )

            // Active fill
            val activeWidth = size.width * amplitude.coerceIn(0f, 1f)
            val fillColor = when {
                amplitude > 0.85f -> Color(0xFFEF4444)
                amplitude > 0.6f -> Color(0xFFF59E0B)
                else -> Color(0xFF10B981)
            }

            drawRoundRect(
                color = fillColor,
                size = Size(activeWidth, size.height),
                cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
            )
        }
    }
}
