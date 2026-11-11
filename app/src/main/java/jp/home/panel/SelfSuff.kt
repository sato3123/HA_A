@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package jp.home.panel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject

private val GREEN = Color(0xFF66BB6A)

// ======================= ホーム画面：今日の自給率 =======================

/** 今日の自給率と目標（設定で「ホーム画面に出す」をオンにしたときだけ） */
@Composable
fun SelfRateLine(s: JSONObject?) {
    if (s == null || !s.optBoolean("show")) return
    val rate = if (s.isNull("rate")) null else s.optDouble("rate").takeIf { !it.isNaN() }
    val target = s.optInt("target", 0)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("🌱 今日の自給率", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(rate?.let { "${it.toInt()}%" } ?: "－", fontSize = 17.sp, fontWeight = FontWeight.Medium,
            color = if (rate != null && target > 0 && rate >= target) GREEN else MaterialTheme.colorScheme.onSurface)
        if (target > 0) {
            LinearProgressIndicator(
                progress = { ((rate ?: 0.0) / target).toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.weight(1f),
                color = GREEN,
            )
            Text("目標 $target%" + if (rate != null && rate >= target) " 達成" else "", style = MaterialTheme.typography.labelSmall)
        }
    }
}

// ======================= 設定：自給率を上げる（どれもオプション） =======================

private fun Map<String, Any?>.bool(k: String) = this[k] == true
private fun Map<String, Any?>.num(k: String, d: Double) = (this[k] as? Number)?.toDouble() ?: d
private fun Map<String, Any?>.strList(k: String): List<String> =
    (this[k] as? JSONArray)?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
private fun Map<String, Any?>.objList(k: String): List<JSONObject> =
    (this[k] as? JSONArray)?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } } ?: emptyList()

@Composable
private fun OptionCard(title: String, desc: String, on: Boolean, onToggle: (Boolean) -> Unit, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    Text(desc, style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = on, onCheckedChange = onToggle)
            }
            if (on) content()
        }
    }
}

@Composable
private fun ChoiceRow(label: String, values: List<Double>, current: Double, fmt: (Double) -> String, onPick: (Double) -> Unit) {
    val chip = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primary, selectedLabelColor = MaterialTheme.colorScheme.onPrimary)
    Text(label, style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        values.forEach { v -> FilterChip(selected = current == v, onClick = { onPick(v) }, label = { Text(fmt(v)) }, colors = chip) }
    }
}

private fun kwText(v: Double) = (if (v % 1.0 == 0.0) v.toInt().toString() else v.toString()) + "kW"

@Composable
private fun HoursRow(conf: Map<String, Any?>, prefix: String, dFrom: Int, dTo: Int, vm: MainViewModel) {
    val from = conf.num("${prefix}_from", dFrom.toDouble())
    val to = conf.num("${prefix}_to", dTo.toDouble())
    ChoiceRow("始める時刻", listOf(8.0, 9.0, 10.0, 11.0), from, { "${it.toInt()}時から" }) { vm.setServerConf("${prefix}_from", it.toInt()) }
    ChoiceRow("終わる時刻", listOf(14.0, 15.0, 16.0, 17.0), to, { "${it.toInt()}時まで" }) { vm.setServerConf("${prefix}_to", it.toInt()) }
}

