package com.camlight.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.camlight.app.model.Corner
import com.camlight.app.model.Side
import com.camlight.app.model.StripConfig
import kotlin.math.hypot

private val StartGreen = Color(0xFF00C853)

/**
 * A top-down TV with its four LED edges. Edges with LEDs are drawn in their color, the current edge is
 * thicker, and an arrow shows the direction the strip travels.
 */
@Composable
fun TvDiagram(
    config: StripConfig,
    currentSide: Side?,
    modifier: Modifier = Modifier,
    onCornerTap: ((Corner) -> Unit)? = null,
    onSideTap: ((Side) -> Unit)? = null,
) {
    val body = MaterialTheme.colorScheme.surfaceVariant
    val unused = MaterialTheme.colorScheme.outlineVariant
    val arrowColor = MaterialTheme.colorScheme.onSurface
    val cornerTap by rememberUpdatedState(onCornerTap)
    val sideTap by rememberUpdatedState(onSideTap)

    Canvas(
        modifier
            .fillMaxWidth()
            .aspectRatio(1.9f)
            .pointerInput(Unit) {
                detectTapGestures { p ->
                    val pad = 28.dp.toPx()
                    val pts = corners(size.width.toFloat(), size.height.toFloat(), pad)
                    cornerTap?.let { cb ->
                        cb(Corner.entries.minBy { (pts[it.ordinal] - p).getDistance() })
                    } ?: sideTap?.let { cb ->
                        cb(Side.entries.minBy { distToEdge(p, it, pts) })
                    }
                }
            },
    ) {
        val pad = 28.dp.toPx()
        val pts = corners(size.width, size.height, pad)
        drawRoundRect(
            body,
            topLeft = pts[0] + Offset(8.dp.toPx(), 8.dp.toPx()),
            size = Size(pts[1].x - pts[0].x - 16.dp.toPx(), pts[3].y - pts[0].y - 16.dp.toPx()),
            cornerRadius = CornerRadius(8.dp.toPx()),
        )

        Side.entries.forEach { side ->
            val (a, b) = edge(side, pts)
            val used = config.segmentFor(side).count > 0
            val current = side == currentSide
            drawLine(
                color = if (used) Color(0xFF000000 or side.rgb.toLong()) else unused,
                start = a,
                end = b,
                strokeWidth = (if (current) 16.dp else 9.dp).toPx(),
                cap = StrokeCap.Round,
                alpha = if (currentSide == null || current) 1f else 0.55f,
            )
        }

        // Direction arrow on the current edge (or the first edge while choosing the start).
        val arrowSide = currentSide ?: config.order.first()
        val (a, b) = edge(arrowSide, pts)
        var dir = (b - a) / (b - a).getDistance()
        if (!config.clockwise) dir = -dir
        val mid = (a + b) / 2f
        val s = 12.dp.toPx()
        val perp = Offset(-dir.y, dir.x)
        val arrow = Path().apply {
            moveTo((mid + dir * s).x, (mid + dir * s).y)
            lineTo((mid - dir * s + perp * s * 0.9f).x, (mid - dir * s + perp * s * 0.9f).y)
            lineTo((mid - dir * s - perp * s * 0.9f).x, (mid - dir * s - perp * s * 0.9f).y)
            close()
        }
        drawPath(arrow, arrowColor, style = Fill)

        // Corner dots; the start corner is large and green.
        Corner.entries.forEach { c ->
            val isStart = c == config.startCorner
            drawCircle(
                if (isStart) StartGreen else arrowColor.copy(alpha = 0.35f),
                radius = (if (isStart) 13.dp else 6.dp).toPx(),
                center = pts[c.ordinal],
            )
        }
    }
}

private fun corners(w: Float, h: Float, pad: Float) = listOf(
    Offset(pad, pad), Offset(w - pad, pad), Offset(w - pad, h - pad), Offset(pad, h - pad),
) // TL, TR, BR, BL matches Corner order

private fun edge(side: Side, p: List<Offset>): Pair<Offset, Offset> = when (side) {
    Side.TOP -> p[0] to p[1]
    Side.RIGHT -> p[1] to p[2]
    Side.BOTTOM -> p[2] to p[3]
    Side.LEFT -> p[3] to p[0]
}

private fun distToEdge(pt: Offset, side: Side, p: List<Offset>): Float {
    val (a, b) = edge(side, p)
    val ab = b - a
    val t = (((pt.x - a.x) * ab.x + (pt.y - a.y) * ab.y) / (ab.x * ab.x + ab.y * ab.y)).coerceIn(0f, 1f)
    val proj = a + ab * t
    return hypot(pt.x - proj.x, pt.y - proj.y)
}
