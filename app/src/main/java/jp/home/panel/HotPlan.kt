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
    )
    val need = if (n.isNull("need")) null else n.optInt("need")
    return parts.joinToString(" ＋ ") + (need?.let { " ＝ ${it}L" } ?: "") + if (pred == null) "（${n.optString("how")}）" else ""
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
        Text("🛁 ${dayText(e.optString("date"))}の夕方（${e.optInt("from")}時）までに用意：" + needText(e), style = MaterialTheme.typography.bodySmall)
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
                Text(
                    "直近4週間の同じ種類の日（平日／土日祝）のお湯の使い方から、夜に使う量を予想して、夕方までに「予想＋余裕」のお湯を用意します。" +
                        "足りなければ、間に合う時刻から沸かします（昼に余った電気で沸けば沸かしません）。夜止めのときは、夜に沸かす量も翌朝の予想＋余裕にします。",
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
                Text("→ ${dayText(e.optString("date"))}：${e.optInt("from")}時〜${e.optInt("to")}時に使う " + needText(e) +
                    (if (!e.isNull("max")) "（多い日は ${e.optInt("max")}L・${e.optString("how")}）" else ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            p.optJSONObject("night")?.let { n ->
                Text("→ ${dayText(n.optString("date"))}の朝（0時〜${n.optInt("to")}時）に使う " + needText(n) +
                    (if (n.has("pct")) "：夜止めの日は ${n.optInt("pct")}%" + (tank?.let { t -> "（約${t * n.optInt("pct") / 100}L）" } ?: "") + " まで沸かします" else ""),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            Text("沸かす速さ：約${p.optInt("rate_lph")}L/時（${p.optString("rate_how")}）。足りない量から、沸かし始める時刻を決めます。", style = MaterialTheme.typography.labelSmall)
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
