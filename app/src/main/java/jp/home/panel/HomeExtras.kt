@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package jp.home.panel

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

private fun kw(w: Double) = String.format("%.1f", w / 1000)

// ======================= リアルタイム表示（発電・消費・売買電・エコキュート） =======================

/** ホーム画面：いまの発電・消費・売電／買電とエコキュート（表示している間、10秒ごとに更新） */
@Composable
fun LiveStrip(vm: MainViewModel) {
    val server by vm.shServer.collectAsState()
    if (server.isBlank()) return
    DisposableEffect(Unit) {
        vm.watchLive(true)
        onDispose { vm.watchLive(false) }
    }
    val l by vm.live.collectAsState()
    val awayUntil by vm.awayUntil.collectAsState()
    val v = l ?: return
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                v.genW?.let { LiveItem("☀ 発電", "${kw(it)}kW", Color(0xFF66BB6A)) }
                v.useW?.let { LiveItem("🏠 消費", "${kw(it)}kW", Color(0xFFFFB74D)) }
                v.surplusW?.let {
                    if (it >= 0) LiveItem("⚡ 売電", "${kw(it)}kW", Color(0xFF42A5F5)) else LiveItem("⚡ 買電", "${kw(-it)}kW", Color(0xFFEF5350))
                }
                val eco = when {
                    awayUntil > System.currentTimeMillis() -> "🧳 留守"
                    v.ecoOnline == false -> "応答なし"
                    v.heating == true -> "♨ 沸き上げ中"
                    v.heating == false -> "停止中"
                    else -> null
                }
                eco?.let { e ->
                    val rest = v.remainL?.let { r -> " 残り${r}L" + (v.tankL?.takeIf { it > 0 }?.let { "（${r * 100 / it}%）" } ?: "") } ?: ""
                    LiveItem("🛁 エコキュート", e + rest, if (v.heating == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                }
                v.ecoTodayKwh?.let { LiveItem("今日の沸き上げ", String.format("%.1fkWh", it), MaterialTheme.colorScheme.onSurface) }
            }
            v.demand?.let { d ->
                val proj = d.optDouble("projected_kw", Double.NaN)
                val warn = d.optDouble("warn_kw", Double.NaN)
                if (!proj.isNaN()) {
                    val over = !warn.isNaN() && proj > warn
                    Text(
                        "30分の電力（${d.optString("slot").takeLast(5)}〜）：このままだと ${String.format("%.1f", proj)}kW" +
                            (if (!warn.isNaN()) "（注意 ${String.format("%.1f", warn)}kW・上限 ${String.format("%.1f", d.optDouble("limit_kw"))}kW）" else ""),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (over) FontWeight.Bold else null,
                    )
                }
            }
            v.guard?.takeIf { it.optBoolean("active") }?.let { g ->
                Text("⏸ 買電が多いので、お湯の沸き上げを一時停止しています（${g.optString("reason")}）", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
            }
            v.demandAlert?.let { a ->
                if (System.currentTimeMillis() - (a.optDouble("at") * 1000).toLong() < 30 * 60_000) {
                    Text("⚠ " + a.optString("message"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun LiveItem(label: String, value: String, color: Color) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 17.sp, fontWeight = FontWeight.Medium, color = color)
    }
}

// ======================= 留守（エコキュートの沸き上げを数日止める） =======================

/** エコキュートのカードの中：留守の状態と、設定・取り消しのボタン */
@Composable
fun AwayRow(vm: MainViewModel) {
    val until by vm.awayUntil.collectAsState()
    val phase by vm.awayPhase.collectAsState()
    val msg by vm.awayMsg.collectAsState()
    var dialog by remember { mutableStateOf(false) }
    if (dialog) AwayDialog(vm) { dialog = false }
    msg?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    if (until > System.currentTimeMillis()) {
        val t = LocalDateTime.ofInstant(Instant.ofEpochMilli(until), ZoneId.systemDefault())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "🧳 留守：${t.monthValue}/${t.dayOfMonth} ${t.hour}:${String.format("%02d", t.minute)} に帰る予定　" +
                    if (phase == "reheat") "（帰る前なので沸かしています）" else "（沸き上げを止めています）",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { dialog = true }) { Text("変更") }
            TextButton(onClick = { vm.cancelAway() }) { Text("取り消す") }
        }
    } else {
        OutlinedButton(onClick = { dialog = true }) { Text("🧳 留守にする（沸き上げを止める）") }
    }
}

@Composable
fun AwayDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    val cur by vm.awayUntil.collectAsState()
    val curReheat by vm.awayReheatHours.collectAsState()
    val today = LocalDate.now()
    val init = if (cur > System.currentTimeMillis()) LocalDateTime.ofInstant(Instant.ofEpochMilli(cur), ZoneId.systemDefault()) else today.plusDays(2).atTime(18, 0)
    var day by remember { mutableStateOf(init.toLocalDate()) }
    var hour by remember { mutableStateOf(init.hour) }
    var reheat by remember { mutableStateOf(curReheat) }
    val chip = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primary, selectedLabelColor = MaterialTheme.colorScheme.onPrimary)
    val at = day.atTime(hour, 0)
    val ok = at.isAfter(LocalDateTime.now())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("🧳 留守にする") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("帰る日時まで、エコキュートの沸き上げ（夜の沸き上げも）を止めます。帰る少し前から沸かし始めるので、帰ったときにはお湯があります。", style = MaterialTheme.typography.bodySmall)
                Text("帰る日", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (0..13).forEach { i ->
                        val d = today.plusDays(i.toLong())
                        val w = "日月火水木金土"[d.dayOfWeek.value % 7]
                        FilterChip(selected = day == d, onClick = { day = d }, label = { Text(if (i == 0) "今日" else if (i == 1) "明日" else "${d.monthValue}/${d.dayOfMonth}($w)") }, colors = chip)
                    }
                }
                Text("帰る時刻", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(6, 9, 12, 15, 17, 18, 19, 20, 21, 22).forEach { h ->
                        FilterChip(selected = hour == h, onClick = { hour = h }, label = { Text("${h}時") }, colors = chip)
                    }
                }
                Text("帰る何時間前から沸かすか", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0.0 to "沸かさない", 3.0 to "3時間", 6.0 to "6時間", 9.0 to "9時間", 12.0 to "12時間").forEach { (h, l) ->
                        FilterChip(selected = reheat == h, onClick = { reheat = h }, label = { Text(l) }, colors = chip)
                    }
                }
                Text(
                    if (ok) "→ ${at.monthValue}/${at.dayOfMonth} ${at.hour}時まで止めます" + if (reheat > 0) "（${at.minusHours(reheat.toLong()).let { "${it.monthValue}/${it.dayOfMonth} ${it.hour}時" }} から沸かします）" else ""
                    else "帰る日時が過ぎています",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
                Text("※ 留守の間に誰かがリモコンで沸き上げを戻しても、10分ごとに止め直します。凍結防止など、エコキュート自身の安全のための運転は止まりません。", style = MaterialTheme.typography.labelSmall)
            }
        },
        confirmButton = {
            TextButton(enabled = ok, onClick = {
                vm.setAway(at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), reheat)
                onDismiss()
            }) { Text("留守にする") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("やめる") } },
    )
}

