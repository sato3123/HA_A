@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package jp.home.panel

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.time.LocalDate

/** 1時間ぶんの記録（Wh） */
data class EnergyHour(
    val key: String, // "2026-10-27T14"
    val use: Double, val gen: Double, val buy: Double, val sell: Double,
    val circuits: Map<String, Double>,
) {
    val date: String get() = key.take(10)
    val hour: Int get() = key.takeLast(2).toIntOrNull() ?: 0
}

private val USE = Color(0xFFFFB74D)
private val GEN = Color(0xFF66BB6A)
private val BUY = Color(0xFFEF5350)
private val SELL = Color(0xFF42A5F5)

private fun kwhOf(wh: Double) = if (wh >= 100_000) String.format("%.0f kWh", wh / 1000) else String.format("%.1f kWh", wh / 1000)

/** 電気の分析（時間別・回路別）。データはホームパネルのサーバーが AiSEG2 から1分ごとに記録したもの */
@Composable
fun EnergyAnalysisDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    var period by remember { mutableStateOf("today") }
    var tab by remember { mutableStateOf("hour") }
    var circuit by remember { mutableStateOf<String?>(null) }
    val data by vm.energyHours.collectAsState()
    val status by vm.energyHoursStatus.collectAsState()
    LaunchedEffect(Unit) { vm.loadEnergyHours(31) }
    val chip = FilterChipDefaults.filterChipColors(
        selectedContainerColor = MaterialTheme.colorScheme.primary,
        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
    )
    val today = LocalDate.now()
    val dayList: List<String> = when (period) {
        "today" -> listOf(today.toString())
        "yesterday" -> listOf(today.minusDays(1).toString())
        "7" -> (0..6).map { today.minusDays(it.toLong()).toString() }
        else -> (0..29).map { today.minusDays(it.toLong()).toString() }
    }
    val hours = (data ?: emptyList()).filter { it.date in dayList }
    val oneDay = dayList.size == 1

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss) { Text("← 戻る") }
                    Text("📊 電気の使い方の分析", style = MaterialTheme.typography.titleLarge)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("today" to "今日", "yesterday" to "昨日", "7" to "7日間", "30" to "30日間").forEach { (k, l) ->
                        FilterChip(selected = period == k, onClick = { period = k; circuit = null }, label = { Text(l) }, colors = chip)
                    }
                    Text("　")
                    listOf("hour" to "時間別", "circuit" to "回路別").forEach { (k, l) ->
                        FilterChip(selected = tab == k, onClick = { tab = k }, label = { Text(l) }, colors = chip)
                    }
                }
                status?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                if (data != null && hours.isEmpty()) Text("この期間の記録はまだありません（記録は、サーバーが AiSEG2 から読み始めた時点からたまります）。", style = MaterialTheme.typography.bodyMedium)

                if (hours.isNotEmpty()) {
                    // 合計
                    val sum = { f: (EnergyHour) -> Double -> hours.sumOf(f) }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Total("使った電気", kwhOf(sum { it.use }), USE)
                        Total("発電", kwhOf(sum { it.gen }), GEN)
                        Total("買った電気", kwhOf(sum { it.buy }), BUY)
                        Total("売った電気", kwhOf(sum { it.sell }), SELL)
                        val use = sum { it.use }
                        if (use > 0) Total("自給率", String.format("%.0f%%", ((1 - sum { it.buy } / use) * 100).coerceIn(0.0, 100.0)), GEN)
                    }

                    if (tab == "hour") {
                        // 時間別（1日なら24時間、複数日なら日ごと）
                        val slots: List<Pair<String, List<EnergyHour>>> = if (oneDay)
                            (0..23).map { h -> "${h}時" to hours.filter { it.hour == h } }
                        else dayList.reversed().map { d -> d.substring(5).replace('-', '/') to hours.filter { it.date == d } }
                        Text(if (oneDay) "時間ごとの電気" else "日ごとの電気", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        Legend()
                        FlowChart(slots.map { (l, list) -> Slot(l, list.sumOf { it.use }, list.sumOf { it.gen }, list.sumOf { it.buy }, list.sumOf { it.sell }) })
                        val peak = slots.maxByOrNull { it.second.sumOf { h -> h.use } }
                        peak?.takeIf { it.second.isNotEmpty() }?.let {
                            Text("いちばん多く使ったのは ${it.first}（${kwhOf(it.second.sumOf { h -> h.use })}）", style = MaterialTheme.typography.bodySmall)
                        }
                    } else {
                        // 回路別
                        val totals = HashMap<String, Double>()
                        hours.forEach { h -> h.circuits.forEach { (n, v) -> totals[n] = (totals[n] ?: 0.0) + v } }
                        val all = totals.values.sum()
                        val sorted = totals.entries.sortedByDescending { it.value }
                        Text("回路ごとの使った電気（タップで時間ごとの変化）", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        if (sorted.isEmpty()) Text("回路ごとの記録がありません（AiSEG2 に回路の計測が登録されているか確認してください）。", style = MaterialTheme.typography.bodySmall)
                        val max = sorted.firstOrNull()?.value ?: 1.0
                        sorted.forEach { (name, wh) ->
                            Column(Modifier.fillMaxWidth().clickable { circuit = if (circuit == name) null else name }) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(name, modifier = Modifier.weight(1f), fontWeight = if (circuit == name) FontWeight.Bold else FontWeight.Normal)
                                    Text(kwhOf(wh) + (if (all > 0) String.format("（%.0f%%）", wh / all * 100) else ""), style = MaterialTheme.typography.bodySmall)
                                }
                                Box(Modifier.fillMaxWidth().height(10.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(5.dp))) {
                                    Box(Modifier.fillMaxWidth((wh / max).toFloat().coerceIn(0.01f, 1f)).height(10.dp).background(USE, RoundedCornerShape(5.dp)))
                                }
                            }
                            if (circuit == name) {
                                val slots = if (oneDay) (0..23).map { h -> Slot("${h}時", hours.filter { it.hour == h }.sumOf { it.circuits[name] ?: 0.0 }, 0.0, 0.0, 0.0) }
                                else dayList.reversed().map { d -> Slot(d.substring(5).replace('-', '/'), hours.filter { it.date == d }.sumOf { it.circuits[name] ?: 0.0 }, 0.0, 0.0, 0.0) }
                                FlowChart(slots, onlyUse = true)
                            }
                        }
                    }
                }
                Text(
                    "※ ホームパネルのサーバーが、AiSEG2 の画面のデータを1分ごとに読んで記録しています（40日分）。記録はサーバーが動いている間だけたまります。",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun Total(label: String, value: String, color: Color) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = color)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun Legend() {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("■ 使った電気", color = USE, style = MaterialTheme.typography.labelSmall)
        Text("● 発電", color = GEN, style = MaterialTheme.typography.labelSmall)
        Text("▼ 買った電気", color = BUY, style = MaterialTheme.typography.labelSmall)
        Text("▲ 売った電気", color = SELL, style = MaterialTheme.typography.labelSmall)
    }
}

