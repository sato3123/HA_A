package jp.home.panel

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** ホーム画面の「今日の電気」 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EnergyStrip(vm: MainViewModel, onOpen: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    val r by vm.energy.collectAsState()
    val status by vm.energyStatus.collectAsState()
    val live by vm.elLive.collectAsState()
    val report = r
    if (live.buyW != null || live.solarW != null || live.batteryPct != null) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(horizontal = 4.dp)) {
            Text("🔌 いま", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            live.buyW?.let { Stat(if (it >= 0) "買電" else "売電", "${kotlin.math.abs(it)} W") }
            live.solarW?.let { Stat("発電", "$it W") }
            live.batteryPct?.let { Stat("蓄電池", "$it%") }
        }
    }
    val cost by vm.serverCost.collectAsState()
    cost?.let { c ->
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(horizontal = 4.dp).clickable { onOpen() }) {
            Text("💴 電気代", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Stat("今日", yenText(c, "today_yen"))
            Stat("今月", yenText(c, "month_yen"))
            Stat("見込み", yenText(c, "month_estimate_yen"))
            if (!c.isNull("rate_now") && c.has("rate_now")) Stat("いまの単価", "${c.optDouble("rate_now")}円/kWh")
            if (c.optLong("today_sell_yen") > 0) Stat("売電", yenText(c, "today_sell_yen"))
        }
    }
    if (report == null) {
        status?.let { Text("⚡ $it", style = MaterialTheme.typography.bodySmall) }
        return@Column
    }
    val t = report.today
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth().clickable { onOpen() },
    ) {
        FlowRow(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("⚡ 今日の電気", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Stat("使用", kwh(t.used))
            Stat("買電", kwh(t.gridIn))
            if (report.hasExport) Stat("売電", kwh(t.gridOut))
            if (report.hasSolar) Stat("発電", kwh(t.solar))
            if (report.hasSolar || report.hasBattery) t.selfRate?.let { Stat("自給率", String.format("%.0f%%", it)) }
            t.cost?.let { Stat("電気代", String.format("%,.0f円", it)) }
        }
    }
    }
}

private fun yenText(c: org.json.JSONObject, k: String) = if (!c.has(k) || c.isNull(k)) "－" else String.format("%,d円", c.optLong(k))

@Composable
private fun Stat(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

/** 今日・昨日・今月の収支と、時間ごとのグラフ、よく使っている機器 */
@Composable
fun EnergyDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    val r by vm.energy.collectAsState()
    val states by vm.allStates.collectAsState()
    val report = r ?: return
    var analysis by remember { mutableStateOf(false) }
    if (analysis) { EnergyAnalysisDialog(vm) { analysis = false }; return }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("⚡ 電気の収支") },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // 表：今日・昨日・今月
                val cols = listOf("今日" to report.today, "昨日" to report.yesterday, "今月" to report.month)
                Row {
                    Text("", modifier = Modifier.width(84.dp))
                    cols.forEach { (n, _) -> Text(n, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)) }
                }
                listOfNotNull(
                    "使った電気" to { t: EnergyTotals -> kwh(t.used) },
                    "買った電気" to { t: EnergyTotals -> kwh(t.gridIn) },
                    if (report.hasExport) "売った電気" to { t: EnergyTotals -> kwh(t.gridOut) } else null,
                    if (report.hasSolar) "発電" to { t: EnergyTotals -> kwh(t.solar) } else null,
                    if (report.hasBattery) "蓄電池から" to { t: EnergyTotals -> kwh(t.batteryOut) } else null,
                    if (report.hasBattery) "蓄電池へ" to { t: EnergyTotals -> kwh(t.batteryIn) } else null,
                    if (report.hasSolar || report.hasBattery) "自給率" to { t: EnergyTotals -> t.selfRate?.let { String.format("%.0f%%", it) } ?: "－" } else null,
                    if (report.hasExport) "収支（売−買）" to { t: EnergyTotals -> (if (t.net >= 0) "+" else "") + kwh(t.net) } else null,
                    if (report.today.cost != null) "電気代" to { t: EnergyTotals -> t.cost?.let { String.format("%,.0f円", it) } ?: "－" } else null,
                ).forEach { (label, f) ->
                    Row {
                        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(84.dp))
                        cols.forEach { (_, t) -> Text(f(t), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f)) }
                    }
                }

                HorizontalDivider()
                Text("今日の時間ごとの使用量", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                val bars = report.hourlyUsed
                val max = (bars.maxOrNull() ?: 0.0).coerceAtLeast(0.01)
                val color = MaterialTheme.colorScheme.primary
                val now = java.time.LocalTime.now().hour
                Canvas(Modifier.fillMaxWidth().height(110.dp)) {
                    val bw = size.width / 24f
                    bars.forEachIndexed { i, v ->
                        val h = (v / max).toFloat() * (size.height - 4f)
                        drawRoundRect(
                            color.copy(alpha = if (i == now) 1f else 0.6f),
                            topLeft = Offset(i * bw + bw * 0.15f, size.height - h),
                            size = Size(bw * 0.7f, h),
                            cornerRadius = CornerRadius(bw * 0.2f),
                        )
                    }
                }
                Row(Modifier.fillMaxWidth()) {
                    listOf("0時", "6時", "12時", "18時", "24時").forEachIndexed { i, s ->
                        Text(s, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(if (i == 4) 0.5f else 1f))
                    }
                }
                Text("いちばん多い時間：${bars.indexOf(bars.maxOrNull() ?: 0.0)}時台（${kwh(bars.maxOrNull() ?: 0.0)}）", style = MaterialTheme.typography.bodySmall)

                if (report.topDevices.isNotEmpty()) {
                    HorizontalDivider()
                    Text("今日よく使っている機器", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    report.topDevices.forEach { (id, v) ->
                        val name = states[id]?.name ?: id.substringAfter('.')
                        Row {
                            Text(name, modifier = Modifier.weight(1f))
                            Text(kwh(v), fontWeight = FontWeight.Medium)
                        }
                    }
                }
                Text("※ Home Assistant の「エネルギー」の設定と記録をもとに計算しています（数分おきに更新）。", style = MaterialTheme.typography.labelSmall, fontSize = 11.sp)
                androidx.compose.material3.OutlinedButton(onClick = { analysis = true }) { Text("📊 回路別・時間別に分析（AiSEG2）") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("閉じる") } },
    )
}
