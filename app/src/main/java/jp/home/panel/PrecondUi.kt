@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package jp.home.panel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

// ======================= スマートフォンへのお知らせ（知らせる先・30分ごとの電気の上限） =======================

@Composable
fun NotifySettings(vm: MainViewModel) {
    val server by vm.shServer.collectAsState()
    val conf by vm.serverConf.collectAsState()
    val scope = rememberCoroutineScope()
    var testMsg by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HelpText(
            "家のサーバーから、Home Assistant のスマートフォンのアプリにお知らせを送ります。ここで選んだ先に、" +
                "30分ごとの電気の上限・「いま使うとお得」・つけっぱなし・エアコン（目標の時刻に快適に）のお知らせが届きます。",
            style = MaterialTheme.typography.bodySmall,
        )
        if (server.isBlank()) {
            Text("家のサーバーが登録されていません（「お湯（エコキュート）と太陽光」で登録できます）", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            return@Column
        }
        if (conf["ha_token_set"] != true) {
            Text("サーバーに Home Assistant の鍵の登録が必要です（「エアコンの自動温度調整」の設定から登録できます）。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("📱 知らせる先", style = MaterialTheme.typography.titleMedium)
                NotifyTargets(vm)
                OutlinedButton(onClick = { scope.launch { testMsg = vm.notifyTest() ?: "送りました。スマートフォンに届いたか確かめてください。" } }) { Text("ためしに送る") }
                testMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
        Text("30分ごとの電気の上限", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        DemandSettings(vm, withTargets = false)
    }
}

// ======================= 目標の時刻に快適に（エアコン） =======================

private val DAY_NAMES = listOf("月", "火", "水", "木", "金", "土", "日")

private fun rulesOf(conf: Map<String, Any?>): List<JSONObject> =
    (conf["precond_rules"] as? JSONArray)?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } } ?: emptyList()

private fun modeText(m: String) = when (m) { "cool" -> "冷房"; "heat" -> "暖房"; else -> "冷房・暖房を自動で" }

@Composable
fun PrecondSettings(vm: MainViewModel) {
    val server by vm.shServer.collectAsState()
    val conf by vm.serverConf.collectAsState()
    val all by vm.allEntities.collectAsState()
    var info by remember { mutableStateOf<JSONObject?>(null) }
    var editing by remember { mutableStateOf<JSONObject?>(null) }
    LaunchedEffect(server) {
        while (server.isNotBlank()) {
            info = runCatching { vm.precond() }.getOrNull()
            delay(30_000)
        }
    }
    val rules = rulesOf(conf)
    fun save(list: List<JSONObject>) = vm.setServerConf("precond_rules", JSONArray(list))
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HelpText(
            "「18時に26℃」のように、部屋を快適にしたい時刻と温度を決めると、家のサーバーが、外の気温の予報・いまの室温・時間帯ごとの電気の単価・" +
                "昼に余る電気の予報から、電気代がいちばん安くなる「つける時刻」と「設定温度」を15分ごとに試算して、エアコンをつけます。" +
                "余る電気があるうちは少し強め（冷房なら1〜2℃低め）に動かし、そのあと目標の温度に戻すこともあります。" +
                "つけたあとに人が操作したら、その日はもう触りません。目標の時刻が来ても切りません。すでに動いているエアコンには何もしません。",
            style = MaterialTheme.typography.bodySmall,
        )
        if (server.isBlank()) {
            Text("家のサーバーが登録されていません（「お湯（エコキュート）と太陽光」で登録できます）", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            return@Column
        }
        if (conf["ha_token_set"] != true) {
            Text("サーバーに Home Assistant の鍵の登録が必要です（「エアコンの自動温度調整」の設定から登録できます）。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        if (conf["tariff_plan"] == null || conf["tariff_plan"] == "") {
            HelpText("⚙ 設定 →「電気料金」で料金プランを選ぶと、時間帯ごとの単価で計算します（選んでいないときは、どの時間も同じ単価として計算します）。", style = MaterialTheme.typography.bodySmall)
        }
        val state = info?.optJSONObject("state")
        rules.forEach { r ->
            val st = state?.optJSONObject(r.optString("id"))
            val e = all.firstOrNull { it.entityId == r.optString("entity") }
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${r.optString("time")} に ${fmt(r.optDouble("temp"))}℃", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                            Text((e?.let { withRoom(vm.roomNameOf(it.entityId), it.name) } ?: r.optString("entity")) + "・" + modeText(r.optString("mode")) + "・" +
                                daysText(r.optJSONArray("days")), style = MaterialTheme.typography.bodyMedium)
                        }
                        Switch(checked = r.optBoolean("enabled", true), onCheckedChange = { on ->
                            save(rules.map { x -> if (x.optString("id") == r.optString("id")) JSONObject(x.toString()).put("enabled", on) else x })
                        }, modifier = Modifier.semantics { contentDescription = "${r.optString("time")} の設定を使う" })
                    }
                    st?.optString("status")?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
                    st?.optJSONObject("plan")?.let { PlanText(it) }
                    st?.optJSONArray("log")?.let { lg ->
                        (0 until minOf(3, lg.length())).forEach { i ->
                            val x = lg.optJSONArray(i) ?: return@forEach
                            val t = java.time.Instant.ofEpochSecond(x.optDouble(0).toLong()).atZone(java.time.ZoneId.systemDefault())
                            Text(String.format("%d/%d %02d:%02d　%s", t.monthValue, t.dayOfMonth, t.hour, t.minute, x.optString(1)), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { editing = JSONObject(r.toString()) }) { Text("✎ 変える") }
                    }
                }
            }
        }
        Button(onClick = {
            editing = JSONObject().put("id", java.util.UUID.randomUUID().toString().take(8)).put("time", "18:00").put("temp", 26.0)
                .put("mode", "auto").put("days", JSONArray(listOf(0, 1, 2, 3, 4, 5, 6))).put("early_min", 120).put("adjust", 1)
                .put("power_kw", 0.9).put("enabled", true).put("notify", false)
        }, modifier = Modifier.heightIn(min = 48.dp)) { Text("＋ 時刻と温度を追加") }
        info?.optJSONObject("learn")?.let { lr ->
            val lines = lr.keys().asSequence().mapNotNull { eid ->
                val o = lr.optJSONObject(eid) ?: return@mapNotNull null
                val name = all.firstOrNull { it.entityId == eid }?.name ?: eid
                listOf("cool" to "冷房", "heat" to "暖房").mapNotNull { (k, l) -> o.optJSONObject(k)?.let { "$l ${it.optDouble("rate")}℃/時（${it.optInt("n")}回）" } }
                    .takeIf { it.isNotEmpty() }?.let { "$name：" + it.joinToString("・") }
            }.toList()
            if (lines.isNotEmpty()) HelpText("学習した、部屋の温度が動く速さ：" + lines.joinToString("　"), style = MaterialTheme.typography.bodySmall)
        }
    }
    editing?.let { r ->
        PrecondEditor(vm, r, isNew = rules.none { it.optString("id") == r.optString("id") },
            onSave = { nr -> save(if (rules.any { it.optString("id") == nr.optString("id") }) rules.map { if (it.optString("id") == nr.optString("id")) nr else it } else rules + nr); editing = null },
            onDelete = { save(rules.filter { it.optString("id") != r.optString("id") }); editing = null },
            onDismiss = { editing = null })
    }
}

