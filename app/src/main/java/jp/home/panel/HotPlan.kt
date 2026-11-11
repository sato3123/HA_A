@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package jp.home.panel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

private fun dayText(iso: String): String = runCatching {
    val d = LocalDate.parse(iso)
    val t = LocalDate.now()
    val w = "日月火水木金土"[d.dayOfWeek.value % 7]
    when (d) { t -> "今日"; t.plusDays(1) -> "明日"; else -> "${d.monthValue}/${d.dayOfMonth}($w)" }
}.getOrDefault(iso)

/** 「夜に使う予想 180L ＋ 余裕 50L ＋ 多めの日 100L ＝ 330L」 */
private fun needText(n: JSONObject): String {
    val pred = if (n.isNull("predicted")) null else n.optInt("predicted")
    val parts = listOfNotNull(
        pred?.let { "予想 ${it}L" },
        n.optInt("margin").takeIf { it > 0 }?.let { "余裕 ${it}L" },
        n.optInt("extra").takeIf { it > 0 }?.let { "多めの日 ${it}L" + (n.optString("note").takeIf { s -> s.isNotBlank() }?.let { s -> "（$s）" } ?: "") },
        n.optInt("loss").takeIf { it > 0 }?.let { "冷めて減る分 ${it}L" },
    )
    val need = if (n.isNull("need")) null else n.optInt("need")
    return parts.joinToString(" ＋ ") + (need?.let { " ＝ ${it}L" } ?: "") + if (pred == null) "（${n.optString("how")}）" else ""
}

private fun hm(sec: Double) = java.time.Instant.ofEpochMilli((sec * 1000).toLong()).atZone(java.time.ZoneId.systemDefault())
    .let { "${it.hour}:${String.format("%02d", it.minute)}" }

/** エコキュートのカード：今夜の沸き上げの予定（できるだけ遅く） */
@Composable
fun NightHeatSummary(vm: MainViewModel) {
    val hp by vm.hotPlan.collectAsState()
    val nh = hp?.optJSONObject("night_heat") ?: return
    val pl = nh.optJSONObject("plan") ?: JSONObject()
    val text = if (nh.optBoolean("started")) "🌙 夜の沸き上げ中（${nh.optInt("target")}L まで）"
    else "🌙 今夜は ${hm(nh.optDouble("start"))} から沸かします（約${pl.optInt("liters")}L・約${pl.optDouble("hours")}時間・目標 ${nh.optInt("target")}L）"
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
    if (!nh.optBoolean("started") && pl.has("yen")) {
        val save = pl.optDouble("saving_yen")
        Text("見込みの電気代 約${Math.round(pl.optDouble("yen"))}円" +
            (if (save >= 0.5) "（夜の始まりから沸かすより約${String.format("%.0f", save)}円安い）" else if (save <= -0.5) "（できるだけ遅くするため、いちばん安い時刻より約${String.format("%.0f", pl.optDouble("yen") - pl.optDouble("min_yen"))}円高い）" else "") +
            "・朝までに冷める熱 約${String.format("%.1f", pl.optDouble("loss_kwh"))}kWh・その時刻の外気温 ${pl.optDouble("t_out")}℃",
            style = MaterialTheme.typography.labelSmall)
    }
}