// ======================= まとめて操作（ボタン1つで、いくつもの機器をつける・消す） =======================

private val GROUP_DOMAINS = setOf("switch", "light", "fan", "input_boolean", "climate", "media_player", "cover", "humidifier")

/** ホーム画面：まとめて操作のボタン */
@Composable
fun GroupButtons(vm: MainViewModel, compact: Boolean) {
    val server by vm.shServer.collectAsState()
    val groups by vm.groups.collectAsState()
    val msg by vm.groupMsg.collectAsState()
    if (server.isBlank()) return
    var edit by remember { mutableStateOf<MainViewModel.DeviceGroup?>(null) }
    var confirm by remember { mutableStateOf<MainViewModel.DeviceGroup?>(null) }
    edit?.let { g -> GroupEditor(vm, g) { edit = null } }
    confirm?.let { g ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text((g.icon.ifBlank { "🔘" }) + " " + g.name) },
            text = { Text("${g.entities.size}台の機器をまとめて操作します。") },
            confirmButton = {
                Row {
                    TextButton(onClick = { vm.runGroup(g, "on"); confirm = null }) { Text("まとめてつける") }
                    TextButton(onClick = { vm.runGroup(g, "off"); confirm = null }) { Text("まとめて消す") }
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { edit = g; confirm = null }) { Text("✎ 編集") }
                    TextButton(onClick = { confirm = null }) { Text("閉じる") }
                }
            },
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            groups.forEach { g ->
                // タップ：ボタンの設定どおり（つける／消す）。もう一方や編集は「…」から
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { vm.runGroup(g) }) {
                        Text((g.icon.ifBlank { if (g.action == "on") "💡" else "⏻" }) + " " + g.name, fontSize = if (compact) 13.sp else 15.sp)
                    }
                    TextButton(onClick = { confirm = g }) { Text("…") }
                }
            }
            TextButton(onClick = { edit = MainViewModel.DeviceGroup(java.util.UUID.randomUUID().toString().take(8), "", "", "off", emptyList()) }) {
                Text(if (groups.isEmpty()) "＋ まとめて操作のボタンを作る" else "＋")
            }
        }
        msg?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
    }
}

