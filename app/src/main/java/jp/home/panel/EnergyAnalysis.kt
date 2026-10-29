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
    val yen: Double? = null, val sellYen: Double? = null, val rate: Double? = null, val band: String? = null,
) {
    val date: String get() = key.take(10)
    val hour: Int get() = key.substring(11, 13).toIntOrNull() ?: 0
}

private val USE = Color(0xFFFFB74D)
private val GEN = Color(0xFF66BB6A)
private val BUY = Color(0xFFEF5350)
private val SELL = Color(0xFF42A5F5)

private val COMPARE = listOf(Color(0xFFE53935), Color(0xFF1E88E5), Color(0xFF43A047), Color(0xFFFB8C00), Color(0xFF8E24AA), Color(0xFF00ACC1))

private fun kwhOf(wh: Double) = if (wh >= 100_000) String.format("%.0f kWh", wh / 1000) else String.format("%.1f kWh", wh / 1000)

/** 電気の分析（時間別・回路別）。データはホームパネルのサーバーが AiSEG2 から1分ごとに記録したもの */
@Composable
fun EnergyAnalysisDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    var period by remember { mutableStateOf("today") }
    var tab by remember { mutableStateOf("hour") }
    var circuit by remember { mutableStateOf<String?>(null) }
    val data by vm.energyHours.collectAsState()
    val daily by vm.energyDaily.collectAsState()
    var picked by remember { mutableStateOf<Set<String>?>(null) }
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
                    listOf("hour" to "時間別", "five" to "5分ごと（回路別）", "day" to "日ごと（天気・気温）", "circuit" to "回路別", "compare" to "回路の比較").forEach { (k, l) ->
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
                        if (hours.any { it.yen != null }) Total("電気代（買った電気）", String.format("%,.0f円", hours.sumOf { it.yen ?: 0.0 }), BUY)
                        if (hours.any { (it.sellYen ?: 0.0) > 0 }) Total("売電", String.format("%,.0f円", hours.sumOf { it.sellYen ?: 0.0 }), SELL)
                    }
                    val cost by vm.energyCost.collectAsState()
                    if (cost == null) Text("※ 電気代を出すには、⚙ 設定 →「電気料金」で電力会社とプランを選んでください。", style = MaterialTheme.typography.labelSmall)
                    else cost?.optJSONObject("months")?.let { ms ->
                        ms.keys().asSequence().sorted().toList().takeLast(2).forEach { k ->
                            val m = ms.optJSONObject(k) ?: return@forEach
                            Text("${k.substring(5).trimStart('0')}月：電気代 ${String.format("%,d", m.optLong("total_yen"))}円（使用 ${String.format("%,.0f", m.optDouble("energy_yen"))}円＋基本料金 ${String.format("%,d", m.optLong("basic_yen"))}円・買った電気 ${String.format("%.1f", m.optDouble("kwh"))}kWh）" +
                                (if (m.optDouble("sell_yen") > 0) "・売電 ${String.format("%,.0f", m.optDouble("sell_yen"))}円" else "") + "　${cost?.optString("plan")}",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    if (tab == "hour") {
                        // 時間別（1日なら24時間、複数日なら日ごと）
                        val slots: List<Pair<String, List<EnergyHour>>> = if (oneDay)
                            (0..23).map { h -> "${h}時" to hours.filter { it.hour == h } }
                        else dayList.reversed().map { d -> d.substring(5).replace('-', '/') to hours.filter { it.date == d } }
                        Text(if (oneDay) "時間ごとの電気" else "日ごとの電気", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        Legend()
                        FlowChart(slots.map { (l, list) -> Slot(l, list.sumOf { it.use }, list.sumOf { it.gen }, list.sumOf { it.buy }, list.sumOf { it.sell }) })
                        if (oneDay && hours.any { it.yen != null }) {
                            val costly = slots.filter { it.second.isNotEmpty() }.sortedByDescending { it.second.sumOf { h -> h.yen ?: 0.0 } }.take(3)
                            Text("電気代が多かった時間：" + costly.joinToString("、") { (l, list) -> "$l ${String.format("%,.0f", list.sumOf { it.yen ?: 0.0 })}円（${list.firstOrNull()?.rate?.let { r -> "${r}円/kWh" } ?: ""}）" },
                                style = MaterialTheme.typography.bodySmall)
                        }
                        val peak = slots.maxByOrNull { it.second.sumOf { h -> h.use } }
                        peak?.takeIf { it.second.isNotEmpty() }?.let {
                            Text("いちばん多く使ったのは ${it.first}（${kwhOf(it.second.sumOf { h -> h.use })}）", style = MaterialTheme.typography.bodySmall)
                        }
                    } else if (tab == "five") {
                        FiveMinuteView(vm)
                    } else if (tab == "day") {
                        DailyWeatherTable(if (oneDay) (0..6).map { today.minusDays(it.toLong()).toString() } else dayList, data ?: emptyList(), daily)
                    } else if (tab == "compare") {
                        // 回路の比較（選んだ回路を同じグラフに重ねる）
                        val totals = circuitTotals(dayList, hours, daily)
                        val names = totals.entries.sortedByDescending { it.value }.map { it.key }
                        val sel = (picked ?: names.take(3).toSet()).filter { it in totals }
                        Text("比べる回路を選んでください（最大6つ）", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        if (names.isEmpty()) Text("回路ごとの記録がありません（AiSEG2 に回路の計測が登録されているか確認してください）。", style = MaterialTheme.typography.bodySmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            names.forEach { n ->
                                val i = sel.indexOf(n)
                                FilterChip(
                                    selected = i >= 0,
                                    onClick = {
                                        val cur = sel.toMutableSet()
                                        if (n in cur) cur.remove(n) else if (cur.size < 6) cur.add(n)
                                        picked = cur
                                    },
                                    label = { Text((if (i >= 0) "● " else "") + n, color = if (i >= 0) COMPARE[i % COMPARE.size] else Color.Unspecified) },
                                )
                            }
                        }
                        if (sel.isNotEmpty()) {
                            val labels = if (oneDay) (0..23).map { "${it}時" } else dayList.reversed().map { it.substring(5).replace('-', '/') }
                            val series = sel.map { n ->
                                n to (if (oneDay) (0..23).map { h -> hours.filter { it.hour == h }.sumOf { it.circuits[n] ?: 0.0 } }
                                else dayList.reversed().map { d -> circuitDay(d, n, hours, daily) })
                            }
                            CompareChart(labels, series.map { it.second })
                            val all = totals.values.sum()
                            series.forEachIndexed { i, (n, v) ->
                                val t = v.sum()
                                val peak = v.indices.maxByOrNull { v[it] }
                                Text(
                                    "● $n　${kwhOf(t)}" + (if (all > 0) String.format("（全体の%.0f%%）", t / all * 100) else "") +
                                        (peak?.takeIf { v[it] > 0 }?.let { "・いちばん多いのは ${labels[it]}" } ?: ""),
                                    color = COMPARE[i % COMPARE.size], style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    } else {
                        // 回路別
                        val totals = circuitTotals(dayList, hours, daily)
                        val all = totals.values.sum()
                        if (dayList.any { daily[it]?.has("circuits_kwh") == true }) {
                            Text("※ 合計は AiSEG2 の「回路ごとの使用電力量（日）」の値です（エコキュート・エアコンなど 200V の回路も入ります）。", style = MaterialTheme.typography.labelSmall)
                        }
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
                                else dayList.reversed().map { d -> Slot(d.substring(5).replace('-', '/'), circuitDay(d, name, hours, daily), 0.0, 0.0, 0.0) }
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

/** 天気の番号（WMO）→ 絵文字と名前 */
fun wmoWeather(code: Int?): Pair<String, String> = when (code) {
    null -> "－" to "不明"
    0 -> "☀️" to "快晴"
    1 -> "🌤️" to "晴れ"
    2 -> "⛅" to "晴れ時々くもり"
    3 -> "☁️" to "くもり"
    45, 48 -> "🌫️" to "霧"
    in 51..57 -> "🌦️" to "霧雨"
    in 61..67, in 80..82 -> "🌧️" to "雨"
    in 71..77, 85, 86 -> "❄️" to "雪"
    in 95..99 -> "⛈️" to "雷雨"
    else -> "🌡" to "－"
}

/** 日ごとの電気と、天気・最高/最低気温・日照時間・雨・お湯 */
@Composable
private fun DailyWeatherTable(days: List<String>, data: List<EnergyHour>, daily: Map<String, org.json.JSONObject>) {
    Text("日ごとの電気と天気・気温", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    if (daily.isEmpty()) Text("天気・気温の記録はまだありません（サーバーが3時間ごとに記録します。自宅の位置をサーバーに送ってください）。", style = MaterialTheme.typography.bodySmall)
    val maxUse = days.maxOfOrNull { d -> data.filter { it.date == d }.sumOf { it.use } }?.coerceAtLeast(1.0) ?: 1.0
    days.forEach { d ->
        val h = data.filter { it.date == d }
        val w = daily[d]
        fun num(k: String) = w?.takeIf { it.has(k) && !it.isNull(k) }?.optDouble(k)?.takeIf { !it.isNaN() }
        val (emoji, wname) = wmoWeather(w?.takeIf { it.has("weather_code") && !it.isNull("weather_code") }?.optInt("weather_code"))
        val date = runCatching { LocalDate.parse(d) }.getOrNull()
        val label = date?.let { "${it.monthValue}/${it.dayOfMonth}（${"月火水木金土日"[it.dayOfWeek.value - 1]}）" } ?: d
        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)).padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(96.dp))
                Text("$emoji $wname", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(
                    listOfNotNull(
                        num("tmax")?.let { "最高 ${fmt1(it)}℃" }, num("tmin")?.let { "最低 ${fmt1(it)}℃" },
                    ).joinToString("・"),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                listOfNotNull(
                    num("sun_h")?.let { "日照 ${fmt1(it)}時間" }, num("rain_mm")?.takeIf { it > 0 }?.let { "雨 ${fmt1(it)}mm" },
                    num("hot_use_l")?.let { "お湯 ${it.toInt()}L" },
                ).joinToString("・").ifBlank { " " },
                style = MaterialTheme.typography.bodySmall,
            )
            if (h.isEmpty()) Text("電気の記録なし", style = MaterialTheme.typography.bodySmall)
            else {
                val use = h.sumOf { it.use }
                Box(Modifier.padding(vertical = 3.dp).fillMaxWidth((use / maxUse).toFloat().coerceIn(0.01f, 1f)).height(8.dp).background(USE, RoundedCornerShape(4.dp)))
                Text(
                    "使った ${kwhOf(use)}・発電 ${kwhOf(h.sumOf { it.gen })}・買った ${kwhOf(h.sumOf { it.buy })}・売った ${kwhOf(h.sumOf { it.sell })}" +
                        (if (h.any { it.yen != null }) "・電気代 ${String.format("%,.0f", h.sumOf { it.yen ?: 0.0 })}円" else ""),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
    Text("※ 天気・気温は Open-Meteo（自宅の位置）の値です。日の切り替わりは日本時間の0時です。", style = MaterialTheme.typography.labelSmall)
}

private fun fmt1(v: Double) = String.format("%.1f", v)

/** 回路の比較：選んだ回路ごとの折れ線 */
@Composable
private fun CompareChart(labels: List<String>, series: List<List<Double>>, unit: (Double) -> String = { String.format("%.1f", it / 1000) + "kWh" }) {
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(Modifier.fillMaxWidth().height(200.dp)) {
        val left = 44.dp.toPx(); val bottom = size.height - 18.dp.toPx(); val top = 6.dp.toPx()
        val max = (series.flatten().maxOrNull() ?: 0.0).coerceAtLeast(50.0)
        val n = labels.size.coerceAtLeast(1)
        val w = (size.width - left) / n
        fun y(v: Double) = bottom - (v / max).toFloat() * (bottom - top)
        for (i in 0..2) {
            val v = max * i / 2
            drawLine(grid, Offset(left, y(v)), Offset(size.width, y(v)), 1f)
            val tl = measurer.measure(unit(v), style)
            drawText(tl, topLeft = Offset(0f, y(v) - tl.size.height / 2))
        }
        val every = when { n > 200 -> 36; n > 100 -> 12; n > 20 -> 6; n > 10 -> 3; else -> 1 }
        labels.forEachIndexed { i, l ->
            if (i % every == 0) {
                val tl = measurer.measure(l, style)
                drawText(tl, topLeft = Offset((left + i * w + w / 2 - tl.size.width / 2).coerceAtLeast(left), bottom + 2))
            }
        }
        series.forEachIndexed { si, v ->
            val c = COMPARE[si % COMPARE.size]
            v.forEachIndexed { i, value ->
                val x = left + i * w + w / 2
                if (n <= 60) drawCircle(c, radius = 2.5.dp.toPx(), center = Offset(x, y(value)))
                if (i > 0) drawLine(c, Offset(x - w, y(v[i - 1])), Offset(x, y(value)), if (n > 60) 1.5.dp.toPx() else 2.dp.toPx())
            }
        }
    }
}

/** 5分ごとの電気（その日）。回路を選んで重ねて見る。値は5分間の平均の電力（W） */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun FiveMinuteView(vm: MainViewModel) {
    val today = LocalDate.now()
    var day by remember { mutableStateOf(today) }
    var data by remember { mutableStateOf<List<EnergyHour>?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var picked by remember { mutableStateOf<Set<String>?>(null) }
    LaunchedEffect(day) {
        data = null; err = null
        try { data = vm.energy5(day.toString()) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { err = e.message }
    }
    val chip = FilterChipDefaults.filterChipColors(
        selectedContainerColor = MaterialTheme.colorScheme.primary,
        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
    )
    Text("5分ごとの電気（5分間の平均の電力）", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        (0..6).forEach { i ->
            val d = today.minusDays(i.toLong())
            FilterChip(selected = day == d, onClick = { day = d },
                label = { Text(when (i) { 0 -> "今日"; 1 -> "昨日"; else -> "${d.monthValue}/${d.dayOfMonth}" }) }, colors = chip)
        }
    }
    err?.let { Text("読めませんでした：$it", style = MaterialTheme.typography.bodySmall) }
    val list = data
    if (list == null && err == null) Text("読んでいます…", style = MaterialTheme.typography.bodySmall)
    if (list == null) return
    if (list.isEmpty()) {
        Text("この日の5分ごとの記録はまだありません（記録は8日分です。サーバーを新しい版にした時点からたまります）。", style = MaterialTheme.typography.bodySmall)
        return
    }
    val HOUSE = "家全体（使った電気）"
    val totals = HashMap<String, Double>()
    list.forEach { h -> h.circuits.forEach { (n, v) -> totals[n] = (totals[n] ?: 0.0) + v } }
    val names = listOf(HOUSE) + totals.entries.sortedByDescending { it.value }.map { it.key }
    val sel = (picked ?: (listOf(HOUSE) + names.drop(1).take(2)).toSet()).filter { it in names }
    Text("見る回路（最大6つ）", style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        names.forEach { n ->
            val i = sel.indexOf(n)
            FilterChip(
                selected = i >= 0,
                onClick = {
                    val cur = sel.toMutableSet()
                    if (n in cur) cur.remove(n) else if (cur.size < 6) cur.add(n)
                    picked = cur
                },
                label = { Text((if (i >= 0) "● " else "") + n, color = if (i >= 0) COMPARE[i % COMPARE.size] else Color.Unspecified) },
            )
        }
    }
    // 0:00〜23:55 の 288 コマ
    val byKey = list.associateBy { it.key.substring(11, 16) }
    val keys = (0 until 288).map { String.format("%d:%02d", it / 12, it % 12 * 5) }
    val padKeys = (0 until 288).map { String.format("%02d:%02d", it / 12, it % 12 * 5) }
    val last = if (day == today) (java.time.LocalTime.now().let { it.hour * 12 + it.minute / 5 }) else 287
    val series = sel.map { n ->
        padKeys.take(last + 1).map { k ->
            val h = byKey[k]
            val wh = if (h == null) 0.0 else if (n == HOUSE) h.use else h.circuits[n] ?: 0.0
            wh * 12 // 5分間の Wh → 平均の W
        }
    }
    CompareChart(keys.take(last + 1), series) { v -> if (v >= 1000) String.format("%.1fkW", v / 1000) else String.format("%.0fW", v) }
    series.forEachIndexed { i, v ->
        val peak = v.indices.maxByOrNull { v[it] }
        val kwh = v.sum() / 12 / 1000
        Text(
            "● ${sel[i]}　${String.format("%.2f", kwh)}kWh" + (peak?.takeIf { v[it] > 0 }?.let { "・いちばん多いのは ${keys[it]}ごろ（${String.format("%.0f", v[it])}W）" } ?: ""),
            color = COMPARE[i % COMPARE.size], style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** その日の回路の使用量（Wh）。AiSEG2 の1日ごとの値があればそれを、無ければ時間ごとの記録の合計 */
private fun circuitDay(d: String, name: String, hours: List<EnergyHour>, daily: Map<String, org.json.JSONObject>): Double {
    val ck = daily[d]?.optJSONObject("circuits_kwh")
    if (ck != null) return ck.optDouble(name, 0.0) * 1000
    return hours.filter { it.date == d }.sumOf { it.circuits[name] ?: 0.0 }
}

/** 期間の回路ごとの使用量（Wh） */
private fun circuitTotals(days: List<String>, hours: List<EnergyHour>, daily: Map<String, org.json.JSONObject>): Map<String, Double> {
    val out = HashMap<String, Double>()
    for (d in days) {
        val ck = daily[d]?.optJSONObject("circuits_kwh")
        if (ck != null) ck.keys().forEach { k -> out[k] = (out[k] ?: 0.0) + ck.optDouble(k, 0.0) * 1000 }
        else hours.filter { it.date == d }.forEach { h -> h.circuits.forEach { (n, v) -> out[n] = (out[n] ?: 0.0) + v } }
    }
    return out
}
