@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package jp.home.panel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

/**
 * エアコンの自動温度調整：部屋の温度を min〜max に保つ。
 * source：「ac」＝エアコンの室温、それ以外＝温度計の key（SwitchBot など）。
 */
data class ClimateRule(
    val entityId: String,
    val enabled: Boolean = false,
    val min: Double = 20.0,
    val max: Double = 27.0,
    val source: String = "ac",
    val cool: Boolean = true,
    val heat: Boolean = true,
    val offInRange: Boolean = false, // 範囲の真ん中に戻ったら止める
) {
    fun toJson(): JSONObject = JSONObject().put("e", entityId).put("on", enabled).put("min", min).put("max", max)
        .put("src", source).put("cool", cool).put("heat", heat).put("off", offInRange)

    companion object {
        fun fromJson(j: JSONObject) = ClimateRule(
            j.optString("e"), j.optBoolean("on"), j.optDouble("min", 20.0), j.optDouble("max", 27.0),
            j.optString("src", "ac"), j.optBoolean("cool", true), j.optBoolean("heat", true), j.optBoolean("off", false),
        )

        fun listFrom(text: String?): List<ClimateRule> = try {
            val a = JSONArray(text ?: "[]")
            (0 until a.length()).map { fromJson(a.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
    }
}

/** 設定画面：エアコンの自動温度調整 */
@Composable
fun ClimateAutoSettings(vm: MainViewModel) {
    val entities by vm.allEntities.collectAsState()
    val rules by vm.climateRules.collectAsState()
    val localStatus by vm.climateStatus.collectAsState()
    val thermos by vm.thermos.collectAsState()
    val enabled by vm.climateEnabled.collectAsState()
    val onServer by vm.climateOnServer.collectAsState()
    val server by vm.climateServer.collectAsState()
    val serverMsg by vm.climateServerMsg.collectAsState()
    val tokenMsg by vm.serverTokenMsg.collectAsState()
    var detail by remember { mutableStateOf<String?>(null) }
    val acs = entities.filter { it.domain == "climate" }
    val chip = FilterChipDefaults.filterChipColors(
        selectedContainerColor = MaterialTheme.colorScheme.primary,
        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
    )
    // サーバーで動かしているときは、様子を 30 秒ごとに読み直す
    LaunchedEffect(onServer) {
        while (onServer) { vm.loadClimateServer(); delay(30_000) }
    }
    val serverState = server?.optJSONObject("state")
    val status: Map<String, String> = if (onServer) {
        acs.associate { it.entityId to (serverState?.optJSONObject(it.entityId)?.optString("status").orEmpty()) }.filterValues { it.isNotBlank() }
    } else localStatus
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "温度の範囲を決めておくと、部屋の温度がその範囲を外れたときに、エアコンを自動で冷房・暖房にして範囲内に戻します。" +
                "温度は、エアコンの室温か、部屋に置いた温度計（SwitchBot など）から選べます。1分ごとに確かめます。",
            style = MaterialTheme.typography.bodySmall,
        )
        // 全体のオン/オフ
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("自動温度調整（全体）", style = MaterialTheme.typography.titleMedium)
                        Text(if (enabled) "オン：下でオンにしたエアコンを自動で調整します" else "オフ：どのエアコンも自動では動かしません",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = enabled, onCheckedChange = { vm.setClimateEnabled(it) })
                }
                Text("動かす場所", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = onServer, onClick = { vm.setClimateWhere(true) }, label = { Text("ホームパネルのサーバー（おすすめ）") }, colors = chip)
                    FilterChip(selected = !onServer, onClick = { vm.setClimateWhere(false) }, label = { Text("このタブレット") }, colors = chip)
                }
                Text(
                    if (onServer) "サーバー（${vm.shServer.value}）が Home Assistant を通してエアコンを操作します。タブレットの電源が切れていても動き続けます。"
                    else "このタブレットのアプリが動いている間だけ調整します。",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (onServer) {
                    val tokenSet = server?.optBoolean("ha_token_set") == true
                    Text(
                        if (server == null) "サーバーの様子を読んでいます…"
                        else if (tokenSet) "✓ サーバーに Home Assistant の鍵（トークン）が登録されています"
                        else "⚠ サーバーが Home Assistant を操作するための鍵（トークン）がまだありません。下のボタンで登録してください",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (server != null && !tokenSet) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { vm.registerServerToken() }) { Text(if (tokenSet) "鍵を作り直して登録" else "サーバーに鍵を登録") }
                        OutlinedButton(onClick = { vm.pushClimateToServer() }) { Text("設定をサーバーに送り直す") }
                    }
                    tokenMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    serverMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                }
            }
        }
        if (acs.isEmpty()) Text("エアコンが見つかりません。", style = MaterialTheme.typography.bodyMedium)
        acs.forEach { ac ->
            val r = rules.firstOrNull { it.entityId == ac.entityId } ?: ClimateRule(ac.entityId)
            fun save(x: ClimateRule) = vm.saveClimateRule(x)
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(ac.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Switch(checked = r.enabled, onCheckedChange = { save(r.copy(enabled = it)) })
                    }
                    status[ac.entityId]?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                    TextButton(onClick = { detail = ac.entityId }) { Text("詳しく見る（いまの様子・24時間の温度・操作の記録）") }
                    // 範囲
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("下限", style = MaterialTheme.typography.bodySmall)
                        FilledTonalButton(onClick = { save(r.copy(min = (r.min - 0.5).coerceAtLeast(10.0))) }) { Text("−") }
                        Text("${fmt(r.min)}℃", style = MaterialTheme.typography.titleMedium)
                        FilledTonalButton(onClick = { save(r.copy(min = (r.min + 0.5).coerceAtMost(r.max - 1))) }) { Text("＋") }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("上限", style = MaterialTheme.typography.bodySmall)
                        FilledTonalButton(onClick = { save(r.copy(max = (r.max - 0.5).coerceAtLeast(r.min + 1))) }) { Text("−") }
                        Text("${fmt(r.max)}℃", style = MaterialTheme.typography.titleMedium)
                        FilledTonalButton(onClick = { save(r.copy(max = (r.max + 0.5).coerceAtMost(32.0))) }) { Text("＋") }
                    }
                    // 温度のもと
                    Text("温度のもと", style = MaterialTheme.typography.labelMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = r.source == "ac", onClick = { save(r.copy(source = "ac")) }, label = { Text("エアコンの室温") }, colors = chip)
                        thermos.forEach { t ->
                            FilterChip(selected = r.source == t.key, onClick = { save(r.copy(source = t.key)) },
                                label = { Text("🌡 " + t.name + (t.temperature?.let { " ${fmt(it)}℃" } ?: "")) }, colors = chip)
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = r.cool, onClick = { save(r.copy(cool = !r.cool)) }, label = { Text("冷房を使う") }, colors = chip)
                        FilterChip(selected = r.heat, onClick = { save(r.copy(heat = !r.heat)) }, label = { Text("暖房を使う") }, colors = chip)
                        FilterChip(selected = r.offInRange, onClick = { save(r.copy(offInRange = !r.offInRange)) }, label = { Text("範囲の真ん中に戻ったら止める") }, colors = chip)
                    }
                }
            }
        }
        Text(
            "・上限より0.5℃高くなったら冷房（設定温度＝上限）、下限より0.5℃低くなったら暖房（設定温度＝下限）にします。\n" +
                "・温度計を使うときは、20分たっても範囲に戻らなければ、設定温度を1℃ずつ（最大3℃）強めます。\n" +
                "・同じエアコンの操作は5分に1回までです。手でエアコンを操作したときは、その設定がしばらく優先されます（10分）。",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    detail?.let { ClimateDetailDialog(vm, it) { detail = null } }
}

