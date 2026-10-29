@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package jp.home.panel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject

/** 設定画面：電気料金（電力会社とプランを選ぶと、サーバーが単価表から時間帯・季節ごとの単価で計算する） */
@Composable
fun TariffSettings(vm: MainViewModel) {
    val scope = rememberCoroutineScope()
    val sc by vm.serverConf.collectAsState()
    val cost by vm.serverCost.collectAsState()
    var list by remember { mutableStateOf<JSONObject?>(null) }
    var msg by remember { mutableStateOf<String?>("電力会社とプランの一覧を読んでいます…") }
    var reload by remember { mutableStateOf(0) }
    LaunchedEffect(reload) {
        try {
            list = vm.tariffList(); msg = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            msg = "ホームパネルのサーバー（${vm.shServer.value}）から読めませんでした：${e.message}"
        }
    }
    val chip = FilterChipDefaults.filterChipColors(
        selectedContainerColor = MaterialTheme.colorScheme.primary,
        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
    )
    val planId = sc["tariff_plan"] as? String ?: ""
    fun save(vararg kv: Pair<String, Any>) = scope.launch {
        try {
            vm.saveTariff(kv.toMap()); msg = "保存しました"; reload++
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            msg = "保存できませんでした：${e.message}"
        }
    }
    val providers = list?.optJSONArray("providers")
    var provider by remember(list, planId) {
        mutableStateOf(
            if (planId == "custom") "custom" else (0 until (providers?.length() ?: 0)).mapNotNull { providers?.optJSONObject(it) }
                .firstOrNull { p -> p.optJSONArray("plans")?.let { a -> (0 until a.length()).any { a.optJSONObject(it)?.optString("id") == planId } } == true }
                ?.optString("id") ?: "",
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "電力会社とプランを選ぶと、ホームパネルのサーバーが、買った電気の量（AiSEG2 の記録）に時間帯・季節・休日ごとの単価をかけて、電気料金を計算します。" +
                "単価表はサーバーが毎日新しくします（${list?.optString("updated")?.let { "単価表の更新日：$it" } ?: ""}）。",
            style = MaterialTheme.typography.bodySmall,
        )
        msg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        cost?.let { c ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(c.optString("plan"), style = MaterialTheme.typography.titleSmall)
                    Text("今日 ${yen(c, "today_yen")}（${c.optDouble("today_kwh")}kWh）・今月 ${yen(c, "month_yen")}（見込み ${yen(c, "month_estimate_yen")}）",
                        style = MaterialTheme.typography.bodyMedium)
                    if (!c.isNull("rate_now")) Text("いまの単価：${c.optDouble("rate_now")}円/kWh（${c.optString("band_now")}。再エネ賦課金・燃料費調整を含む）",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (providers != null) {
            Text("電力会社", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (i in 0 until providers.length()) {
                    val p = providers.optJSONObject(i) ?: continue
                    FilterChip(selected = provider == p.optString("id"), onClick = { provider = p.optString("id") }, label = { Text(p.optString("name")) }, colors = chip)
                }
                FilterChip(selected = provider == "custom", onClick = { provider = "custom" }, label = { Text("その他（単価を自分で入力）") }, colors = chip)
            }
            val prov = (0 until providers.length()).mapNotNull { providers.optJSONObject(it) }.firstOrNull { it.optString("id") == provider }
            prov?.let { p ->
                Text("プラン", style = MaterialTheme.typography.labelLarge)
                val plans = p.optJSONArray("plans")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (i in 0 until (plans?.length() ?: 0)) {
                        val pl = plans!!.optJSONObject(i) ?: continue
                        FilterChip(selected = planId == pl.optString("id"), onClick = { save("tariff_plan" to pl.optString("id")) },
                            label = { Text(pl.optString("name")) }, colors = chip)
                    }
                }
            }
            if (provider == "custom") CustomTariff(sc) { save("tariff_plan" to "custom", "tariff_custom" to it) }
        }
        // 選んでいるプランの単価と契約の大きさ
        list?.optJSONObject("current")?.let { cur ->
            val contract = cur.optString("contract")
            Text("いまのプランの単価（${cur.optString("from")}から）", style = MaterialTheme.typography.labelLarge)
            val bands = cur.optJSONArray("bands")
            for (i in 0 until (bands?.length() ?: 0)) {
                val b = bands!!.optJSONObject(i) ?: continue
                val r = b.opt("rate")
                val rate = if (r is JSONObject) r.keys().asSequence().joinToString("・") { k -> seasonName(k) + " " + r.optDouble(k) + "円" } else "${b.optDouble("rate")}円"
                Text("・${b.optString("name")}：$rate/kWh", style = MaterialTheme.typography.bodySmall)
            }
            Text("・基本料金（最低料金）：約${cur.optInt("basic_yen")}円/月", style = MaterialTheme.typography.bodySmall)
            Text("・再エネ賦課金：${list?.optDouble("surcharge")}円/kWh" +
                (list?.optDouble("subsidy")?.takeIf { it > 0 }?.let { "・国の補助：−${it}円/kWh（今月）" } ?: ""), style = MaterialTheme.typography.bodySmall)
            if (contract != "none") {
                val unit = when (contract) { "ampere" -> "A（アンペア）"; "kva" -> "kVA"; else -> "kW" }
                val opts = when (contract) { "ampere" -> listOf(10, 15, 20, 30, 40, 50, 60); "kva" -> listOf(6, 7, 8, 10, 12, 15); else -> listOf(5, 6, 8, 10, 12, 15) }
                val cur2 = (sc["tariff_contract"] as? Number)?.toInt() ?: 30
                Text("契約の大きさ：$cur2$unit（検針票の「契約」の欄）", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    opts.forEach { v -> FilterChip(selected = cur2 == v, onClick = { save("tariff_contract" to v) }, label = { Text("$v") }, colors = chip) }
                }
            }
        }
        // 燃料費調整額・売電単価
        var fuel by remember(sc["tariff_fuel"]) { mutableStateOf((sc["tariff_fuel"] as? Number)?.toDouble()?.let { fmtNum(it) } ?: "0") }
        var sell by remember(sc["tariff_sell"]) { mutableStateOf((sc["tariff_sell"] as? Number)?.toDouble()?.let { fmtNum(it) } ?: "0") }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(fuel, { fuel = it }, label = { Text("燃料費調整額（円/kWh）") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.widthIn(max = 220.dp))
            OutlinedTextField(sell, { sell = it }, label = { Text("売電の単価（円/kWh）") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.widthIn(max = 220.dp))
            Button(onClick = { save("tariff_fuel" to (fuel.toDoubleOrNull() ?: 0.0), "tariff_sell" to (sell.toDoubleOrNull() ?: 0.0)) }) { Text("保存") }
        }
        Text(
            "燃料費調整額は毎月変わり、電力会社のサイトや検針票に「燃料費調整単価」として出ています（マイナスのこともあります）。売電の単価は、太陽光の買取の契約の単価です。\n" +
                "※ 単価は各社の公式の料金表をもとにしていますが、実際の請求額と少しずれることがあります（ポイント・割引・端数の扱いなど）。料金表が変わったときは単価表を更新します。",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(onClick = { reload++ }) { Text("読み直す") }
    }
}

private fun seasonName(k: String) = when (k) { "summer" -> "夏"; "springautumn" -> "春秋"; "other" -> "その他の季節"; else -> k }

private fun fmtNum(d: Double) = if (d % 1.0 == 0.0) d.toInt().toString() else d.toString()

private fun yen(c: JSONObject, k: String) = if (c.isNull(k) || !c.has(k)) "－" else String.format("%,d円", c.optLong(k))

/** その他の電力会社：単価を自分で入力（昼と夜の2つまで） */
@Composable
private fun CustomTariff(sc: Map<String, Any?>, onSave: (JSONObject) -> Unit) {
    val c = sc["tariff_custom"] as? JSONObject ?: JSONObject()
    var basic by remember { mutableStateOf(c.optString("basic", "")) }
    var day by remember { mutableStateOf(c.optString("day_rate", "")) }
    var night by remember { mutableStateOf(c.optString("night_rate", "")) }
    var ns by remember { mutableStateOf(c.optString("night_start", "23")) }
    var ne by remember { mutableStateOf(c.optString("night_end", "7")) }
    val num = KeyboardOptions(keyboardType = KeyboardType.Decimal)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("検針票やプランの説明にある単価を入れてください（税込）。夜の単価が無いプランは、夜の欄を空にします。", style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedTextField(basic, { basic = it }, label = { Text("基本料金（円/月）") }, singleLine = true, keyboardOptions = num, modifier = Modifier.widthIn(max = 180.dp))
            OutlinedTextField(day, { day = it }, label = { Text("昼の単価（円/kWh）") }, singleLine = true, keyboardOptions = num, modifier = Modifier.widthIn(max = 180.dp))
            OutlinedTextField(night, { night = it }, label = { Text("夜の単価（円/kWh）") }, singleLine = true, keyboardOptions = num, modifier = Modifier.widthIn(max = 180.dp))
            OutlinedTextField(ns, { ns = it }, label = { Text("夜の始まり（時）") }, singleLine = true, keyboardOptions = num, modifier = Modifier.widthIn(max = 140.dp))
            OutlinedTextField(ne, { ne = it }, label = { Text("夜の終わり（時）") }, singleLine = true, keyboardOptions = num, modifier = Modifier.widthIn(max = 140.dp))
        }
        Button(onClick = {
            onSave(JSONObject().put("basic", basic.toDoubleOrNull() ?: 0.0).put("day_rate", day.toDoubleOrNull() ?: 0.0)
                .put("night_rate", night.toDoubleOrNull() ?: JSONObject.NULL)
                .put("night_start", ns.toIntOrNull() ?: 23).put("night_end", ne.toIntOrNull() ?: 7))
        }, enabled = day.toDoubleOrNull() != null) { Text("この単価で計算する") }
    }
}