/** エコキュートのカード：今日の夕方の準備と、多めの日 */
@Composable
fun HotPlanSummary(vm: MainViewModel) {
    val conf by vm.serverConf.collectAsState()
    val hp by vm.hotPlan.collectAsState()
    if (conf["hot_auto"] != true) return
    val p = hp ?: return
    var dlg by remember { mutableStateOf(false) }
    if (dlg) HotExtraDialog(vm) { dlg = false }
    p.optJSONObject("evening")?.let { e ->
        Text("🛁 ${dayText(e.optString("date"))}の夕方（${e.optInt("from")}時）に残しておく（今夜の沸き上げ ${e.optInt("to").let { if (it > 24) "翌${it - 24}" else "$it" }}時まで使う分）：" + needText(e), style = MaterialTheme.typography.bodySmall)
    }
    p.optJSONObject("day_store")?.takeIf { it.optBoolean("on") && !it.isNull("target") }?.let { d ->
        Text("☀ 昼に余った電気では ${d.optInt("target")}L まで沸かします（${d.optString("how")}）", style = MaterialTheme.typography.bodySmall)
    }
    p.optJSONObject("prep")?.let { st ->
        when {
            st.optBoolean("heating") -> Text("♨ 夕方の準備のために沸かしています", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            st.optInt("short") > 0 && st.optString("start_at").isNotBlank() && !st.optBoolean("done") ->
                Text("今のままだと ${st.optInt("short")}L 足りないので、${st.optString("start_at")} ごろから沸かします（昼に余った電気で沸けば不要）", style = MaterialTheme.typography.bodySmall)
            else -> {}
        }
    }
    val ex = p.optJSONArray("extra")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (ex == null || ex.length() == 0) "お湯を多めに使う日はありません"
            else "多めの日：" + (0 until ex.length()).mapNotNull { ex.optJSONObject(it) }.joinToString("、") {
                "${dayText(it.optString("date"))}${if (it.optString("part") == "morning") "の朝" else "の夜"} +${it.optInt("liters")}L"
            },
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { dlg = true }) { Text("＋ 多めの日") }
    }
}

/** 設定：夜の沸き上げをできるだけ遅く（「夜は止める」の方式のとき） */
@Composable
fun NightLateSettings(vm: MainViewModel) {
    val conf by vm.serverConf.collectAsState()
    val on = conf["night_late"] != false
    val chip = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primary, selectedLabelColor = MaterialTheme.colorScheme.onPrimary)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("🌙 夜の沸き上げは、できるだけ朝に近い時刻にする", style = MaterialTheme.typography.labelLarge)
            HelpText("夜に沸かす日は、沸かしたお湯が朝までに冷める分（外気との温度差に比例）と、その時刻の電気料金・外気温でのヒートポンプの効率から、いちばん安く済む開始時刻を選びます。" +
                "差が1円以内なら遅いほうにします。朝の安い時間の終わりの15分前に沸き終わるようにします。", style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = on, onCheckedChange = { vm.setServerConf("night_late", it) })
    }
    if (!on) return
    val ne = (conf["night_end_hour"] as? Number)?.toInt() ?: 7
    Text("夜の安い時間の終わり：${ne}時", style = MaterialTheme.typography.bodySmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(5, 6, 7, 8).forEach { v -> FilterChip(ne == v, { vm.setServerConf("night_end_hour", v) }, { Text("${v}時") }, colors = chip) }
    }
    val tt = (conf["tank_temp"] as? Number)?.toInt() ?: 65
    Text("沸き上げの温度（冷める量の計算に使う）：${tt}℃", style = MaterialTheme.typography.bodySmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(60, 65, 70, 80, 90).forEach { v -> FilterChip(tt == v, { vm.setServerConf("tank_temp", v) }, { Text("${v}℃") }, colors = chip) }
    }
}

