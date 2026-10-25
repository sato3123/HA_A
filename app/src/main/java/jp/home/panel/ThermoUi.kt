package jp.home.panel

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val LEVEL_COLORS = listOf(Color(0xFF64B5F6), Color(0xFF4DD0E1), Color(0xFF81C784), Color(0xFFFFB74D), Color(0xFFE57373))

/** ホーム画面の「温度・湿度」（SwitchBot 温湿度計など）。タップで温度の変化のグラフ */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ThermoStrip(vm: MainViewModel) {
    val list by vm.thermos.collectAsState()
    val hidden by vm.thermoHidden.collectAsState()
    val shown = list.filter { it.key !in hidden }
    if (shown.isEmpty()) return
    val compact = LocalConfiguration.current.screenWidthDp < 600
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        shown.forEach { t ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier
                    .width(if (compact) 160.dp else 176.dp)
                    .clickable(enabled = t.tempEntity != null || t.humEntity != null) {
                        vm.showThermo(t.key)
                    },
            ) {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).background(LEVEL_COLORS[tempLevel(t.temperature)], CircleShape))
                    Column(Modifier.padding(start = 8.dp)) {
                        Text(t.name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                if (t.unavailable) "－" else t.temperature?.let { fmt1(it) + "℃" } ?: "－",
                                fontSize = 22.sp, fontWeight = FontWeight.Medium,
                            )
                            t.humidity?.let { Text("💧" + fmt1(it).removeSuffix(".0") + "%", style = MaterialTheme.typography.bodyMedium) }
                        }
                        t.battery?.takeIf { it < 20 }?.let {
                            Text("🔋 電池残り ${it.toInt()}%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}

private fun fmt1(v: Double) = String.format("%.1f", v)

/** 設定画面：ホーム画面に出す温度計 */
@Composable
fun ThermoSettings(vm: MainViewModel) {
    val list by vm.thermos.collectAsState()
    val hidden by vm.thermoHidden.collectAsState()
    val names by vm.thermoNames.collectAsState()
    var renaming by remember { mutableStateOf<Thermo?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "Home Assistant に取り込んだ温度計（SwitchBot 温湿度計など）の温度と湿度を、ホーム画面に表示します。" +
                "温度と湿度のセンサーは、同じ機器のものを1つにまとめて表示します。「✎ 名前」で部屋の名前などに変えられます。",
            style = MaterialTheme.typography.bodySmall,
        )
        if (list.isEmpty()) {
            Text(
                "温度計が見つかりません。Home Assistant に SwitchBot の連携（SwitchBot Bluetooth または SwitchBot Cloud）を追加すると、ここに表示されます。",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        list.forEach { t ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { vm.toggleThermo(t.key) },
            ) {
                Checkbox(checked = t.key !in hidden, onCheckedChange = { vm.toggleThermo(t.key) })
                Column(Modifier.weight(1f)) {
                    Text(t.name, style = MaterialTheme.typography.bodyLarge)
                    if (names.containsKey(t.key)) Text("元の名前：" + t.original, style = MaterialTheme.typography.labelSmall)
                    Text(
                        listOfNotNull(
                            t.temperature?.let { fmt1(it) + "℃" },
                            t.humidity?.let { fmt1(it) + "%" },
                            t.battery?.let { "電池 ${it.toInt()}%" },
                        ).joinToString("・").ifBlank { "値なし" },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                TextButton(onClick = { renaming = t }) { Text("✎ 名前") }
            }
        }
    }
    renaming?.let { t -> RenameThermoDialog(vm, t, onDismiss = { renaming = null }) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RenameThermoDialog(vm: MainViewModel, t: Thermo, onDismiss: () -> Unit) {
    val rooms by vm.rooms.collectAsState()
    var text by remember { mutableStateOf(t.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("温度計の名前") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true,
                    label = { Text("名前") }, modifier = Modifier.fillMaxWidth())
                if (rooms.isNotEmpty()) {
                    Text("部屋の名前から選ぶ", style = MaterialTheme.typography.labelMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        rooms.forEach { r ->
                            FilterChip(selected = text == r.name, onClick = { text = r.name }, label = { Text(r.name) })
                        }
                    }
                }
                Text("元の名前：" + t.original, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { vm.renameThermo(t.key, text); onDismiss() }) { Text("保存") } },
        dismissButton = {
            Row {
                TextButton(onClick = { vm.renameThermo(t.key, ""); onDismiss() }) { Text("元に戻す") }
                TextButton(onClick = onDismiss) { Text("やめる") }
            }
        },
    )
}

/** 温度計の詳細：温度（と湿度）の変化のグラフ */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ThermoDetailDialog(vm: MainViewModel) {
    val key by vm.thermoFor.collectAsState()
    val list by vm.thermos.collectAsState()
    val t = list.firstOrNull { it.key == key } ?: return
    val hours by vm.thermoHours.collectAsState()
    val temp by vm.thermoTemp.collectAsState()
    val hum by vm.thermoHum.collectAsState()
    val status by vm.thermoStatus.collectAsState()
    AlertDialog(
        onDismissRequest = { vm.hideThermo() },
        title = { Text("🌡 " + t.name) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(t.temperature?.let { fmt1(it) + "℃" } ?: "－", fontSize = 32.sp, fontWeight = FontWeight.Medium)
                    t.humidity?.let { Text("💧" + fmt1(it).removeSuffix(".0") + "%", style = MaterialTheme.typography.titleMedium) }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(24 to "24時間", 72 to "3日", 168 to "7日").forEach { (h, label) ->
                        FilterChip(selected = hours == h, onClick = { vm.setThermoHours(h) }, label = { Text(label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary))
                    }
                }
                status?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                temp?.takeIf { it.size >= 2 }?.let { pts ->
                    Text("温度", style = MaterialTheme.typography.labelLarge)
                    Summary(pts, "℃")
                    LineChart(pts, hours, Color(0xFFFF8A65), "℃")
                }
                hum?.takeIf { it.size >= 2 }?.let { pts ->
                    Text("湿度", style = MaterialTheme.typography.labelLarge)
                    Summary(pts, "%")
                    LineChart(pts, hours, Color(0xFF4FC3F7), "%")
                }
                t.battery?.let { Text("🔋 電池残り ${it.toInt()}%", style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = { vm.hideThermo() }) { Text("閉じる") } },
    )
}

@Composable
private fun Summary(pts: List<MainViewModel.Point>, unit: String) {
    val lo = pts.minBy { it.v }
    val hi = pts.maxBy { it.v }
    val f = DateTimeFormatter.ofPattern("M/d H:mm")
    fun at(p: MainViewModel.Point) = Instant.ofEpochMilli(p.t).atZone(ZoneId.systemDefault()).format(f)
    Text(
        "最高 ${fmt1(hi.v)}$unit（${at(hi)}）　最低 ${fmt1(lo.v)}$unit（${at(lo)}）　平均 ${fmt1(pts.map { it.v }.average())}$unit",
        style = MaterialTheme.typography.bodySmall,
    )
}

/** 折れ線グラフ（横は時刻、縦は値） */
@Composable
private fun LineChart(pts: List<MainViewModel.Point>, hours: Int, color: Color, unit: String) {
    val grid = MaterialTheme.colorScheme.outlineVariant
    val label = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelSmall.copy(color = label)
    val end = System.currentTimeMillis()
    val start = end - hours * 3_600_000L
    var lo = pts.minOf { it.v }
    var hi = pts.maxOf { it.v }
    if (hi - lo < 2) { val c = (hi + lo) / 2; lo = c - 1; hi = c + 1 }
    lo = kotlin.math.floor(lo); hi = kotlin.math.ceil(hi)
    Canvas(Modifier.fillMaxWidth().height(180.dp)) {
        val left = 36.dp.toPx(); val bottom = size.height - 18.dp.toPx(); val top = 6.dp.toPx()
        val w = size.width - left; val h = bottom - top
        fun x(t: Long) = left + w * ((t - start).toFloat() / (end - start))
        fun y(v: Double) = top + h * (1f - ((v - lo) / (hi - lo)).toFloat())
        // 横線と目盛り
        for (i in 0..4) {
            val v = lo + (hi - lo) * i / 4
            val yy = y(v)
            drawLine(grid, Offset(left, yy), Offset(size.width, yy), 1f)
            val tl = measurer.measure(String.format("%.0f", v) + unit, style)
            drawText(tl, topLeft = Offset(0f, yy - tl.size.height / 2))
        }
        // 時刻の目盛り
        val zone = ZoneId.systemDefault()
        val step = if (hours <= 24) 6 else 24
        var tick = Instant.ofEpochMilli(start).atZone(zone).withMinute(0).withSecond(0).withNano(0)
        tick = if (step == 24) tick.withHour(0).plusDays(1) else tick.plusHours(((step - tick.hour % step) % step).toLong().let { if (it == 0L) step.toLong() else it })
        val f = DateTimeFormatter.ofPattern(if (step == 24) "M/d" else "H時")
        while (tick.toInstant().toEpochMilli() < end) {
            val xx = x(tick.toInstant().toEpochMilli())
            drawLine(grid, Offset(xx, top), Offset(xx, bottom), 1f)
            val tl = measurer.measure(tick.format(f), style)
            drawText(tl, topLeft = Offset((xx - tl.size.width / 2).coerceIn(left, size.width - tl.size.width), bottom + 2))
            tick = tick.plusHours(step.toLong())
        }
        // 線
        val path = Path()
        val sorted = pts.sortedBy { it.t }
        sorted.forEachIndexed { i, p ->
            val px = x(p.t.coerceAtLeast(start)); val py = y(p.v)
            if (i == 0) path.moveTo(px, py) else {
                // 記録は変化したときだけなので、階段状に描く
                path.lineTo(px, y(sorted[i - 1].v)); path.lineTo(px, py)
            }
        }
        path.lineTo(size.width, y(sorted.last().v))
        drawPath(path, color, style = Stroke(width = 2.dp.toPx()))
    }
}