private fun daysText(a: JSONArray?): String {
    val d = (0 until (a?.length() ?: 0)).map { a!!.optInt(it) }.sorted()
    return when {
        d.size == 7 || d.isEmpty() -> "毎日"
        d == listOf(0, 1, 2, 3, 4) -> "平日"
        d == listOf(5, 6) -> "土日"
        else -> d.joinToString("・") { DAY_NAMES[it] }
    }
}

@Composable
private fun PlanText(p: JSONObject) {
    val steps = p.optJSONArray("steps") ?: return
    val parts = (0 until steps.length()).mapNotNull { steps.optJSONObject(it) }.mapIndexed { i, s ->
        "${s.optString("at").takeLast(5)} ${if (i == 0) "${hvacLabel(p.optString("mode"))} " else ""}${fmt(s.optDouble("temp"))}℃"
    }
    Text("計画：" + parts.joinToString(" → ") + "（室温 ${fmt(p.optDouble("tin"))}℃・外 ${fmt(p.optDouble("tout"))}℃）", style = MaterialTheme.typography.bodyMedium)
    val base = if (p.isNull("baseline_yen")) null else p.optDouble("baseline_yen")
    Text("見込み ${String.format("%.0f", p.optDouble("yen"))}円・${String.format("%.1f", p.optDouble("kwh"))}kWh" +
        (base?.let { b -> if (b - p.optDouble("yen") >= 1) "（1時間前に目標の温度でつけるより 約${String.format("%.0f", b - p.optDouble("yen"))}円 安い）" else "" } ?: "") +
        (if (p.optBoolean("late")) "　※ 室温が遠いので、目標の時刻に少し間に合わないかもしれません" else ""),
        style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun PrecondEditor(vm: MainViewModel, init: JSONObject, isNew: Boolean, onSave: (JSONObject) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    val all by vm.allEntities.collectAsState()
    var r by remember { mutableStateOf(JSONObject(init.toString())) }
    var preview by remember { mutableStateOf<JSONObject?>(null) }
    var pErr by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun set(k: String, v: Any) { r = JSONObject(r.toString()).put(k, v); preview = null }
    val chip = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primary, selectedLabelColor = MaterialTheme.colorScheme.onPrimary)
    val acs = all.filter { it.domain == "climate" && !isEchonetEntity(it.entityId) }.sortedBy { vm.roomNameOf(it.entityId) ?: "" }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "時刻と温度を追加" else "時刻と温度を変える") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("エアコン", style = MaterialTheme.typography.labelLarge)
                if (acs.isEmpty()) Text("エアコンが見つかりません", style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    acs.forEach { e ->
                        FilterChip(selected = r.optString("entity") == e.entityId, onClick = { set("entity", e.entityId); set("name", withRoom(vm.roomNameOf(e.entityId), e.name)) },
                            label = { Text(withRoom(vm.roomNameOf(e.entityId), e.name)) }, colors = chip)
                    }
                }
                // 時刻（15分きざみ）と温度（0.5℃きざみ）
                val (hh, mm) = r.optString("time", "18:00").split(":").map { it.toInt() }
                Stepper("時刻", r.optString("time"), "時刻を15分早く", "時刻を15分遅く",
                    onMinus = { val t = ((hh * 60 + mm - 15) + 1440) % 1440; set("time", String.format("%02d:%02d", t / 60, t % 60)) },
                    onPlus = { val t = (hh * 60 + mm + 15) % 1440; set("time", String.format("%02d:%02d", t / 60, t % 60)) })
                val temp = r.optDouble("temp", 26.0)
                Stepper("温度", "${fmt(temp)}℃", "温度を0.5℃下げる", "温度を0.5℃上げる",
                    onMinus = { set("temp", (temp - 0.5).coerceAtLeast(16.0)) }, onPlus = { set("temp", (temp + 0.5).coerceAtMost(30.0)) })
                Text("運転", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("auto" to "自動（気温で冷房・暖房）", "cool" to "冷房", "heat" to "暖房").forEach { (k, l) ->
                        FilterChip(selected = r.optString("mode") == k, onClick = { set("mode", k) }, label = { Text(l) }, colors = chip)
                    }
                }
                Text("曜日", style = MaterialTheme.typography.labelLarge)
                val days = (0 until (r.optJSONArray("days")?.length() ?: 0)).map { r.optJSONArray("days")!!.optInt(it) }.toSet()
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DAY_NAMES.forEachIndexed { i, n ->
                        FilterChip(selected = i in days, onClick = { set("days", JSONArray((if (i in days) days - i else days + i).sorted())) }, label = { Text(n) }, colors = chip)
                    }
                }
                ChoiceRow("いちばん早くて、何時間前からつけてよいか", listOf(60.0, 120.0, 180.0, 240.0), r.optDouble("early_min", 120.0), { "${(it / 60).toInt()}時間" }) { set("early_min", it.toInt()) }
                ChoiceRow("余る電気・安い時間に、目標より強めに動かしてよい幅", listOf(0.0, 1.0, 2.0), r.optDouble("adjust", 1.0), { if (it == 0.0) "変えない" else "${it.toInt()}℃まで" }) { set("adjust", it.toInt()) }
                ChoiceRow("エアコンが動き始めたときの消費電力（目安）", listOf(0.6, 0.9, 1.2, 1.5, 2.0), r.optDouble("power_kw", 0.9), { "${it}kW" }) { set("power_kw", it) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("つけたらスマートフォンに知らせる", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Switch(checked = r.optBoolean("notify"), onCheckedChange = { set("notify", it) })
                }
                OutlinedButton(enabled = !busy && r.optString("entity").isNotBlank(), onClick = {
                    busy = true; pErr = null
                    scope.launch {
                        try { preview = vm.precondPreview(r).optJSONObject("plan") } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { pErr = e.message }
                        busy = false
                    }
                }) { Text(if (busy) "試算しています…" else "いまの状態で試算する（エアコンは動かしません）") }
                preview?.let { PlanText(it) }
                pErr?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (!isNew) {
                    TextButton(onClick = { confirmDelete = true }) { Text("この設定を削除", color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = { TextButton(enabled = r.optString("entity").isNotBlank(), onClick = { onSave(r) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("やめる") } },
    )
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("この設定を削除しますか？") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("削除", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("やめる") } },
        )
    }
}

@Composable
private fun Stepper(label: String, value: String, minusDesc: String, plusDesc: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(56.dp))
        OutlinedButton(onClick = onMinus, modifier = Modifier.size(48.dp).semantics { contentDescription = minusDesc },
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("−") }
        Text(value, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 16.dp))
        OutlinedButton(onClick = onPlus, modifier = Modifier.size(48.dp).semantics { contentDescription = plusDesc },
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("＋") }
        Spacer(Modifier.weight(1f))
    }
}
