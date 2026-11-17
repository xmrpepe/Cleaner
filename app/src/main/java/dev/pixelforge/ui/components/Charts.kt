package dev.pixelforge.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.pixelforge.ui.theme.Pf
import dev.pixelforge.ui.theme.readout
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * 270° instrument gauge with tick marks. [fraction] in 0..1; null renders an "n/a" state.
 */
@Composable
fun ArcGauge(
    fraction: Float?,
    valueText: String,
    label: String,
    modifier: Modifier = Modifier,
    size: Dp = 132.dp,
    color: Color = Pf.colors.accent,
    sub: String? = null,
) {
    val c = Pf.colors
    val target = (fraction ?: 0f).coerceIn(0f, 1f)
    val animated by animateFloatAsState(target, animationSpec = tween(if (Pf.dims.animations) 600 else 0), label = "gauge")
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val stroke = this.size.minDimension * 0.075f
            val inset = stroke / 2 + 6.dp.toPx()
            val arcSize = Size(this.size.width - inset * 2, this.size.height - inset * 2)
            val topLeft = Offset(inset, inset)
            drawArc(c.grid, 135f, 270f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            if (fraction != null) {
                drawArc(
                    Brush.sweepGradient(listOf(color.copy(alpha = 0.55f), color, color), center = center),
                    135f, 270f * animated, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
            // Ticks
            val r = this.size.minDimension / 2
            for (i in 0..27) {
                val angle = Math.toRadians(135.0 + i * 10.0)
                val major = i % 9 == 0
                val outer = r - 1.dp.toPx()
                val inner = outer - (if (major) 5.dp.toPx() else 2.5.dp.toPx())
                val lit = fraction != null && i / 27f <= animated
                drawLine(
                    if (lit) color.copy(alpha = 0.8f) else c.hairline,
                    Offset(center.x + inner * cos(angle).toFloat(), center.y + inner * sin(angle).toFloat()),
                    Offset(center.x + outer * cos(angle).toFloat(), center.y + outer * sin(angle).toFloat()),
                    strokeWidth = if (major) 2.dp.toPx() else 1.dp.toPx(),
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(valueText, style = MaterialTheme.typography.readout.copy(fontSize = MaterialTheme.typography.readout.fontSize * (size.value / 132f).coerceIn(0.7f, 1.6f)), textAlign = TextAlign.Center)
            Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = c.muted)
            if (sub != null) Text(sub, style = MaterialTheme.typography.labelSmall, color = c.muted.copy(alpha = 0.8f))
        }
    }
}

/** Compact line with gradient fill. Values are auto-scaled unless min/max are given. */
@Composable
fun Sparkline(
    values: List<Float>,
    modifier: Modifier = Modifier,
    color: Color = Pf.colors.accent,
    minValue: Float? = null,
    maxValue: Float? = null,
    fill: Boolean = true,
    height: Dp = 44.dp,
) {
    val grid = Pf.colors.grid
    Canvas(modifier.fillMaxWidth().height(height)) {
        if (values.size < 2) {
            drawLine(grid, Offset(0f, this.size.height / 2), Offset(this.size.width, this.size.height / 2), 1.dp.toPx())
            return@Canvas
        }
        val lo = minValue ?: values.min()
        val hi = maxValue ?: values.max()
        val span = max(hi - lo, 1e-6f)
        val step = this.size.width / (values.size - 1)
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = i * step
            val y = this.size.height - ((v - lo) / span).coerceIn(0f, 1f) * (this.size.height - 2.dp.toPx()) - 1.dp.toPx()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        if (fill) {
            val area = Path().apply {
                addPath(path)
                lineTo(this@Canvas.size.width, this@Canvas.size.height)
                lineTo(0f, this@Canvas.size.height)
                close()
            }
            drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = 0.28f), Color.Transparent)))
        }
        drawPath(path, color, style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round))
    }
}

data class ChartSeries(val name: String, val values: List<Float>, val color: Color)