/** 設定：お湯の量を過去の使用量から自動で決める */
@Composable
fun HotPlanSettings(vm: MainViewModel, tank: Int?) {
    val conf by vm.serverConf.collectAsState()
    val hp by vm.hotPlan.collectAsState()
    val on = conf["hot_auto"] == true
    val chip = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primary, selectedLabelColor = MaterialTheme.colorScheme.onPrimary)
    var dlg by remember { mutableStateOf(false) }
    if (dlg) HotExtraDialog(vm) { dlg = false }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("お湯の量を過去の使用量から自動で決める", style = MaterialTheme.typography.labelLarge)
                HelpText(
                    "直近4週間の同じ種類の日（平日／土日祝）のお湯の使い方から、夜に使う量を予想して、夕方までに「予想＋余裕」のお湯を用意します。" +
                        "足りなければ、間に合う時刻から沸かします（昼に余った電気で沸けば沸かしません）。「夜は止める」のときは、夜に沸かす量も翌朝の予想＋余裕にします。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = on, onCheckedChange = { vm.setServerConf("hot_auto", it) })
        }
        if (!on) return@Column
        val margin = (conf["hot_margin_l"] as? Number)?.toInt() ?: 50
        Text("余裕（予想に足す量）：${margin}L", style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(0, 50, 100, 150, 200, 250, 300, 400).forEach { v -> FilterChip(margin == v, { vm.setServerConf("hot_margin_l", v) }, { Text("${v}L") }, colors = chip) }
        }
        // 一覧に無い量は数字で入れる（10L ずつ・最大 500L）
        NumField(margin.toDouble(), { vm.setServerConf("hot_margin_l", it.toInt()) }, unit = "L", step = 10.0, min = 0.0, max = 500.0)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("昼に電気が余ったら、翌日分も先に沸かす", style = MaterialTheme.typography.bodyMedium)
                HelpText("翌日分を昼に沸かすほうが電気代が安いとき（売れたはずの電気＋冷める分 ＜ 夜に買う電気）だけ、今夜の分＋翌日1日分の予想まで余った電気で沸かします。その分、夜の沸き上げが減ります。オフにすると満タン近くまで沸かします。",
                    style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = conf["hot_store_tomorrow"] != false, onCheckedChange = { vm.setServerConf("hot_store_tomorrow", it) })
        }
        val ph = (conf["prep_hour"] as? Number)?.toInt() ?: 17
        Text("何時までに用意するか（夕方・お風呂の前）：${ph}時", style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(15, 16, 17, 18, 19, 20).forEach { v -> FilterChip(ph == v, { vm.setServerConf("prep_hour", v) }, { Text("${v}時") }, colors = chip) }
        }
        val ns = (conf["night_start_hour"] as? Number)?.toInt() ?: 23
        Text("夜の沸き上げが始まる時刻（夜の安い時間の始まり。はぴｅタイムRは23時）：${ns}時", style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(21, 22, 23, 0).forEach { v -> FilterChip(ns == (if (v == 0) 24 else v), { vm.setServerConf("night_start_hour", if (v == 0) 24 else v) }, { Text("${v}時") }, colors = chip) }
        }
        hp?.let { p ->
            p.optJSONObject("evening")?.let { e ->
                Text("→ ${dayText(e.optString("date"))}：${e.optInt("from")}時〜${e.optInt("to").let { if (it > 24) "翌${it - 24}" else "$it" }}時（今夜の沸き上げまで）に使う " + needText(e) +
                    (if (!e.isNull("max")) "（多い日は ${e.optInt("max")}L・${e.optString("how")}）" else ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            p.optJSONObject("night")?.let { n ->
                Text("→ ${dayText(n.optString("date"))}の朝（0時〜${n.optInt("to")}時）に使う " + needText(n) +
                    (if (n.has("pct")) "：夜を減らす日は ${n.optInt("pct")}%" + (tank?.let { t -> "（約${t * n.optInt("pct") / 100}L）" } ?: "") + " まで沸かします" else ""),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            Text("沸かす速さ：約${p.optInt("rate_lph")}L/時（${p.optString("rate_how")}）。足りない量から、沸かし始める時刻を決めます。", style = MaterialTheme.typography.labelSmall)
            p.optJSONObject("learn")?.let { l -> TempLearnInfo(vm, l) }
        }
        OutlinedButton(onClick = { dlg = true }) { Text("🛁 多めにお湯を使う日を指定する") }
    }
}

/** 多めにお湯が必要な日（来客・大掃除など）を指定する */
@Composable
fun HotExtraDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    val conf by vm.serverConf.collectAsState()
    val arr = conf["hot_extra"] as? JSONArray
    val list = (0 until (arr?.length() ?: 0)).mapNotNull { arr!!.optJSONObject(it) }.filter { it.optString("date") >= LocalDate.now().toString() }
    val today = LocalDate.now()
    var day by remember { mutableStateOf(today) }
    var liters by remember { mutableStateOf(100) }
    var part by remember { mutableStateOf("evening") }
    var note by remember { mutableStateOf("") }
    val chip = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primary, selectedLabelColor = MaterialTheme.colorScheme.onPrimary)
    fun save(l: List<JSONObject>) = vm.setServerConf("hot_extra", JSONArray().apply { l.forEach { put(it) } })
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("🛁 多めにお湯を使う日") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (list.isNotEmpty()) {
                    list.forEach { x ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${dayText(x.optString("date"))}${if (x.optString("part") == "morning") "の朝" else "の夕方〜夜"} +${x.optInt("liters")}L" +
                                (x.optString("note").takeIf { it.isNotBlank() }?.let { "（$it）" } ?: ""), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = { save(list.filter { it !== x }) }) { Text("取り消す") }
                        }
                    }
                }
                Text("日", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (0..13).forEach { i ->
                        val d = today.plusDays(i.toLong())
                        FilterChip(day == d, { day = d }, { Text(dayText(d.toString())) }, colors = chip)
                    }
                }
                Text("いつ使うか", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(part == "evening", { part = "evening" }, { Text("夕方〜夜（お風呂など）") }, colors = chip)
                    FilterChip(part == "morning", { part = "morning" }, { Text("朝") }, colors = chip)
                }
                Text("ふだんより多く使う量", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(50, 100, 150, 200, 300).forEach { v -> FilterChip(liters == v, { liters = v }, { Text("+${v}L") }, colors = chip) }
                }
                OutlinedTextField(note, { note = it.take(30) }, label = { Text("メモ（例：来客・大掃除）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("※ その日の夕方（朝を選んだときは前の晩）に、予想＋余裕にこの量を足して用意します。浴槽のお湯はりは1回で約180〜200Lです。", style = MaterialTheme.typography.labelSmall)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                save(list + JSONObject().put("date", day.toString()).put("liters", liters).put("part", part).put("note", note.trim()))
                onDismiss()
            }) { Text("追加") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("閉じる") } },
    )
}