@Composable
private fun GroupEditor(vm: MainViewModel, g0: MainViewModel.DeviceGroup, onDismiss: () -> Unit) {
    val all by vm.allEntities.collectAsState()
    val groups by vm.groups.collectAsState()
    var name by remember { mutableStateOf(g0.name) }
    var icon by remember { mutableStateOf(g0.icon) }
    var action by remember { mutableStateOf(g0.action) }
    var picked by remember { mutableStateOf(g0.entities.toSet()) }
    var q by remember { mutableStateOf("") }
    val chip = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primary, selectedLabelColor = MaterialTheme.colorScheme.onPrimary)
    val cands = all.filter { it.domain in GROUP_DOMAINS }
        .filter { q.isBlank() || it.name.contains(q, true) || it.entityId.contains(q, true) || vm.roomNameOf(it.entityId)?.contains(q) == true || it.entityId in picked }
        .sortedWith(compareBy({ it.entityId !in picked }, { vm.roomNameOf(it.entityId) ?: "￿" }, { it.domain }, { it.name }))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (g0.name.isBlank()) "まとめて操作のボタンを作る" else "「${g0.name}」を編集") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(name, { name = it.take(30) }, label = { Text("ボタンの名前（例：おやすみ・出かける）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("", "🌙", "🚪", "💡", "⏻", "📺", "❄").forEach { ic ->
                        FilterChip(selected = icon == ic, onClick = { icon = ic }, label = { Text(ic.ifBlank { "なし" }) }, colors = chip)
                    }
                }
                Text("タップしたときの操作", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = action == "off", onClick = { action = "off" }, label = { Text("まとめて消す") }, colors = chip)
                    FilterChip(selected = action == "on", onClick = { action = "on" }, label = { Text("まとめてつける") }, colors = chip)
                }
                Text("機器（${picked.size}台を選択中）", style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(q, { q = it }, label = { Text("さがす") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                    cands.forEach { e ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = e.entityId in picked, onCheckedChange = { c -> picked = if (c) picked + e.entityId else picked - e.entityId })
                            Text(withRoom(vm.roomNameOf(e.entityId), e.name) + "（" + e.state + "）" + if (isEchonetEntity(e.entityId)) "・ECHONET Lite" else "",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Text("※ ボタンの設定はサーバーに保存され、この家のほかのタブレット・iPad にも出ます。操作はサーバーから全部の機器へ同時に送ります。", style = MaterialTheme.typography.labelSmall)
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && picked.isNotEmpty(), onClick = {
                val g = g0.copy(name = name.trim(), icon = icon, action = action, entities = picked.toList())
                vm.saveGroups(if (groups.any { it.id == g.id }) groups.map { if (it.id == g.id) g else it } else groups + g)
                onDismiss()
            }) { Text("保存") }
        },
        dismissButton = {
            Row {
                if (groups.any { it.id == g0.id }) TextButton(onClick = { vm.saveGroups(groups.filter { it.id != g0.id }); onDismiss() }) {
                    Text("削除", color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = onDismiss) { Text("やめる") }
            }
        },
    )
}

// ======================= 30分ごとの電力（デマンド） =======================

/** 電気の分析の「30分ごと（上限の見張り）」 */
@Composable
fun DemandView(vm: MainViewModel) {
    val today = LocalDate.now()
    var day by remember { mutableStateOf(today) }
    var data by remember { mutableStateOf<JSONObject?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }
    val conf by vm.serverConf.collectAsState()
    LaunchedEffect(day, reload, conf) {
        err = null
        try { data = vm.demand(day.toString()) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { err = e.message }
    }
    val chip = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primary, selectedLabelColor = MaterialTheme.colorScheme.onPrimary)
    Text("30分ごとの電力（30分間の平均。電力会社の「デマンド」と同じ数え方）", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        (0..13).forEach { i ->
            val d = today.minusDays(i.toLong())
            FilterChip(selected = day == d, onClick = { day = d }, label = { Text(when (i) { 0 -> "今日"; 1 -> "昨日"; else -> "${d.monthValue}/${d.dayOfMonth}" }) }, colors = chip)
        }
    }
    err?.let { Text("読めませんでした：$it", style = MaterialTheme.typography.bodySmall) }
    val j = data ?: return
    val target = j.optString("target", "buy")
    val limit = j.optDouble("limit_kw", Double.NaN)
    val margin = j.optDouble("margin_kw", 0.5)
    val warn = if (limit.isNaN()) Double.NaN else limit - margin
    val slots = j.optJSONArray("slots")
    val byT = HashMap<String, Double>()
    for (i in 0 until (slots?.length() ?: 0)) {
        val s = slots!!.optJSONObject(i) ?: continue
        byT[s.optString("t")] = s.optDouble(target, 0.0) * 2 / 1000
    }
    val keys = (0 until 48).map { String.format("%02d:%02d", it / 2, it % 2 * 30) }
    val vals = keys.map { byT[it] }
    val dayMax = vals.filterNotNull().maxOrNull()
    Text(
        (if (target == "buy") "買った電気" else "使った電気") + "　この日の最大：" + (dayMax?.let { String.format("%.2fkW", it) } ?: "—") +
            (if (!limit.isNaN()) "　上限：${String.format("%.2f", limit)}kW（${j.optString("limit_how")}）　注意：${String.format("%.2f", warn)}kW" else "　上限：${j.optString("limit_how")}"),
        style = MaterialTheme.typography.bodySmall,
    )
    if (vals.all { it == null }) {
        Text("この日の30分ごとの記録はまだありません（サーバーを新しい版にした時点からたまります。直近12か月の最大も、記録がたまるほど正確になります）。", style = MaterialTheme.typography.bodySmall)
    } else {
        val bar = MaterialTheme.colorScheme.primary
        val err2 = MaterialTheme.colorScheme.error
        val grid = MaterialTheme.colorScheme.outlineVariant
        val top = listOfNotNull(dayMax, limit.takeIf { !it.isNaN() }).maxOrNull()!!.coerceAtLeast(0.5) * 1.1
        val measurer = androidx.compose.ui.text.rememberTextMeasurer()
        val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
        Canvas(Modifier.fillMaxWidth().height(200.dp)) {
            val left = 34f
            val bottom = size.height - 18f
            val w = (size.width - left) / 48f
            val y = { v: Double -> (bottom - (v / top) * (bottom - 4f)).toFloat() }
            listOf(0.0, top / 2, top / 1.1).forEach { g ->
                drawLine(grid, Offset(left, y(g)), Offset(size.width, y(g)), 1f)
                drawText(measurer, String.format("%.1f", g), Offset(0f, y(g) - 14f), androidx.compose.ui.text.TextStyle(fontSize = 9.sp, color = labelColor))
            }
            vals.forEachIndexed { i, v ->
                if (v == null) return@forEachIndexed
                val c = if (!warn.isNaN() && v > warn) err2 else bar
                drawRect(c, Offset(left + i * w + w * 0.12f, y(v)), Size(w * 0.76f, bottom - y(v)))
            }
            for (h in 0..24 step 6) {
                drawText(measurer, "${h}時", Offset(left + h * 2 * w - 8f, bottom + 2f), androidx.compose.ui.text.TextStyle(fontSize = 9.sp, color = labelColor))
            }
            if (!limit.isNaN()) {
                drawLine(err2, Offset(left, y(limit)), Offset(size.width, y(limit)), 2f)
                drawLine(Color(0xFFFFA726), Offset(left, y(warn)), Offset(size.width, y(warn)), 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
            }
        }
        Text("赤い線：上限　橙の点線：注意（上限 − ${String.format("%.1f", margin)}kW）。注意を超えた30分は赤い棒です。", style = MaterialTheme.typography.labelSmall)
    }
    j.optJSONObject("months")?.let { ms ->
        val ks = ms.keys().asSequence().sorted().toList()
        if (ks.isNotEmpty()) {
            Text("月ごとの最大（30分の電力）", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ks.forEach { k ->
                    val m = ms.optJSONObject(k) ?: return@forEach
                    Text("${k.substring(2).replace('-', '/')}：${String.format("%.2f", m.optDouble(target))}kW", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    DemandSettings(vm)
}

@Composable
private fun DemandSettings(vm: MainViewModel) {
    val conf by vm.serverConf.collectAsState()
    val enabled = conf["demand_enabled"] == true
    val target = conf["demand_target"] as? String ?: "buy"
    val margin = (conf["demand_margin_kw"] as? Number)?.toDouble() ?: 0.5
    val limit = (conf["demand_limit_kw"] as? Number)?.toDouble() ?: 0.0
    val notifyArr = conf["demand_notify"] as? org.json.JSONArray
    val notify = (0 until (notifyArr?.length() ?: 0)).map { notifyArr!!.optString(it) }.toSet()
    var services by remember { mutableStateOf<List<String>?>(null) }
    var limitText by remember(limit) { mutableStateOf(if (limit > 0) limit.toString() else "") }
    LaunchedEffect(Unit) { services = vm.notifyServices() }
    val chip = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primary, selectedLabelColor = MaterialTheme.colorScheme.onPrimary)
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("🔔 上限に近づいたらスマートフォンに知らせる", style = MaterialTheme.typography.titleSmall)
                    Text("いまの30分が、このまま使い続けると「上限 − 余裕」を超えそうなとき、30分に1回だけ知らせます（30分の初めの5分は様子を見ます）。", style = MaterialTheme.typography.labelSmall)
                }
                Switch(checked = enabled, onCheckedChange = { vm.setDemand(enabled = it) })
            }
            Text("見張る電気", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = target == "buy", onClick = { vm.setDemand(target = "buy") }, label = { Text("買った電気（発電の分を引く・おすすめ）") }, colors = chip)
                FilterChip(selected = target == "use", onClick = { vm.setDemand(target = "use") }, label = { Text("使った電気（家全体）") }, colors = chip)
            }
            Text("余裕（上限からどれだけ手前で知らせるか）", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0.3, 0.5, 1.0).forEach { m -> FilterChip(selected = margin == m, onClick = { vm.setDemand(marginKw = m) }, label = { Text("${m}kW") }, colors = chip) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(limitText, { limitText = it }, label = { Text("上限（kW）。空なら直近12か月の最大") }, singleLine = true, modifier = Modifier.weight(1f))
                TextButton(onClick = { vm.setDemand(limitKw = limitText.toDoubleOrNull()?.coerceIn(0.0, 100.0) ?: 0.0) }) { Text("保存") }
            }
            HeatGuardSettings(vm)
            Text("知らせる先（Home Assistant のスマートフォンのアプリ）", style = MaterialTheme.typography.labelLarge)
            when {
                services == null -> Text("読んでいます…", style = MaterialTheme.typography.bodySmall)
                services!!.isEmpty() -> Text("通知先が見つかりません。スマートフォンに Home Assistant のアプリを入れてログインすると「notify.mobile_app_〇〇」が出てきます（サーバーに Home Assistant の鍵の登録も必要です）。", style = MaterialTheme.typography.bodySmall)
                else -> services!!.filter { it.startsWith("notify.mobile_app") || it in notify }.ifEmpty { services!! }.forEach { s ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = s in notify, onCheckedChange = { c -> vm.setDemand(notify = if (c) (notify + s).toList() else (notify - s).toList()) })
                        Text(s.removePrefix("notify.mobile_app_").let { if (it == s) s else "📱 $it" }, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}


/** 買電が多いときはお湯を沸かさない */
@Composable
fun HeatGuardSettings(vm: MainViewModel) {
    val conf by vm.serverConf.collectAsState()
    val live by vm.live.collectAsState()
    val on = conf["heat_guard"] != false
    val kw = (conf["heat_guard_kw"] as? Number)?.toDouble() ?: 0.0
    val g = live?.guard
    val chip = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primary, selectedLabelColor = MaterialTheme.colorScheme.onPrimary)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("⏸ 買電が多いときはお湯を沸かさない", style = MaterialTheme.typography.titleSmall)
            Text("買電が目安以上になったとき（または30分の電力が注意の値を超えそうなとき）に、エコキュートの沸き上げを一時停止し、下がったら元に戻します。" +
                "夜の沸き上げにも効きます。お湯が少ないとき（20%未満）は止めず、止めるのは長くても1時間です。", style = MaterialTheme.typography.labelSmall)
        }
        Switch(checked = on, onCheckedChange = { vm.setServerConf("heat_guard", it) })
    }
    if (!on) return
    Text("目安（買電）：" + if (kw > 0) "${numText(kw)}kW" else "自動（${g?.optDouble("level_kw")?.takeIf { !it.isNaN() }?.let { numText(Math.round(it * 10) / 10.0) + "kW・" } ?: ""}${g?.optString("level_how") ?: "30分の電力の注意の値"}）",
        style = MaterialTheme.typography.bodySmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(0.0 to "自動", 2.0 to "2kW", 3.0 to "3kW", 4.0 to "4kW", 5.0 to "5kW", 6.0 to "6kW").forEach { (v, l) ->
            FilterChip(selected = kw == v, onClick = { vm.setServerConf("heat_guard_kw", v) }, label = { Text(l) }, colors = chip)
        }
    }
    if (g?.optBoolean("active") == true) Text("いま一時停止しています（${g.optString("reason")}）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
}


/** 回路ごとのいまの電力（電気の分析の上）。200V の回路（エコキュート・エアコンなど）は、1日の使用量の増え方から出した5〜20分の平均 */
@Composable
fun CircuitsNow(vm: MainViewModel) {
    DisposableEffect(Unit) {
        vm.watchLive(true)
        onDispose { vm.watchLive(false) }
    }
    val l by vm.live.collectAsState()
    val list = l?.circuits?.filter { it.second >= 1 } ?: return
    if (list.isEmpty()) return
    val max = list.maxOf { it.second }.coerceAtLeast(100.0)
    val bar = MaterialTheme.colorScheme.primary
    val est = MaterialTheme.colorScheme.tertiary
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("いまの回路ごとの電力（10秒ごと）", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            list.take(12).forEach { (n, w, e) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(n + if (e) "＊" else "", style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(140.dp), maxLines = 1)
                    Canvas(Modifier.weight(1f).height(12.dp)) {
                        drawRect(if (e) est else bar, size = Size((size.width * (w / max)).toFloat().coerceAtLeast(2f), size.height))
                    }
                    Text(if (w >= 1000) String.format("%.2fkW", w / 1000) else "${Math.round(w)}W", style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(64.dp))
                }
            }
            if (list.any { it.third }) Text("＊ は、AiSEG2 にいまの電力がまだ一度も出ていない回路です。1日の使用量の増え方から出した直近5〜20分の平均を出しています（いまの電力が出た回路は、その値を使います）。",
                style = MaterialTheme.typography.labelSmall)
        }
    }
}
