package dev.pixelforge.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pixelforge.core.util.Format
import dev.pixelforge.ui.theme.Pf

data class TreeNode(val key: String, val label: String, val value: Long)

/** Squarified treemap layout (Bruls et al.). Returns rects in the same order as [values]. */
fun squarify(values: List<Double>, bounds: Rect): List<Rect> {
    val total = values.sum()
    if (total <= 0 || values.isEmpty()) return values.map { Rect.Zero }
    val scale = bounds.width * bounds.height / total
    val areas = values.map { it * scale }
    val out = MutableList(values.size) { Rect.Zero }
    var rect = bounds
    var i = 0
    while (i < areas.size) {
        val short = minOf(rect.width, rect.height).toDouble()
        val row = mutableListOf(i)
        var j = i + 1
        fun worst(r: List<Int>): Double {
            val s = r.sumOf { areas[it] }
            val mx = r.maxOf { areas[it] }
            val mn = r.minOf { areas[it] }
            return maxOf(short * short * mx / (s * s), (s * s) / (short * short * mn))
        }
        while (j < areas.size && worst(row + j) <= worst(row)) { row += j; j++ }
        val rowArea = row.sumOf { areas[it] }
        if (rect.width >= rect.height) {
            val w = (rowArea / rect.height).toFloat()
            var y = rect.top
            row.forEach { k -> val h = (areas[k] / w).toFloat(); out[k] = Rect(rect.left, y, rect.left + w, y + h); y += h }
            rect = Rect(rect.left + w, rect.top, rect.right, rect.bottom)
        } else {
            val h = (rowArea / rect.width).toFloat()
            var x = rect.left
            row.forEach { k -> val w = (areas[k] / h).toFloat(); out[k] = Rect(x, rect.top, x + w, rect.top + h); x += w }
            rect = Rect(rect.left, rect.top + h, rect.right, rect.bottom)
        }
        i = j
    }
    return out
}

@Composable
fun Treemap(nodes: List<TreeNode>, modifier: Modifier = Modifier, height: Dp = 260.dp, onClick: (TreeNode) -> Unit = {}) {
    val c = Pf.colors
    val palette = remember(c) {
        listOf(c.accent, c.cyan, Color(0xFFA78BFA), c.success, c.warning, Color(0xFF4C8DFF), Color(0xFFF472B6), Color(0xFF94A3B8))
    }
    val measurer = rememberTextMeasurer()
    val sorted = remember(nodes) { nodes.filter { it.value > 0 }.sortedByDescending { it.value }.take(30) }
    var rects: List<Rect> = emptyList()
    Canvas(
        modifier.fillMaxWidth().height(height).pointerInput(sorted) {
            detectTapGestures { p -> rects.indexOfFirst { it.contains(p) }.takeIf { it >= 0 }?.let { onClick(sorted[it]) } }
        },
    ) {
        rects = squarify(sorted.map { it.value.toDouble() }, Rect(Offset.Zero, this.size))
        sorted.forEachIndexed { i, n ->
            val r = rects[i]
            val color = palette[i % palette.size]
            val inset = 1.5.dp.toPx()
            val rr = Rect(r.left + inset, r.top + inset, r.right - inset, r.bottom - inset)
            if (rr.width <= 0 || rr.height <= 0) return@forEachIndexed
            drawRoundRect(color.copy(alpha = 0.22f), rr.topLeft, rr.size, CornerRadius(6.dp.toPx()))
            drawRoundRect(color.copy(alpha = 0.7f), rr.topLeft, rr.size, CornerRadius(6.dp.toPx()), style = Stroke(1.dp.toPx()))
            if (rr.width > 56.dp.toPx() && rr.height > 30.dp.toPx()) {
                val maxW = (rr.width - 12.dp.toPx()).toInt().coerceAtLeast(1)
                val title = measurer.measure(n.label, TextStyle(color = Color.White.copy(alpha = if (c.isDark) 0.92f else 0.85f).let { if (c.isDark) it else Color.Black.copy(alpha = 0.85f) }, fontSize = 12.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, constraints = androidx.compose.ui.unit.Constraints(maxWidth = maxW))
                drawText(title, topLeft = Offset(rr.left + 6.dp.toPx(), rr.top + 5.dp.toPx()))
                val size = measurer.measure(Format.bytes(n.value), TextStyle(color = color, fontSize = 11.sp), maxLines = 1, constraints = androidx.compose.ui.unit.Constraints(maxWidth = maxW))
                if (rr.height > 44.dp.toPx()) drawText(size, topLeft = Offset(rr.left + 6.dp.toPx(), rr.top + 21.dp.toPx()))
            }
        }
        if (sorted.isEmpty()) drawRect(c.grid, size = Size(this.size.width, this.size.height))
    }
}
