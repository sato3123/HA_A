@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package jp.home.panel

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONObject

private val COST = Color(0xFFFF9800)
private val SELLC = Color(0xFF42A5F5)

private fun yen(v: Long) = String.format("%,d円", v)
private fun diff(v: Long) = (if (v > 0) "＋" else if (v < 0) "−" else "±") + String.format("%,d円", kotlin.math.abs(v))

/** 月ごとの電気代を比べる：電気代（買った電気）・売電額・差し引き。前の月・去年の同じ月と比べる */
@Composable
fun MonthlyCostSection(vm: MainViewModel) {
    var data by remember { mutableStateOf<JSONObject?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var sel by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        try { data = vm.costMonths() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { err = e.message }
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("💴 月ごとの電気代", style = MaterialTheme.typography.titleMedium)
            err?.let { Text("読めませんでした：$it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            val d = data ?: run { if (err == null) Text("読んでいます…", style = MaterialTheme.typography.bodySmall); return@Column }
            val arr = d.optJSONArray("months")
            val rows = (0 until (arr?.length() ?: 0)).mapNotNull { arr!!.optJSONObject(it) }   // 新しい月が先
            if (rows.isEmpty()) {
                HelpText("まだ記録がありません。⚙ 設定 →「電気料金」で電力会社とプランを選ぶと、サーバーの記録と Home Assistant のエネルギーの記録から計算します。", style = MaterialTheme.typography.bodySmall)
            } else {
                val byYm = rows.associateBy { it.optString("ym") }
                val cur = byYm[sel] ?: rows.first()
                val ym = cur.optString("ym")
                val y = ym.take(4).toInt(); val m = ym.substring(5).toInt()
                val prev = byYm[if (m == 1) "${y - 1}-12" else String.format("%04d-%02d", y, m - 1)]
                val lastYear = byYm[String.format("%04d-%02d", y - 1, m)]
                Text("${y}年${m}月" + (if (cur.optBoolean("partial")) "（記録は途中から）" else "") +
                    (if (cur.optString("src") == "ha") "・Home Assistant の記録から計算" else ""),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Big("電気代", yen(cur.optLong("total_yen")), COST)
                    Big("売電", yen(cur.optLong("sell_yen")), SELLC)
                    Big("差し引き", yen(cur.optLong("net_yen")), null)
                }
                Text("買った電気 ${String.format("%.0f", cur.optDouble("buy_kwh"))}kWh・売った電気 ${String.format("%.0f", cur.optDouble("sell_kwh"))}kWh" +
                    "（使用 ${String.format("%,d", cur.optLong("energy_yen"))}円＋基本料金 ${String.format("%,d", cur.optLong("basic_yen"))}円）",
                    style = MaterialTheme.typography.bodySmall)
                val cmp = listOfNotNull(
                    prev?.let { "前の月より 差し引き ${diff(cur.optLong("net_yen") - it.optLong("net_yen"))}（電気代 ${diff(cur.optLong("total_yen") - it.optLong("total_yen"))}）" },
                    lastYear?.let { "去年の${m}月より 差し引き ${diff(cur.optLong("net_yen") - it.optLong("net_yen"))}（電気代 ${diff(cur.optLong("total_yen") - it.optLong("total_yen"))}）" },
                )
                cmp.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                if (ym == rows.first().optString("ym") && !d.isNull("month_estimate_yen") && d.has("month_estimate_yen")) {
                    Text("今月の電気代の見込み ${yen(d.optLong("month_estimate_yen"))}", style = MaterialTheme.typography.bodySmall)
                }
                // 棒グラフ（古い月が左）。上：電気代、下：売電。押すとその月
                val list = rows.take(13).reversed()
                val maxV = (list.maxOfOrNull { maxOf(it.optLong("total_yen"), it.optLong("sell_yen")) } ?: 1L).coerceAtLeast(1L).toFloat()
                val primary = MaterialTheme.colorScheme.primary
                val outline = MaterialTheme.colorScheme.outline
                Canvas(
                    Modifier.fillMaxWidth().height(150.dp)
                        .semantics { contentDescription = "月ごとの電気代（上）と売電（下）の棒グラフ。棒を押すとその月を表示" }
                        .pointerInput(list) {
                            detectTapGestures { o ->
                                val i = (o.x / (size.width / list.size)).toInt().coerceIn(0, list.size - 1)
                                sel = list[i].optString("ym")
                            }
                        },
                ) {
                    val bw = size.width / list.size
                    val mid = size.height * 0.62f
                    drawLine(outline, Offset(0f, mid), Offset(size.width, mid), 1.5f)
                    list.forEachIndexed { i, r ->
                        val x = i * bw + bw * 0.18f
                        val w = bw * 0.64f
                        val up = r.optLong("total_yen") / maxV * mid * 0.95f
                        val down = r.optLong("sell_yen") / maxV * mid * 0.95f
                        val chosen = r.optString("ym") == ym
                        drawRect(COST.copy(alpha = if (chosen) 1f else 0.55f), Offset(x, mid - up), Size(w, up))
                        drawRect(SELLC.copy(alpha = if (chosen) 1f else 0.55f), Offset(x, mid), Size(w, minOf(down, size.height - mid)))
                        if (chosen) drawRect(primary, Offset(x - 2f, 0f), Size(w + 4f, size.height), style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
                    }
                }
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text(list.first().optString("ym").replace("-", "/"), style = MaterialTheme.typography.labelMedium)
                    Text("■ 電気代　■ 売電", style = MaterialTheme.typography.labelMedium)
                    Text(list.last().optString("ym").replace("-", "/"), style = MaterialTheme.typography.labelMedium)
                }
                // 一覧
                Row(Modifier.fillMaxWidth()) {
                    listOf("月" to 0.9f, "電気代" to 1.2f, "売電" to 1.1f, "差し引き" to 1.2f).forEach { (t, w) ->
                        Text(t, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(w))
                    }
                }
                rows.take(13).forEach { r ->
                    val chosen = r.optString("ym") == ym
                    Row(Modifier.fillMaxWidth().pointerInput(r) { detectTapGestures { sel = r.optString("ym") } }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        val fw = if (chosen) FontWeight.Bold else null
                        Text(r.optString("ym").replace("-", "/") + if (r.optBoolean("partial")) "＊" else "", fontWeight = fw, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.9f))
                        Text(yen(r.optLong("total_yen")), fontWeight = fw, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1.2f))
                        Text(yen(r.optLong("sell_yen")), fontWeight = fw, style = MaterialTheme.typography.bodyMedium, color = SELLC, modifier = Modifier.weight(1.1f))
                        Text(yen(r.optLong("net_yen")), fontWeight = fw, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1.2f))
                    }
                }
                if (rows.any { it.optBoolean("partial") }) Text("＊ 記録がその月の途中からの月です（実際より少なく出ます）", style = MaterialTheme.typography.labelMedium)
                HelpText("電気代は、選んだ料金プランの単価・燃料費調整額・再エネ賦課金で計算した目安です（請求額と少しずれることがあります）。売電額は、設定の売電単価 ${d.optDouble("sell_price")}円/kWh で計算しています。", style = MaterialTheme.typography.bodySmall)
            }
            d.optString("backfill_error").takeIf { it.isNotBlank() && it != "null" }?.let {
                HelpText("Home Assistant の記録（前の月）を読めませんでした：$it", style = MaterialTheme.typography.bodySmall)
            }
            TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    try { data = vm.costMonths(refresh = true); err = null } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { err = e.message }
                    busy = false
                }
            }) { Text(if (busy) "読み直しています…" else "↻ Home Assistant の記録から読み直す") }
        }
    }
}

@Composable
private fun Big(label: String, value: String, color: Color?) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, color = color ?: MaterialTheme.colorScheme.onSurface)
    }
}
