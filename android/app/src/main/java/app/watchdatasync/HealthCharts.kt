package app.watchdatasync

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.watchdatasync.model.SleepStageSample
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TrendChartCard(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String,
    points: List<Pair<Long, Float>>,
    lineColor: Color,
    unit: String,
    emptyMessage: String,
) {
    Card(modifier = modifier) {
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (points.size < 2) {
                Text(
                    emptyMessage,
                    modifier = Modifier.padding(vertical = 18.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                MiniLineChart(points.takeLast(48), lineColor, unit)
            }
        }
    }
}

@Composable
private fun MiniLineChart(
    points: List<Pair<Long, Float>>,
    lineColor: Color,
    unit: String,
) {
    val minRaw = points.minOf { it.second }
    val maxRaw = points.maxOf { it.second }
    val range = (maxRaw - minRaw).takeIf { it > 1f } ?: 1f
    val minY = minRaw - range * 0.12f
    val maxY = maxRaw + range * 0.12f

    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                String.format(Locale.US, "%.0f %s", maxRaw, unit),
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                String.format(Locale.US, "%.0f %s", minRaw, unit),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp),
        ) {
            val stepX = size.width / (points.size - 1).coerceAtLeast(1)
            repeat(4) { index ->
                val y = size.height * index / 3f
                drawLine(
                    color = lineColor.copy(alpha = 0.10f),
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1f,
                )
            }

            val path = Path()
            points.forEachIndexed { index, entry ->
                val x = index * stepX
                val normalized = ((entry.second - minY) / (maxY - minY)).coerceIn(0f, 1f)
                val y = size.height - normalized * size.height
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(
                path,
                lineColor,
                style = Stroke(width = 4f, cap = StrokeCap.Round),
            )

            points.forEachIndexed { index, entry ->
                val x = index * stepX
                val normalized = ((entry.second - minY) / (maxY - minY)).coerceIn(0f, 1f)
                val y = size.height - normalized * size.height
                drawCircle(
                    color = lineColor,
                    radius = if (index == points.lastIndex) 5f else 2.5f,
                    center = Offset(x, y),
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                formatChartTime(points.first().first),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                formatChartTime(points.last().first),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun SleepTrendCard(history: List<SleepStageSample>) {
    Card {
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Sleep stages", style = MaterialTheme.typography.titleMedium)
            Text(
                "Charted only from verified sleep-stage records.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (history.isEmpty()) {
                Text(
                    "No 0x32/CB sleep stages have been returned yet.",
                    modifier = Modifier.padding(vertical = 16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val grouped = history
                    .takeLast(28)
                    .groupBy { SimpleDateFormat("dd MMM", Locale.US).format(Date(it.epochMillis)) }
                    .toList()
                    .takeLast(7)

                grouped.forEach { (day, samples) ->
                    val totals = (1..4).associateWith { stage ->
                        samples.filter { it.stage == stage }.sumOf { it.durationMinutes }
                    }
                    val total = totals.values.sum().coerceAtLeast(1)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            day,
                            modifier = Modifier.width(58.dp),
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .height(22.dp),
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            listOf(
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.colorScheme.secondary,
                                MaterialTheme.colorScheme.tertiary,
                                MaterialTheme.colorScheme.outline,
                            ).forEachIndexed { index, color ->
                                val minutes = totals[index + 1] ?: 0
                                if (minutes > 0) {
                                    Box(
                                        Modifier
                                            .weight(minutes.toFloat())
                                            .height(22.dp)
                                            .background(color, MaterialTheme.shapes.small),
                                    )
                                }
                            }
                        }
                        Text(
                            formatSleepMinutes(total),
                            modifier = Modifier.width(58.dp),
                            textAlign = TextAlign.End,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }
}

private fun formatChartTime(epochMillis: Long): String =
    SimpleDateFormat("dd MMM HH:mm", Locale.US).format(Date(epochMillis))

private fun formatSleepMinutes(minutes: Int): String {
    val hours = minutes / 60
    val mins = minutes % 60
    return if (hours > 0) hours.toString() + "h " + mins + "m" else mins.toString() + "m"
}
