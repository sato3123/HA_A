@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package jp.home.panel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

// ======================= ホーム：電気とお湯の要点だけ（くわしい情報は押すと開く） =======================
// 表示だけの部品。データの取り方・更新の間隔・操作は今までの部品（LiveStrip・EnergyStrip・EcoCuteCard）のまま。

private val GEN = Color(0xFF66BB6A)
private val USE = Color(0xFFFFB74D)
private val SELL = Color(0xFF42A5F5)
private val BUY = Color(0xFFEF5350)

/** ホーム画面の「電気とお湯」：大きな数字を3〜4つと、いちばん大事なことを1行だけ。押すと、くわしい情報 */
@Composable
fun EnergySummaryCard(vm: MainViewModel, onOpen: () -> Unit) {
    val server by vm.shServer.collectAsState()
    // いまの値（10秒ごと）は、このカードが見えている間だけ読む（今までの LiveStrip と同じしくみ）
    DisposableEffect(server) {
        val on = server.isNotBlank()
        if (on) vm.watchLive(true)
        onDispose { if (on) vm.watchLive(false) }
    }
    val live by vm.live.collectAsState()
    val report by vm.energy.collectAsState()
    val el by vm.elLive.collectAsState()
    val sh by vm.solarHeat.collectAsState()
    val cost by vm.serverCost.collectAsState()
    val awayUntil by vm.awayUntil.collectAsState()

    val kpis = mutableListOf<@Composable () -> Unit>()
    val f1 = { w: Double -> String.format("%.1f", kotlin.math.abs(w) / 1000) }
    val v = live
    if (v != null && (v.genW != null || v.useW != null || v.surplusW != null)) {
        v.genW?.let { kpis += { Kpi("☀ 発電", f1(it), "kW", GEN) } }
        v.useW?.let { kpis += { Kpi("🏠 消費", f1(it), "kW", USE) } }
        v.surplusW?.let { kpis += { if (it >= 0) Kpi("⚡ 売電", f1(it), "kW", SELL) else Kpi("⚡ 買電", f1(it), "kW", BUY) } }
    } else if (el.buyW != null || el.solarW != null || el.batteryPct != null) {
        el.solarW?.let { kpis += { Kpi("☀ 発電", f1(it.toDouble()), "kW", GEN) } }
        el.buyW?.let { kpis += { if (it >= 0) Kpi("⚡ 買電", f1(it.toDouble()), "kW", BUY) else Kpi("⚡ 売電", f1(it.toDouble()), "kW", SELL) } }
        el.batteryPct?.let { kpis += { Kpi("🔋 蓄電池", "$it", "%", null) } }
    } else report?.let { r ->
        kpis += { Kpi("🏠 今日の使用", String.format("%.1f", r.today.used), "kWh", null) }
        if (r.hasSolar) kpis += { Kpi("☀ 今日の発電", String.format("%.1f", r.today.solar), "kWh", GEN) }
        kpis += { Kpi("⚡ 今日の買電", String.format("%.1f", r.today.gridIn), "kWh", null) }
    }
    val remain = v?.remainL ?: sh?.remainL
    val tank = v?.tankL ?: sh?.tankL
    if (remain != null) {
        val pct = tank?.takeIf { it > 0 }?.let { (remain * 100 / it).coerceIn(0, 100) }
        kpis += { Kpi("🛁 お湯", pct?.toString() ?: remain.toString(), if (pct != null) "%" else "L", null, pct) }
    }
    val c = cost
    if (kpis.isEmpty() && c == null) return

    // いちばん大事なことを1行だけ（注意 → 自動で動いていること → 留守・沸き上げ中）
    val now = System.currentTimeMillis()
    fun recent(a: org.json.JSONObject?) = a != null && now - (a.optDouble("at") * 1000).toLong() < 30 * 60_000
    var warn = false
    val line: String? = run {
        v?.demand?.let { d ->
            val p = d.optDouble("projected_kw", Double.NaN); val w = d.optDouble("warn_kw", Double.NaN)
            if (!p.isNaN() && !w.isNaN() && p > w) { warn = true; return@run "30分の電力がこのままだと ${String.format("%.1f", p)}kW（注意 ${String.format("%.1f", w)}kW）" }
        }
        if (recent(v?.demandAlert)) { warn = true; return@run "⚠ " + v!!.demandAlert!!.optString("message") }
        sh?.plan?.let { if (!it.ok) { warn = true; return@run "お湯の毎日の設定がうまくいきませんでした" } }
        v?.autoNow?.firstOrNull()?.let { (i, t) -> return@run "$i 自動：$t" + if (v.autoNow.size > 1) "（ほか${v.autoNow.size - 1}件）" else "" }
        if (v?.guard?.optBoolean("active") == true) return@run "⏸ 買電が多いので、お湯の沸き上げを一時停止しています"
        if (awayUntil > now) return@run "🧳 留守（沸き上げを止めています）"
        v?.selfsuff?.takeIf { it.optBoolean("show") && !it.isNull("rate") }?.let { s ->
            val t = s.optInt("target", 0)
            return@run "🌱 今日の自給率 ${s.optDouble("rate").toInt()}%" + if (t > 0) "（目標 $t%）" else ""
        }
        if ((v?.heating ?: sh?.heating) == true) "♨ 沸き上げ中" else null
    }

    Card(
        onClick = onOpen,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "電気とお湯。押すとくわしい情報を開きます" },
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (kpis.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    kpis.forEach { it() }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    line ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (warn) FontWeight.Bold else null,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                c?.takeIf { it.has("today_yen") && !it.isNull("today_yen") }?.let {
                    Text("今日 " + String.format("%,d円", it.optLong("today_yen")), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                }
                Text("くわしく ›", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun Kpi(label: String, value: String, unit: String, color: Color?, bar: Int? = null) {
    Column(Modifier.widthIn(min = 76.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = color ?: MaterialTheme.colorScheme.onSurface)
            Text(unit, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 2.dp, bottom = 3.dp))
        }
        bar?.let { LinearProgressIndicator(progress = { it / 100f }, modifier = Modifier.widthIn(min = 76.dp, max = 120.dp).height(4.dp)) }
    }
}

/** 押すと開く：電気とお湯のくわしい情報（今までホーム画面に出していたものを、そのまま全部） */
@Composable
fun EnergyDetailDialog(vm: MainViewModel, onOpenEnergy: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.widthIn(max = 860.dp).fillMaxWidth().padding(12.dp),
        ) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("⚡ 電気とお湯", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("✕ 閉じる") }
                }
                Column(
                    Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    LiveStrip(vm)
                    EnergyStrip(vm, onOpen = onOpenEnergy)
                    EcoCuteCard(vm)
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}
