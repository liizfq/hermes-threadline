package com.hermes.android.media.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.max

@Composable
fun WaveformView(
    amplitudes: List<Float>,
    progress: Float = 0f,
    modifier: Modifier = Modifier,
    barColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    progressColor: Color = MaterialTheme.colorScheme.primary,
    barWidthDp: Float = 3f,
    barGapDp: Float = 2f
) {
    Canvas(modifier = modifier.height(32.dp)) {
        val barWidth = barWidthDp.dp.toPx()
        val barGap = barGapDp.dp.toPx()
        val maxBars = max(1, (size.width / (barWidth + barGap)).toInt())
        val display = if (amplitudes.size > maxBars) amplitudes.takeLast(maxBars) else amplitudes
        val progressIndex = (progress * display.size).toInt()

        display.forEachIndexed { index, amplitude ->
            val x = index * (barWidth + barGap)
            val barHeight = (amplitude * size.height).coerceAtLeast(2.dp.toPx())
            val y = (size.height - barHeight) / 2
            drawRoundRect(
                color = if (index <= progressIndex && progress > 0f) progressColor else barColor,
                topLeft = Offset(x, y),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2)
            )
        }
    }
}