private data class Slot(val label: String, val use: Double, val gen: Double, val buy: Double, val sell: Double)

/** 棒（使った電気）と、発電（緑の線）・買電（赤）・売電（青）の小さな棒 */
@Composable
private fun FlowChart(slots: List<Slot>, onlyUse: Boolean = false) {
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(Modifier.fillMaxWidth().height(170.dp)) {
        val left = 44.dp.toPx(); val bottom = size.height - 18.dp.toPx(); val top = 6.dp.toPx()
        val max = (slots.maxOfOrNull { maxOf(it.use, it.gen) } ?: 0.0).coerceAtLeast(100.0)
        val w = (size.width - left) / slots.size.coerceAtLeast(1)
        fun y(v: Double) = bottom - (v / max).toFloat() * (bottom - top)
        for (i in 0..2) {
            val v = max * i / 2
            drawLine(grid, Offset(left, y(v)), Offset(size.width, y(v)), 1f)
            val tl = measurer.measure(String.format("%.1f", v / 1000) + "kWh", style)
            drawText(tl, topLeft = Offset(0f, y(v) - tl.size.height / 2))
        }
        val every = when { slots.size > 20 -> 6; slots.size > 10 -> 3; else -> 1 }
        slots.forEachIndexed { i, s ->
            val x = left + i * w
            if (onlyUse) {
                drawRect(USE, topLeft = Offset(x + w * 0.15f, y(s.use)), size = Size(w * 0.7f, bottom - y(s.use)))
            } else {
                drawRect(USE.copy(alpha = 0.85f), topLeft = Offset(x + w * 0.1f, y(s.use)), size = Size(w * 0.45f, bottom - y(s.use)))
                drawRect(BUY, topLeft = Offset(x + w * 0.57f, y(s.buy)), size = Size(w * 0.15f, bottom - y(s.buy)))
                drawRect(SELL, topLeft = Offset(x + w * 0.74f, y(s.sell)), size = Size(w * 0.15f, bottom - y(s.sell)))
                if (s.gen > 0) drawCircle(GEN, radius = 3.dp.toPx(), center = Offset(x + w / 2, y(s.gen)))
                if (i > 0 && slots[i - 1].gen > 0 && s.gen > 0) {
                    drawLine(GEN, Offset(x - w / 2, y(slots[i - 1].gen)), Offset(x + w / 2, y(s.gen)), 2.dp.toPx())
                }
            }
            if (i % every == 0) {
                val tl = measurer.measure(s.label, style)
                drawText(tl, topLeft = Offset((x + w / 2 - tl.size.width / 2).coerceAtLeast(left), bottom + 2))
            }
        }
    }
}
