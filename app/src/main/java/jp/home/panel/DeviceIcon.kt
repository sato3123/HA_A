package jp.home.panel

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 機器の種類がひと目で分かるアイコン（すべて同じ大きさの丸いバッジ。オンのときは色付き） */
@Composable
fun DeviceIcon(e: Entity, size: Dp = 40.dp) {
    val active = e.isOn && e.domain !in setOf("scene", "script") && !e.unavailable
    val primary = MaterialTheme.colorScheme.primary
    val bg = if (active) primary else primary.copy(alpha = 0.14f)
    val fg = if (active) MaterialTheme.colorScheme.onPrimary else primary
    Box(Modifier.size(size).background(bg, CircleShape), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size * 0.6f)) { drawDevice(e.domain, fg, active) }
    }
}

private fun DrawScope.drawDevice(domain: String, c: Color, on: Boolean) {
    val w = size.width
    val stroke = Stroke(width = w * 0.09f, cap = StrokeCap.Round)
    fun p(x: Float, y: Float) = Offset(x * w, y * w)
    when (domain) {
        "light" -> {
            // 電球：丸い球と口金。オンのときは光の線
            drawCircle(c, radius = w * 0.27f, center = p(0.5f, 0.42f))
            drawRect(c, topLeft = p(0.39f, 0.56f), size = Size(w * 0.22f, w * 0.16f))
            drawRoundRect(c, topLeft = p(0.40f, 0.76f), size = Size(w * 0.20f, w * 0.10f), cornerRadius = CornerRadius(w * 0.04f))
            if (on) {
                listOf(-60f, -30f, 0f, 30f, 60f).forEach { a ->
                    rotate(a, pivot = p(0.5f, 0.42f)) {
                        drawLine(c, p(0.5f, 0.02f), p(0.5f, 0.07f), strokeWidth = w * 0.06f, cap = StrokeCap.Round)
                    }
                }
            }
        }
        "switch" -> {
            // コンセント：角丸の四角と差し込み口
            drawRoundRect(c, topLeft = p(0.1f, 0.1f), size = Size(w * 0.8f, w * 0.8f), cornerRadius = CornerRadius(w * 0.2f), style = stroke)
            drawRoundRect(c, topLeft = p(0.34f, 0.32f), size = Size(w * 0.08f, w * 0.2f), cornerRadius = CornerRadius(w * 0.04f))
            drawRoundRect(c, topLeft = p(0.58f, 0.32f), size = Size(w * 0.08f, w * 0.2f), cornerRadius = CornerRadius(w * 0.04f))
            drawCircle(c, radius = w * 0.05f, center = p(0.5f, 0.68f))
        }
        "fan" -> {
            // 扇風機：3枚の羽根
            val center = p(0.5f, 0.5f)
            listOf(0f, 120f, 240f).forEach { a ->
                rotate(a, pivot = center) {
                    drawOval(c, topLeft = p(0.39f, 0.04f), size = Size(w * 0.22f, w * 0.42f))
                }
            }
            drawCircle(c, radius = w * 0.1f, center = center)
        }
        "cover" -> {
            // カーテン：レールと左右の布
            drawLine(c, p(0.06f, 0.1f), p(0.94f, 0.1f), strokeWidth = w * 0.08f, cap = StrokeCap.Round)
            val left = Path().apply {
                moveTo(0.12f * w, 0.16f * w); lineTo(0.42f * w, 0.16f * w)
                quadraticBezierTo(0.30f * w, 0.55f * w, 0.36f * w, 0.92f * w); lineTo(0.12f * w, 0.92f * w); close()
            }
            val right = Path().apply {
                moveTo(0.88f * w, 0.16f * w); lineTo(0.58f * w, 0.16f * w)
                quadraticBezierTo(0.70f * w, 0.55f * w, 0.64f * w, 0.92f * w); lineTo(0.88f * w, 0.92f * w); close()
            }
            drawPath(left, c)
            drawPath(right, c)
        }
        "climate" -> {
            // エアコン：本体と吹き出す風
            drawRoundRect(c, topLeft = p(0.06f, 0.14f), size = Size(w * 0.88f, w * 0.36f), cornerRadius = CornerRadius(w * 0.1f), style = stroke)
            drawLine(c, p(0.24f, 0.38f), p(0.76f, 0.38f), strokeWidth = w * 0.06f, cap = StrokeCap.Round)
            listOf(0.3f, 0.5f, 0.7f).forEach { x ->
                val wave = Path().apply {
                    moveTo(x * w, 0.6f * w)
                    quadraticBezierTo((x - 0.07f) * w, 0.72f * w, x * w, 0.8f * w)
                    quadraticBezierTo((x + 0.07f) * w, 0.88f * w, x * w, 0.96f * w)
                }
                drawPath(wave, c, style = Stroke(width = w * 0.07f, cap = StrokeCap.Round))
            }
        }
        "input_boolean" -> {
            // 切り替え：トグルスイッチ
            drawRoundRect(c, topLeft = p(0.04f, 0.28f), size = Size(w * 0.92f, w * 0.44f), cornerRadius = CornerRadius(w * 0.22f), style = stroke)
            drawCircle(c, radius = w * 0.14f, center = if (on) p(0.72f, 0.5f) else p(0.28f, 0.5f))
        }
        "scene" -> {
            // シーン：きらめき
            fun star(cx: Float, cy: Float, r: Float) = Path().apply {
                moveTo(cx * w, (cy - r) * w)
                quadraticBezierTo(cx * w, cy * w, (cx + r) * w, cy * w)
                quadraticBezierTo(cx * w, cy * w, cx * w, (cy + r) * w)
                quadraticBezierTo(cx * w, cy * w, (cx - r) * w, cy * w)
                quadraticBezierTo(cx * w, cy * w, cx * w, (cy - r) * w)
                close()
            }
            drawPath(star(0.42f, 0.56f, 0.4f), c)
            drawPath(star(0.8f, 0.2f, 0.18f), c)
        }
        "script" -> {
            // スクリプト：再生ボタン
            drawCircle(c, radius = w * 0.42f, center = p(0.5f, 0.5f), style = stroke)
            val tri = Path().apply {
                moveTo(0.42f * w, 0.32f * w); lineTo(0.7f * w, 0.5f * w); lineTo(0.42f * w, 0.68f * w); close()
            }
            drawPath(tri, c)
        }
        else -> drawCircle(c, radius = w * 0.2f, center = p(0.5f, 0.5f))
    }
}
