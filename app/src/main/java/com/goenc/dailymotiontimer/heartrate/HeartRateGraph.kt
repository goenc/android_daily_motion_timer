package com.goenc.dailymotiontimer.heartrate

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.goenc.dailymotiontimer.R

private const val GRAPH_MIN_BPM = 50
private const val GRAPH_MAX_BPM = 150
private const val GRAPH_WINDOW_MS = 10 * 60 * 1_000L

@Composable
fun HeartRateGraph(
    samples: List<HeartRateGraphSample>,
    modifier: Modifier = Modifier,
) {
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val lineColor = MaterialTheme.colorScheme.primary
    val latestHeartRate = samples.lastOrNull()?.heartRate
    val graphDescription = latestHeartRate?.let {
        stringResource(R.string.heart_rate_graph_description, it)
    } ?: stringResource(R.string.heart_rate_graph_waiting)

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                text = stringResource(R.string.heart_rate_graph_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.heart_rate_graph_period),
                style = MaterialTheme.typography.bodySmall,
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(132.dp)
                    .padding(top = 4.dp)
                    .semantics {
                        contentDescription = graphDescription
                    },
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    repeat(5) { index ->
                        val y = size.height * index / 4f
                        drawLine(gridColor, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                        val x = size.width * index / 4f
                        drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
                    }
                    if (samples.isEmpty()) return@Canvas

                    val latestTime = samples.last().timestampMs
                    val startTime = latestTime - GRAPH_WINDOW_MS
                    samples.zipWithNext().forEach { (from, to) ->
                        drawLine(
                            color = lineColor,
                            start = graphPoint(from, startTime, size.width, size.height),
                            end = graphPoint(to, startTime, size.width, size.height),
                            strokeWidth = 3.dp.toPx(),
                            cap = StrokeCap.Round,
                        )
                    }
                    drawCircle(
                        color = lineColor,
                        radius = 1.5.dp.toPx(),
                        center = graphPoint(samples.last(), startTime, size.width, size.height),
                    )
                }
                Text("$GRAPH_MAX_BPM", style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.TopStart))
                Text("$GRAPH_MIN_BPM", style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.BottomStart))
                if (samples.isEmpty()) {
                    Text(
                        text = stringResource(R.string.heart_rate_graph_waiting),
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.heart_rate_graph_oldest),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(R.string.heart_rate_graph_latest),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

private fun graphPoint(
    sample: HeartRateGraphSample,
    startTime: Long,
    width: Float,
    height: Float,
): Offset {
    val xRatio = ((sample.timestampMs - startTime).toFloat() / GRAPH_WINDOW_MS).coerceIn(0f, 1f)
    val yRatio = (sample.heartRate.coerceIn(GRAPH_MIN_BPM, GRAPH_MAX_BPM) - GRAPH_MIN_BPM).toFloat() /
        (GRAPH_MAX_BPM - GRAPH_MIN_BPM)
    return Offset(width * xRatio, height * (1f - yRatio))
}
