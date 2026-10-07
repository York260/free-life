package com.freelife.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class Glyph { CALENDAR, MIC, SLIDERS, SUN, CHECKLIST, REPEAT, SPEAKER, WARNING, CHECK }

/** 自己畫的線條圖示(圓角、統一線寬),不依賴任何圖示庫。 */
@Composable
fun AppIcon(glyph: Glyph, color: Color, size: Dp = 22.dp, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(size)) {
        val u = this.size.minDimension / 24f
        val st = Stroke(width = 1.9f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun p(x: Float, y: Float) = Offset(x * u, y * u)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(color, p(x1, y1), p(x2, y2), strokeWidth = st.width, cap = StrokeCap.Round)
        when (glyph) {
            Glyph.CALENDAR -> {
                drawRoundRect(color, p(3.5f, 5f), Size(17f * u, 15.5f * u), CornerRadius(3f * u), style = st)
                line(3.5f, 10f, 20.5f, 10f)
                line(8f, 3f, 8f, 6.5f)
                line(16f, 3f, 16f, 6.5f)
                drawCircle(color, 1.3f * u, p(8.5f, 14.5f))
                drawCircle(color, 1.3f * u, p(12f, 14.5f))
                drawCircle(color, 1.3f * u, p(15.5f, 14.5f))
            }

            Glyph.MIC -> {
                drawRoundRect(color, p(9f, 3f), Size(6f * u, 11f * u), CornerRadius(3f * u), style = st)
                val arc = Path().apply {
                    moveTo(5.5f * u, 11f * u)
                    cubicTo(5.5f * u, 17.5f * u, 18.5f * u, 17.5f * u, 18.5f * u, 11f * u)
                }
                drawPath(arc, color, style = st)
                line(12f, 16.5f, 12f, 20.5f)
                line(8.5f, 20.5f, 15.5f, 20.5f)
            }

            Glyph.SLIDERS -> {
                line(4f, 7f, 20f, 7f)
                line(4f, 17f, 20f, 17f)
                drawCircle(color, 2.6f * u, p(9f, 7f), style = st)
                drawCircle(color, 2.6f * u, p(15f, 17f), style = st)
            }

            Glyph.SUN -> {
                drawCircle(color, 4f * u, p(12f, 12f), style = st)
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    val c = Math.cos(a).toFloat()
                    val s = Math.sin(a).toFloat()
                    line(12f + c * 7.2f, 12f + s * 7.2f, 12f + c * 9.4f, 12f + s * 9.4f)
                }
            }

            Glyph.CHECKLIST -> {
                drawRoundRect(color, p(4f, 3.5f), Size(16f * u, 17f * u), CornerRadius(3f * u), style = st)
                val tick = Path().apply {
                    moveTo(8f * u, 9.5f * u); lineTo(9.6f * u, 11.1f * u); lineTo(12.2f * u, 8f * u)
                }
                drawPath(tick, color, style = st)
                line(14f, 10f, 16.5f, 10f)
                val tick2 = Path().apply {
                    moveTo(8f * u, 15.5f * u); lineTo(9.6f * u, 17.1f * u); lineTo(12.2f * u, 14f * u)
                }
                drawPath(tick2, color, style = st)
                line(14f, 16f, 16.5f, 16f)
            }

            Glyph.REPEAT -> {
                val a = Path().apply {
                    moveTo(5f * u, 11f * u); lineTo(5f * u, 9.5f * u)
                    cubicTo(5f * u, 7.5f * u, 6.5f * u, 6.5f * u, 8.5f * u, 6.5f * u)
                    lineTo(18f * u, 6.5f * u)
                    moveTo(15f * u, 3.5f * u); lineTo(18f * u, 6.5f * u); lineTo(15f * u, 9.5f * u)
                }
                drawPath(a, color, style = st)
                val b = Path().apply {
                    moveTo(19f * u, 13f * u); lineTo(19f * u, 14.5f * u)
                    cubicTo(19f * u, 16.5f * u, 17.5f * u, 17.5f * u, 15.5f * u, 17.5f * u)
                    lineTo(6f * u, 17.5f * u)
                    moveTo(9f * u, 14.5f * u); lineTo(6f * u, 17.5f * u); lineTo(9f * u, 20.5f * u)
                }
                drawPath(b, color, style = st)
            }

            Glyph.SPEAKER -> {
                val body = Path().apply {
                    moveTo(4f * u, 9.5f * u); lineTo(8f * u, 9.5f * u); lineTo(12.5f * u, 5.5f * u)
                    lineTo(12.5f * u, 18.5f * u); lineTo(8f * u, 14.5f * u); lineTo(4f * u, 14.5f * u); close()
                }
                drawPath(body, color, style = st)
                val w1 = Path().apply {
                    moveTo(15.5f * u, 9f * u); cubicTo(17f * u, 10.8f * u, 17f * u, 13.2f * u, 15.5f * u, 15f * u)
                }
                drawPath(w1, color, style = st)
                val w2 = Path().apply {
                    moveTo(18f * u, 6.5f * u); cubicTo(21f * u, 9.5f * u, 21f * u, 14.5f * u, 18f * u, 17.5f * u)
                }
                drawPath(w2, color, style = st)
            }

            Glyph.WARNING -> {
                val t = Path().apply {
                    moveTo(12f * u, 4f * u); lineTo(21f * u, 19.5f * u); lineTo(3f * u, 19.5f * u); close()
                }
                drawPath(t, color, style = st)
                line(12f, 10f, 12f, 14f)
                drawCircle(color, 1.1f * u, p(12f, 16.8f))
            }

            Glyph.CHECK -> {
                val c = Path().apply {
                    moveTo(5f * u, 12.5f * u); lineTo(10f * u, 17.5f * u); lineTo(19f * u, 7f * u)
                }
                drawPath(c, color, style = st)
            }
        }
    }
}