/** 気温の学習：寒い日のお湯の使い方・冷めて減る量 と、明日の予報での見込み */
@Composable
private fun TempLearnInfo(vm: MainViewModel, l: JSONObject) {
    val on = l.optBoolean("enabled", true)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("🌡 気温を見込んで沸かす量を決める", style = MaterialTheme.typography.labelLarge)
            HelpText("寒い日にお湯を多く使う度合いと、外気との温度差で冷めて減る量を記録から学び、翌日の気温の予報で予想を直します。", style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = on, onCheckedChange = { vm.setServerConf("hot_temp_learn", it) })
    }
    if (!on) return
    val perDeg = if (l.isNull("usage_per_deg")) null else l.optDouble("usage_per_deg")
    Text(
        (perDeg?.let { "・お湯の使い方：平均気温が1℃下がると、1日に約${String.format("%.0f", it)}L 多く使う（${l.optInt("usage_days")}日分から）" }
            ?: "・お湯の使い方：まだ学習中です（同じ種類の日が10日以上、気温の差が4℃以上たまると使います）") + "\n" +
            "・冷めて減る量：外気との差1℃あたり 1時間に約${String.format("%.3f", l.optDouble("loss_k"))}L（${l.optString("loss_how")}）。明日は1日で約${l.optInt("loss_day_l")}L" +
            (if (!l.isNull("tomorrow_t")) "\n・明日の予想の平均気温 ${l.optDouble("tomorrow_t")}℃ → 使う量の予想を ${String.format("%+.0f", (l.optDouble("tomorrow_factor", 1.0) - 1) * 100)}%" else ""),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
    )
}


/** 設定の下：家のサーバーのセットアップ（自動で実行し直す）の様子 */
@Composable
fun ServerSetupInfo(vm: MainViewModel) {
    val st by vm.serverSetup.collectAsState()
    val j = st ?: return
    val v = if (j.isNull("version")) null else j.optInt("version")
    val at = j.optDouble("auto_at", Double.NaN)
    Text(
        "家のサーバーのセットアップ：" + (v?.let { "版 $it" } ?: "版の記録なし（次の自動更新のときに実行し直します）") +
            (if (!at.isNaN()) "・最後に自動で実行：" + java.time.Instant.ofEpochMilli((at * 1000).toLong()).atZone(java.time.ZoneId.systemDefault())
                .let { "${it.monthValue}/${it.dayOfMonth} ${it.hour}:${String.format("%02d", it.minute)}" } + if (j.optBoolean("auto_ok")) "（成功）" else "（${j.optString("auto_last")}）" else "") +
            "。セットアップに新しい版があれば、サーバーが自分で実行し直すので、コマンドを入れる必要はありません。",
        style = MaterialTheme.typography.labelSmall,
    )
}