/** Multi-series line chart with horizontal grid and min/max labels. */
@Composable
fun LineChart(
    series: List<ChartSeries>,
    modifier: Modifier = Modifier,
    height: Dp = 160.dp,
    minValue: Float? = null,
    maxValue: Float? = null,
    yFormatter: (Float) -> String = { "%.0f".format(it) },
) {
    val c = Pf.colors
    val all = series.flatMap { it.values }
    val lo = minValue ?: (all.minOrNull() ?: 0f)
    val hi = maxValue ?: (all.maxOrNull() ?: 1f)
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            series.forEach { s ->
                Canvas(Modifier.size(8.dp)) { drawRect(s.color) }
                Spacer(Modifier.width(4.dp))
                Text(s.name, style = MaterialTheme.typography.labelSmall, color = c.muted, maxLines = 1)
                Spacer(Modifier.width(10.dp))
            }
        }
        Text(yFormatter(hi), style = MaterialTheme.typography.labelSmall, color = c.muted)
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val span = max(hi - lo, 1e-6f)
            for (i in 0..4) {
                val y = this.size.height * i / 4f
                drawLine(c.grid, Offset(0f, y), Offset(this.size.width, y), 1.dp.toPx())
            }
            series.forEach { s ->
                if (s.values.size < 2) return@forEach
                val step = this.size.width / (s.values.size - 1)
                val path = Path()
                s.values.forEachIndexed { i, v ->
                    val y = this.size.height - ((v - lo) / span).coerceIn(0f, 1f) * this.size.height
                    if (i == 0) path.moveTo(0f, y) else path.lineTo(i * step, y)
                }
                drawPath(path, s.color, style = Stroke(1.8.dp.toPx(), cap = StrokeCap.Round))
            }
        }
        Text(yFormatter(lo), style = MaterialTheme.typography.labelSmall, color = c.muted)
    }
}

/** Horizontal stacked bar, e.g. RAM composition or storage by type. */
@Composable
fun SegmentBar(segments: List<Pair<Float, Color>>, modifier: Modifier = Modifier, height: Dp = 10.dp) {
    val track = Pf.colors.grid
    Canvas(modifier.fillMaxWidth().height(height)) {
        drawRoundRect(track, cornerRadius = CornerRadius(this.size.height / 2))
        var x = 0f
        val total = segments.sumOf { it.first.toDouble() }.toFloat().coerceAtLeast(1f)
        segments.forEach { (f, color) ->
            val w = this.size.width * (f / total)
            if (w > 0.5f) {
                drawRoundRect(color, Offset(x, 0f), Size(w - 1.5f, this.size.height), CornerRadius(this.size.height / 2))
            }
            x += w
        }
    }
}

/** Vertical bars (e.g. per-core load). Null entries are drawn as hatched "n/a". */
@Composable
fun BarRow(values: List<Float?>, modifier: Modifier = Modifier, color: Color = Pf.colors.accent, height: Dp = 72.dp, labels: List<String>? = null) {
    val c = Pf.colors
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val n = values.size.coerceAtLeast(1)
            val gap = 6.dp.toPx()
            val w = (this.size.width - gap * (n - 1)) / n
            values.forEachIndexed { i, v ->
                val x = i * (w + gap)
                drawRoundRect(c.grid, Offset(x, 0f), Size(w, this.size.height), CornerRadius(4.dp.toPx()))
                if (v != null) {
                    val h = this.size.height * v.coerceIn(0f, 1f)
                    drawRoundRect(
                        Brush.verticalGradient(listOf(color, color.copy(alpha = 0.55f)), startY = this.size.height - h, endY = this.size.height),
                        Offset(x, this.size.height - h), Size(w, h), CornerRadius(4.dp.toPx()),
                    )
                }
            }
        }
        if (labels != null) {
            Row(Modifier.fillMaxWidth()) {
                labels.forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = c.muted, modifier = Modifier.weight(1f), textAlign = TextAlign.Center, maxLines = 1) }
            }
        }
    }
}
