package jp.home.panel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 詳しい情報に出す項目と日本語名（ここにない項目は出さない） */
private val ATTR_LABELS = linkedMapOf(
    "brightness" to "明るさ",
    "color_temp_kelvin" to "色温度",
    "color_mode" to "色のモード",
    "hs_color" to "色（色相・彩度）",
    "current_temperature" to "室温",
    "temperature" to "設定温度",
    "target_temp_low" to "設定温度（下限）",
    "target_temp_high" to "設定温度（上限）",
    "current_humidity" to "湿度",
    "humidity" to "湿度",
    "hvac_action" to "いまの動作",
    "fan_mode" to "風量",
    "swing_mode" to "風向き",
    "preset_mode" to "プリセット",
    "percentage" to "風量",
    "oscillating" to "首振り",
    "current_position" to "開き具合",
    "battery_level" to "電池",
    "battery" to "電池",
    "device_class" to "種類",
    "unit_of_measurement" to "単位",
    "min_temp" to "設定できる最低温度",
    "max_temp" to "設定できる最高温度",
    "hvac_modes" to "使える運転モード",
    "fan_modes" to "使える風量",
    "preset_modes" to "使えるプリセット",
    "last_triggered" to "最後に動いた時刻",
)

private val DT = DateTimeFormatter.ofPattern("M/d（E） H:mm", Locale.JAPAN)
private val HM = DateTimeFormatter.ofPattern("H:mm", Locale.JAPAN)

private fun parseTime(s: String): LocalDateTime? =
    runCatching { OffsetDateTime.parse(s).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime() }.getOrNull()

/** 「5分前（10/25（土） 0:35）」 */
fun agoText(s: String): String {
    val t = parseTime(s) ?: return "不明"
    val d = Duration.between(t, LocalDateTime.now())
    val ago = when {
        d.toMinutes() < 1 -> "たった今"
        d.toMinutes() < 60 -> "${d.toMinutes()}分前"
        d.toHours() < 24 -> "${d.toHours()}時間前"
        else -> "${d.toDays()}日前"
    }
    return "$ago（${t.format(DT)}）"
}

private fun attrValue(key: String, v: Any): String = when {
    key == "brightness" && v is Number -> "${(v.toDouble() / 255 * 100).toInt()}%"
    key == "color_temp_kelvin" -> "${v}K"
    key in setOf("current_temperature", "temperature", "target_temp_low", "target_temp_high", "min_temp", "max_temp") -> "${v}℃"
    key in setOf("current_humidity", "humidity", "percentage", "current_position", "battery_level") -> "${v}%"
    key == "hvac_action" -> when (v.toString()) {
        "cooling" -> "冷房中"; "heating" -> "暖房中"; "drying" -> "除湿中"; "fan" -> "送風中"; "idle" -> "待機中"; "off" -> "停止"
        else -> v.toString()
    }
    key == "hvac_modes" && v is JSONArray -> (0 until v.length()).joinToString("・") { hvacLabel(v.optString(it)) }
    key == "fan_mode" -> fanModeLabel(v.toString())
    key == "swing_mode" -> swingLabel(v.toString())
    key == "preset_mode" -> presetLabel(v.toString())
    key == "oscillating" -> if (v == true) "オン" else "オフ"
    key == "last_triggered" -> agoText(v.toString())
    v is JSONArray -> (0 until v.length()).joinToString("・") { v.opt(it).toString() }
    else -> v.toString()
}

/** 機器の「動作の詳細」：いまの状態、最後に変わった時刻、詳しい値、直近24時間の動き */
@Composable
fun EntityInfoDialog(vm: MainViewModel) {
    val id by vm.infoFor.collectAsState()
    val states by vm.allStates.collectAsState()
    val history by vm.history.collectAsState()
    val status by vm.historyStatus.collectAsState()
    val rooms by vm.rooms.collectAsState()
    val entityId = id ?: return
    val e = states[entityId]
    AlertDialog(
        onDismissRequest = vm::hideInfo,
        title = { Text("ⓘ " + (e?.name ?: entityId)) },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (e == null) {
                    Text("この機器の情報がありません")
                    return@Column
                }
                InfoRow("いまの状態", stateLabel(e))
                InfoRow("最後に変わった", agoText(e.lastChanged))
                InfoRow("最後に更新", agoText(e.lastUpdated))
                InfoRow("種類", domainLabel(e.domain))
                val rk = vm.roomKey(e.entityId)
                InfoRow("部屋", rooms.firstOrNull { it.key == rk }?.name ?: "その他")
                InfoRow("ID", e.entityId)

                val attrs: JSONObject = e.attributes
                val shown = ATTR_LABELS.filterKeys { attrs.has(it) && !attrs.isNull(it) }
                if (shown.isNotEmpty()) {
                    HorizontalDivider()
                    Text("詳しい情報", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    shown.forEach { (k, label) -> InfoRow(label, attrValue(k, attrs.get(k))) }
                }

                HorizontalDivider()
                Text("直近24時間の動き", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                history?.take(40)?.forEach { h ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(h.time.format(HM), fontWeight = FontWeight.Medium, modifier = Modifier.width(52.dp))
                        Text(stateLabel(Entity(e.entityId, h.state, JSONObject())))
                    }
                }
                if ((history?.size ?: 0) > 40) Text("ほか${history!!.size - 40}件", style = MaterialTheme.typography.labelSmall)
            }
        },
        confirmButton = { TextButton(onClick = vm::hideInfo) { Text("閉じる") } },
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(120.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