@Composable
fun SelfSuffSettings(vm: MainViewModel) {
    val server by vm.shServer.collectAsState()
    val conf by vm.serverConf.collectAsState()
    val all by vm.allEntities.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "太陽光の電気をできるだけ家の中で使い、買う電気を減らすための機能です。どれも使うかどうかを選べます（初めはすべてオフ）。" +
                "家のサーバー（ホームパネルのサーバー）が見張って動かすので、このアプリを閉じていても働きます。",
            style = MaterialTheme.typography.bodySmall,
        )
        if (server.isBlank()) {
            Text("家のサーバーが登録されていません。「お湯（エコキュート）と太陽光」の設定で、サーバーを登録してください。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            return@Column
        }
        val needToken = conf["ha_token_set"] != true
        if (needToken) {
            Text("エアコン・機器を動かす機能には、サーバーに Home Assistant の鍵の登録が必要です（「エアコンの自動温度調整」の設定から登録できます）。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        // 1. ホーム画面に自給率
        OptionCard("🌱 ホーム画面に今日の自給率を出す", "自給率＝使った電気のうち、買わずに済んだ割合。目標と比べて表示します。",
            conf.bool("ss_show"), { vm.setServerConf("ss_show", it) }) {
            ChoiceRow("目標", listOf(30.0, 50.0, 70.0, 90.0), conf.num("ss_target_pct", 50.0), { "${it.toInt()}%" }) { vm.setServerConf("ss_target_pct", it.toInt()) }
        }

        // 2. いま使うとお得の知らせ
        OptionCard("☀ 電気が余っているときに「いま使うとお得」と知らせる", "昼に売電が続いたら、洗濯機・食器洗い機・乾燥機などを回すよう、スマートフォンに知らせます（2時間に1回まで）。",
            conf.bool("ss_notify"), { vm.setServerConf("ss_notify", it) }) {
            ChoiceRow("売電がこれ以上のとき", listOf(1.0, 1.5, 2.0, 3.0), conf.num("ss_notify_kw", 1.5), ::kwText) { vm.setServerConf("ss_notify_kw", it) }
            ChoiceRow("続いた時間", listOf(5.0, 10.0, 20.0), conf.num("ss_notify_min", 10.0), { "${it.toInt()}分" }) { vm.setServerConf("ss_notify_min", it.toInt()) }
            HoursRow(conf, "ss_notify", 9, 15, vm)
            val targets = conf.strList("demand_notify")
            Text(
                if (targets.isEmpty()) "知らせる先がまだありません。「お湯（エコキュート）と太陽光」→「30分ごとの電気の上限」の「知らせる先」で、スマートフォンを選んでください（同じ先に知らせます）。"
                else "知らせる先：" + targets.joinToString("、") { it.removePrefix("notify.mobile_app_") } + "（30分ごとの電気の上限と同じ先）",
                style = MaterialTheme.typography.bodySmall,
                color = if (targets.isEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // 3. 余剰でエアコンを先に動かす
        OptionCard("❄ 電気が余っているときに、エアコンを先に動かす", "動いているエアコンだけ、冷房は少し下げ・暖房は少し上げて、夕方に買う電気を減らします。買う電気が増えたら元の温度に戻します（人が温度を変えていたら、そのままにします）。エアコンを勝手に入れることはありません。",
            conf.bool("ss_aircon"), { vm.setServerConf("ss_aircon", it) }) {
            ChoiceRow("売電がこれ以上で10分続いたら", listOf(0.5, 1.0, 1.5, 2.0), conf.num("ss_aircon_kw", 1.0), ::kwText) { vm.setServerConf("ss_aircon_kw", it) }
            ChoiceRow("変える温度", listOf(0.5, 1.0, 1.5, 2.0), conf.num("ss_aircon_offset", 1.0), { "${it}℃" }) { vm.setServerConf("ss_aircon_offset", it) }
            HoursRow(conf, "ss_aircon", 10, 16, vm)
            Text("対象のエアコン", style = MaterialTheme.typography.labelLarge)
            val picked = conf.strList("ss_aircon_entities").toSet()
            val acs = all.filter { it.domain == "climate" && !it.entityId.startsWith("climate.el_") }
            if (acs.isEmpty()) Text("エアコンが見つかりません", style = MaterialTheme.typography.bodySmall)
            acs.forEach { e ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = e.entityId in picked, onCheckedChange = { c ->
                        vm.setServerConf("ss_aircon_entities", JSONArray((if (c) picked + e.entityId else picked - e.entityId).toList()))
                    })
                    Text(withRoom(vm.roomNameOf(e.entityId), e.name), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        // 4. 余剰で動かす機器
        OptionCard("🔌 電気が余っているときに動かす機器（電気自動車・蓄電池の充電など）", "売電が決めた量を超えて続いたら、選んだスイッチを入れます。買う電気が増えたら切ります（このしくみで入れたものだけ）。",
            conf.bool("ss_loads"), { vm.setServerConf("ss_loads", it) }) {
            ChoiceRow("続いた時間", listOf(5.0, 10.0, 20.0), conf.num("ss_loads_min", 10.0), { "${it.toInt()}分" }) { vm.setServerConf("ss_loads_min", it.toInt()) }
            HoursRow(conf, "ss_loads", 9, 16, vm)
            val items = conf.objList("ss_loads_items")
            val byId = items.associateBy { it.optString("entity") }
            fun save(list: List<JSONObject>) = vm.setServerConf("ss_loads_items", JSONArray(list))
            Text("動かす機器（スイッチ）", style = MaterialTheme.typography.labelLarge)
            val sws = all.filter { it.domain == "switch" || it.domain == "input_boolean" }
                .sortedWith(compareBy({ it.entityId !in byId }, { it.name }))
            if (sws.isEmpty()) Text("スイッチが見つかりません", style = MaterialTheme.typography.bodySmall)
            sws.take(40).forEach { e ->
                val it0 = byId[e.entityId]
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = it0 != null, onCheckedChange = { c ->
                            save(if (c) items + JSONObject().put("entity", e.entityId).put("name", e.name).put("on_kw", 1.5).put("off_kw", 0.3)
                            else items.filter { x -> x.optString("entity") != e.entityId })
                        })
                        Text(withRoom(vm.roomNameOf(e.entityId), e.name), style = MaterialTheme.typography.bodyMedium)
                    }
                    if (it0 != null) {
                        Column(Modifier.padding(start = 48.dp)) {
                            ChoiceRow("売電がこれ以上で入れる（機器の消費電力くらい）", listOf(1.0, 1.5, 3.0, 6.0), it0.optDouble("on_kw", 1.5), ::kwText) { v ->
                                save(items.map { x -> if (x.optString("entity") == e.entityId) JSONObject(x.toString()).put("on_kw", v) else x })
                            }
                            ChoiceRow("買電がこれ以上で切る", listOf(0.1, 0.3, 0.5, 1.0), it0.optDouble("off_kw", 0.3), ::kwText) { v ->
                                save(items.map { x -> if (x.optString("entity") == e.entityId) JSONObject(x.toString()).put("off_kw", v) else x })
                            }
                        }
                    }
                }
            }
        }

        // 5. くもりの日のお湯
        OptionCard("⛅ くもりの日も、昼に沸かせる分だけ夜の沸き上げを減らす", "晴れない予報の日でも、日射量の予報から昼に沸かせる割合（30%以上）を見積もり、その分だけ夜の沸き上げを減らします。" +
            "「夜の沸き上げの減らし方」が「夜は止める」のときに働きます。足りなければ夕方の準備で沸かします。",
            conf.bool("ss_partial"), { vm.setServerConf("ss_partial", it) }) {
            if (conf["night_method"] != "stop") {
                Text("いまは「夜は止める」になっていないため、働きません（「お湯（エコキュート）と太陽光」の設定で選べます）。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

// ======================= はじめての設定（ホーム画面の案内） =======================

/** 初めて使う人向けの、4つの手順の案内。開くと設定のそのページへ */
@Composable
fun SetupGuideCard(vm: MainViewModel, onOpen: (String) -> Unit) {
    val done by vm.setupGuideDone.collectAsState()
    if (done) return
    val sel by vm.selection.collectAsState()
    val rooms by vm.rooms.collectAsState()
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("👋 はじめての設定（4つの手順）", style = MaterialTheme.typography.titleMedium)
            Text("順番に開いて設定すると、すぐに使い始められます。あとから ⚙ 設定 でいつでも変えられます。", style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            listOf(
                Triple("rooms", "① 部屋を決める", rooms.isNotEmpty()),
                Triple("rooms", "② ホーム画面に出す機器を選ぶ", sel != null),
                Triple("voice", "③ 自宅の位置（天気・日の出の計算）", false),
                Triple("tariff", "④ 電気料金のプラン（電気代の計算）", false),
            ).forEach { (page, label, ok) ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 40.dp)) {
                    Text((if (ok) "✅ " else "") + label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    FilledTonalButton(onClick = { onOpen(page) }) { Text("開く") }
                }
            }
            Row {
                TextButton(onClick = { vm.setSimpleMode(true) }) { Text("家族用に「かんたん表示」にする") }
                androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                TextButton(onClick = { vm.finishSetupGuide() }) { Text("終わった（もう出さない）") }
            }
        }
    }
}

// ======================= かんたん表示（大きなボタンだけ） =======================

/** かんたん表示の大きなボタン：タップで入／切（エアコンは運転／停止） */
@Composable
fun SimpleTile(e: Entity, vm: MainViewModel) {
    val on = e.isOn
    Card(
        onClick = { vm.primaryAction(e) },
        enabled = !e.unavailable,
        modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp),
        colors = CardDefaults.cardColors(containerColor = if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                when (e.domain) { "light" -> "💡"; "climate" -> "❄"; "fan" -> "🌀"; "cover" -> "🪟"; "scene", "script" -> "▶"; else -> "🔌" },
                fontSize = 28.sp,
            )
            Text(withRoom(vm.roomNameOf(e.entityId), e.name), style = MaterialTheme.typography.titleMedium, maxLines = 2)
            Text(
                if (e.unavailable) "つながっていません" else when (e.domain) {
                    "scene", "script" -> "タップで実行"
                    "cover" -> if (e.state == "open") "開いています" else "閉じています"
                    else -> if (on) "ついています（タップで消す）" else "消えています（タップでつける）"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** かんたん表示で出す機器（照明・スイッチ・エアコン・扇風機・シーン・カーテン） */
fun simpleDomains() = setOf("light", "switch", "input_boolean", "climate", "fan", "scene", "script", "cover")

// ======================= Home Assistant へのつなぎ直し（エアコンに「名乗って」と頼む） =======================

/**
 * 家のサーバーが数分ごとに、ECHONET Lite のエアコンへ「名乗って」と頼む様子と設定。
 * 全員あての呼びかけを聞き逃すエアコンが、Home Assistant（HEMS Echonet Lite）の一覧から消えたままになるのを防ぐ。
 * 頼むのは読み取り用の依頼だけで、運転は変えない
 */
@Composable
fun ElRescueCard(vm: MainViewModel) {
    val server by vm.shServer.collectAsState()
    val conf by vm.serverConf.collectAsState()
    val st by vm.elRescue.collectAsState()
    val devs by vm.elDevices.collectAsState()
    var ipText by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf("") }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (server.isBlank()) {
                Text("家のサーバーが登録されていません（「お湯（エコキュート）と太陽光」で登録できます）", style = MaterialTheme.typography.bodySmall)
                return@Column
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("エアコンに数分ごとに「名乗って」と頼む", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Home Assistant の全員あての呼びかけを聞き逃すエアコンが、一覧から消えて「利用不可」のままになるのを防ぎます。" +
                            "家のサーバーが IP アドレスあてに頼むだけで、運転・温度は変えません。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = conf["el_rescue"] != false, onCheckedChange = { vm.setServerConf("el_rescue", it) })
            }
            val j = st
            if (j == null) {
                Text("サーバーの様子を読んでいます（サーバーが新しい版になるまで、数分かかることがあります）", style = MaterialTheme.typography.bodySmall)
            } else {
                fun names(a: org.json.JSONArray?): List<String> = a?.let { x -> (0 until x.length()).map { x.optString(it) } } ?: emptyList()
                val targets = names(j.optJSONArray("targets"))
                val sent = names(j.optJSONArray("sent")).toSet()
                val at = j.optDouble("at", Double.NaN)
                Text(
                    if (targets.isEmpty()) "頼む相手のエアコンがまだありません。上でエアコンを見つけるか、下に IP アドレスを入れてください。"
                    else "頼む相手：",
                    style = MaterialTheme.typography.labelLarge,
                )
                targets.forEach { ip ->
                    val name = devs.firstOrNull { it.ip == ip && it.eoj.classCode == 0x0130 }?.let { withRoom(vm.roomNameOf(it.entityId), it.name) }
                    Text(
                        "・" + (name?.let { "$it（$ip）" } ?: ip) + if (ip in sent) "　✓ 頼みました" else "",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (!at.isNaN()) {
                    val t = java.time.Instant.ofEpochMilli((at * 1000).toLong()).atZone(java.time.ZoneId.systemDefault())
                    Text("最後に頼んだ時刻：${t.hour}:${String.format("%02d", t.minute)}（${j.optDouble("every_min", 3.0).toInt()}分ごと）",
                        style = MaterialTheme.typography.bodySmall)
                }
                if (!j.isNull("error") && j.optString("error").isNotBlank()) {
                    Text("うまく送れなかった相手：" + j.optString("error"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
            HorizontalDivider()
            Text("IP アドレスで足す（アプリで見つからないエアコンなど）", style = MaterialTheme.typography.labelLarge)
            val extra = conf.strList("el_rescue_ips")
            extra.forEach { ip ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(ip, modifier = Modifier.weight(1f))
                    TextButton(onClick = { vm.setServerConf("el_rescue_ips", JSONArray(extra - ip)) }) { Text("外す") }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.OutlinedTextField(
                    value = ipText, onValueChange = { ipText = it.filter { c -> c.isDigit() || c == '.' }.take(15) },
                    label = { Text("例：192.168.1.12") }, singleLine = true, modifier = Modifier.weight(1f),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                )
                val ok = Regex("""(192\.168|10\.\d{1,3})\.\d{1,3}\.\d{1,3}""").matches(ipText)
                FilledTonalButton(enabled = ok && ipText !in extra, onClick = {
                    vm.setServerConf("el_rescue_ips", JSONArray(extra + ipText))
                    ipText = ""
                }) { Text("足す") }
            }
        }
    }
}

// ======================= つけっぱなしのお知らせ =======================

private val RUN_ALERT_DOMAINS = setOf("climate", "switch", "light", "fan", "humidifier", "media_player", "input_boolean", "water_heater")

/** エアコンなどが決めた時間より長く続けて動いていたら、家のサーバーがスマートフォンに知らせる（1回の運転につき1回） */
@Composable
fun RunAlertSettings(vm: MainViewModel) {
    val server by vm.shServer.collectAsState()
    val conf by vm.serverConf.collectAsState()
    val all by vm.allEntities.collectAsState()
    val now by vm.runAlertNow.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "選んだ機器が、決めた時間より長く続けて動いていたら、スマートフォンに知らせます（1回の運転につき1回）。" +
                "家のサーバーが1分ごとに見張るので、このアプリを閉じていても働きます。",
            style = MaterialTheme.typography.bodySmall,
        )
        if (server.isBlank()) {
            Text("家のサーバーが登録されていません（「お湯（エコキュート）と太陽光」で登録できます）", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            return@Column
        }
        if (conf["ha_token_set"] != true) {
            Text("サーバーに Home Assistant の鍵の登録が必要です（「エアコンの自動温度調整」の設定から登録できます）。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        val targets = conf.strList("demand_notify")
        Text(
            if (targets.isEmpty()) "知らせる先がまだありません。「お湯（エコキュート）と太陽光」→「30分ごとの電気の上限」の「知らせる先」で、スマートフォンを選んでください（同じ先に知らせます）。"
            else "知らせる先：" + targets.joinToString("、") { it.removePrefix("notify.mobile_app_") },
            style = MaterialTheme.typography.bodySmall,
            color = if (targets.isEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val items = conf.objList("run_alerts")
        val byId = items.associateBy { it.optString("entity") }
        fun save(list: List<JSONObject>) = vm.setServerConf("run_alerts", JSONArray(list))
        val cands = all.filter { it.domain in RUN_ALERT_DOMAINS && !isEchonetEntity(it.entityId) }
            .sortedWith(compareBy({ it.entityId !in byId }, { it.domain != "climate" }, { vm.roomNameOf(it.entityId) ?: "" }, { it.name }))
        if (cands.isEmpty()) Text("機器が見つかりません", style = MaterialTheme.typography.bodySmall)
        cands.take(60).forEach { e ->
            val it0 = byId[e.entityId]
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = it0 != null, onCheckedChange = { c ->
                            val name = withRoom(vm.roomNameOf(e.entityId), e.name)
                            save(if (c) items + JSONObject().put("entity", e.entityId).put("name", name).put("hours", if (e.domain == "climate") 4 else 2)
                            else items.filter { x -> x.optString("entity") != e.entityId })
                        })
                        Column(Modifier.weight(1f)) {
                            Text(withRoom(vm.roomNameOf(e.entityId), e.name), style = MaterialTheme.typography.bodyMedium)
                            now?.optJSONObject(e.entityId)?.takeIf { it.optBoolean("running") }?.let { r ->
                                Text("いま ${String.format("%.1f", r.optDouble("hours"))}時間 続けて動いています", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                    if (it0 != null) {
                        ChoiceRow("この時間より長く動いていたら知らせる", listOf(1.0, 2.0, 3.0, 4.0, 6.0, 8.0, 12.0), it0.optDouble("hours", 2.0), { "${it.toInt()}時間" }) { v ->
                            save(items.map { x -> if (x.optString("entity") == e.entityId) JSONObject(x.toString()).put("hours", v) else x })
                        }
                    }
                }
            }
        }
    }
}