/** エアコン1台の詳しい様子：いまの温度・運転・設定、24時間の温度、自動で行った操作 */
@Composable
fun ClimateDetailDialog(vm: MainViewModel, entityId: String, onDismiss: () -> Unit) {
    val entities by vm.allEntities.collectAsState()
    val rules by vm.climateRules.collectAsState()
    val server by vm.climateServer.collectAsState()
    val onServer by vm.climateOnServer.collectAsState()
    val local by vm.climateStatus.collectAsState()
    val enabled by vm.climateEnabled.collectAsState()
    LaunchedEffect(entityId) { if (onServer) vm.loadClimateServer() }
    val e = entities.firstOrNull { it.entityId == entityId }
    val r = rules.firstOrNull { it.entityId == entityId }
    val st = server?.optJSONObject("state")?.optJSONObject(entityId)
    val tf = java.time.format.DateTimeFormatter.ofPattern("M/d H:mm")
    fun at(sec: Double) = java.time.Instant.ofEpochMilli((sec * 1000).toLong()).atZone(java.time.ZoneId.systemDefault()).format(tf)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("閉じる") } },
        title = { Text("❄ " + (e?.name ?: st?.optString("name") ?: entityId)) },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val mode = e?.state ?: st?.optString("mode")
                val target = e?.attributes?.optDouble("temperature")?.takeIf { !it.isNaN() }
                val room = e?.attributes?.optDouble("current_temperature")?.takeIf { !it.isNaN() }
                Text("運転：" + modeName(mode) + (target?.let { "・設定 ${fmt(it)}℃" } ?: "") + (room?.let { "・エアコンの室温 ${fmt(it)}℃" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium)
                if (r == null || !r.enabled) Text("この部屋の自動温度調整はオフです", style = MaterialTheme.typography.bodyMedium)
                else Text(
                    "範囲 ${fmt(r.min)}〜${fmt(r.max)}℃・" + (if (r.source == "ac") "エアコンの室温で判断" else "温度計で判断") +
                        (if (r.cool) "・冷房" else "") + (if (r.heat) "・暖房" else "") + (if (r.offInRange) "・真ん中で停止" else "") +
                        if (!enabled) "（全体がオフのため止まっています）" else "",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text("動かしている場所：" + if (onServer) "ホームパネルのサーバー" else "このタブレット", style = MaterialTheme.typography.bodySmall)
                val statusText = if (onServer) st?.optString("status") else local[entityId]
                statusText?.takeIf { it.isNotBlank() }?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium) }
                st?.optDouble("at")?.takeIf { !it.isNaN() && it > 0 }?.let { Text("最後に確かめた時刻：${at(it)}", style = MaterialTheme.typography.bodySmall) }
                // 24 時間の温度（サーバーが 10 分ごとに記録）
                val temps = st?.optJSONArray("temps")
                val pts = (0 until (temps?.length() ?: 0)).mapNotNull { temps?.optJSONArray(it) }
                    .mapNotNull { a -> a.optDouble(1).takeIf { !it.isNaN() }?.let { MainViewModel.Point((a.optDouble(0) * 1000).toLong(), it) } }
                Text("24時間の温度（${st?.optString("source_name")?.takeIf { it.isNotBlank() } ?: "判断に使った温度"}）", style = MaterialTheme.typography.titleSmall)
                if (pts.size >= 2) {
                    LineChart(pts, 24, MaterialTheme.colorScheme.primary, "℃", band = r?.let { it.min to it.max }, stepped = false)
                    Text("色のついた帯が設定した範囲です", style = MaterialTheme.typography.bodySmall)
                } else Text(if (onServer) "まだ記録がありません（サーバーで調整を始めてから10分ごとに記録します）" else "温度の記録はサーバーで動かしているときだけ表示します",
                    style = MaterialTheme.typography.bodySmall)
                // 操作の記録
                Text("自動で行った操作", style = MaterialTheme.typography.titleSmall)
                val acts = st?.optJSONArray("actions")
                if (acts == null || acts.length() == 0) Text("まだありません", style = MaterialTheme.typography.bodySmall)
                else (0 until minOf(20, acts.length())).mapNotNull { acts.optJSONArray(it) }.forEach { a ->
                    Text("${at(a.optDouble(0))}　${a.optString(1)}", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
    )
}

fun modeName(m: String?) = when (m) {
    "cool" -> "冷房"; "heat" -> "暖房"; "dry" -> "除湿"; "fan_only" -> "送風"; "auto", "heat_cool" -> "自動"; "off" -> "停止"
    null, "" -> "不明"; "unavailable" -> "つながっていません"; else -> m
}
