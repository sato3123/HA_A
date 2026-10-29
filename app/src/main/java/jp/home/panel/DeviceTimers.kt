@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package jp.home.panel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** タイマーを使える機器 */
fun timerCapable(e: Entity) = e.domain in setOf("switch", "light", "fan", "input_boolean", "climate", "media_player") &&
    !e.entityId.contains(".el_") && !isDirectCamera(e.entityId) // Home Assistant にある機器だけ（サーバーが Home Assistant を通して動かす）

/** 「22:30」「明日 6:30」 */
fun timerTimeText(atMs: Long): String {
    val t = Instant.ofEpochMilli(atMs).atZone(ZoneId.systemDefault())
    val day = when (t.toLocalDate()) {
        LocalDate.now() -> ""
        LocalDate.now().plusDays(1) -> "明日 "
        else -> "${t.monthValue}/${t.dayOfMonth} "
    }
    return day + t.format(DateTimeFormatter.ofPattern("H:mm"))
}

fun remainText(ms: Long): String {
    val m = (ms / 60_000).coerceAtLeast(0)
    return if (m >= 60) "${m / 60}時間${if (m % 60 > 0) "${m % 60}分" else ""}" else "${m}分"
}

/** タイルの名前の下に出す「⏲ 22:30 に切る」 */
@Composable
fun TimerBadge(e: Entity, vm: MainViewModel) {
    val timers by vm.deviceTimers.collectAsState()
    val t = timers.filter { it.entity == e.entityId }.minByOrNull { it.atMs } ?: return
    Text("⏲ ${timerTimeText(t.atMs)} に${t.label}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1)
}

/** 詳細パネル：この機器のタイマー（「1時間後に切る」「6:30 に暖房」など） */
@Composable
fun DeviceTimerSection(e: Entity, vm: MainViewModel) {
    if (!timerCapable(e)) return
    val timers by vm.deviceTimers.collectAsState()
    val msg by vm.deviceTimerMsg.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { vm.loadDeviceTimers(); while (true) { now = System.currentTimeMillis(); delay(20_000) } }
    val mine = timers.filter { it.entity == e.entityId }.sortedBy { it.atMs }
    val chip = FilterChipDefaults.filterChipColors(
        selectedContainerColor = MaterialTheme.colorScheme.primary,
        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
    )
    // 何をするか
    val climate = e.domain == "climate"
    val modes = if (climate) e.attributes.strings("hvac_modes").filter { it != "off" } else emptyList()
    val actions: List<Pair<String, String>> = if (climate) listOf("off" to "停止") + modes.map { it to hvacLabel(it) }
    else listOf("turn_off" to "切る", "turn_on" to "つける")
    val defaultAction = if (climate) (if (e.state == "off") modes.firstOrNull() ?: "off" else "off") else if (e.isOn) "turn_off" else "turn_on"
    var action by remember(e.entityId) { mutableStateOf(defaultAction) }
    var temp by remember(e.entityId) { mutableStateOf(e.attributes.num("temperature")) }
    var picking by remember { mutableStateOf(false) }
    fun send(atMs: Long? = null, afterMin: Int? = null) {
        if (climate) {
            val d = JSONObject().put("hvac_mode", action)
            if (action != "off") temp?.let { d.put("temperature", it) }
            vm.addDeviceTimer(e, "set_hvac_mode", d, atMs, afterMin)
        } else vm.addDeviceTimer(e, action, JSONObject(), atMs, afterMin)
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("⏲ タイマー", style = MaterialTheme.typography.labelLarge)
        mine.forEach { t ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("${timerTimeText(t.atMs)} に${t.label}（あと${remainText(t.atMs - now)}）", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = { vm.cancelDeviceTimer(t.id) }) { Text("取り消す") }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            actions.forEach { (k, l) -> FilterChip(selected = action == k, onClick = { action = k }, label = { Text(l) }, colors = chip) }
        }
        if (climate && action != "off") {
            val step = e.attributes.num("target_temp_step") ?: 0.5
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("設定温度", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { temp = ((temp ?: 24.0) - step).coerceAtLeast(e.attributes.num("min_temp") ?: 16.0) }) { Text("−") }
                Text(temp?.let { "${fmt(it)}℃" } ?: "そのまま", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { temp = ((temp ?: 24.0) + step).coerceAtMost(e.attributes.num("max_temp") ?: 31.0) }) { Text("＋") }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(30, 60, 90, 120, 180, 240).forEach { m ->
                FilledTonalButton(onClick = { send(afterMin = m) }) { Text(if (m >= 60) "${m / 60}${if (m % 60 == 30) ".5" else ""}時間後" else "${m}分後") }
            }
            FilledTonalButton(onClick = { picking = true }) { Text("時刻を指定") }
        }
        msg?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        Text("タイマーはホームパネルのサーバーが Home Assistant を通して動かすので、タブレットを切っていても動きます。", style = MaterialTheme.typography.bodySmall)
    }
    if (picking) {
        val st = rememberTimePickerState(initialHour = (LocalDateTime.now().hour + 1) % 24, initialMinute = 0, is24Hour = true)
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text("何時にしますか") },
            text = { TimePicker(state = st) },
            confirmButton = {
                TextButton(onClick = {
                    var at = LocalDate.now().atTime(st.hour, st.minute)
                    if (at.isBefore(LocalDateTime.now())) at = at.plusDays(1)
                    send(atMs = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
                    picking = false
                }) { Text("設定する") }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("やめる") } },
        )
    }
}

/** タイマーの画面に出す、すべての機器のタイマー */
@Composable
fun DeviceTimerList(vm: MainViewModel) {
    val timers by vm.deviceTimers.collectAsState()
    LaunchedEffect(Unit) { vm.loadDeviceTimers() }
    if (timers.isEmpty()) return
    val now = System.currentTimeMillis()
    Text("機器のタイマー", style = MaterialTheme.typography.labelLarge)
    timers.sortedBy { it.atMs }.forEach { t ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("${timerTimeText(t.atMs)}　${t.name} を${t.label}（あと${remainText(t.atMs - now)}）", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { vm.cancelDeviceTimer(t.id) }) { Text("取消") }
        }
    }
}

/** 音声の「エアコンを1時間後に切って」「6時半に寝室のエアコンを暖房にして」 */
data class DeviceTimerCommand(val entity: Entity, val action: String, val data: JSONObject, val afterMin: Int?, val atMs: Long?, val label: String)

fun parseDeviceTimerCommand(raw: String, entities: List<Entity>): DeviceTimerCommand? {
    val text = normalizeNumbers(raw).replace(" ", "").replace("　", "")
    val after = Regex("(?:(\\d+)時間)?(半)?(?:(\\d+)分)?後").find(text)?.takeIf { it.groupValues[1].isNotEmpty() || it.groupValues[3].isNotEmpty() }
    val at = Regex("(午前|午後|朝|夜)?(\\d{1,2})時(半|(\\d{1,2})分)?に").find(text)
    if (after == null && at == null) return null
    // いちばん長く名前が一致する機器
    val e = entities.filter { timerCapable(it) && it.name.isNotBlank() && text.contains(it.name.replace(" ", "")) }.maxByOrNull { it.name.length }
        ?: entities.filter { timerCapable(it) && it.domain == "climate" }.takeIf { it.size == 1 && text.contains("エアコン") }?.first()
        ?: return null
    val (action, data, label) = when {
        e.domain == "climate" && Regex("冷房").containsMatchIn(text) -> Triple("set_hvac_mode", JSONObject().put("hvac_mode", "cool"), "冷房にする")
        e.domain == "climate" && Regex("暖房").containsMatchIn(text) -> Triple("set_hvac_mode", JSONObject().put("hvac_mode", "heat"), "暖房にする")
        e.domain == "climate" && Regex("除湿").containsMatchIn(text) -> Triple("set_hvac_mode", JSONObject().put("hvac_mode", "dry"), "除湿にする")
        e.domain == "climate" && Regex("切|消|止|けし|とめ|オフ").containsMatchIn(text) -> Triple("set_hvac_mode", JSONObject().put("hvac_mode", "off"), "止める")
        Regex("切|消|止|けし|とめ|オフ").containsMatchIn(text) -> Triple("turn_off", JSONObject(), "切る")
        Regex("つけ|付け|点け|オン|入れ").containsMatchIn(text) -> Triple("turn_on", JSONObject(), "つける")
        else -> return null
    }
    Regex("(\\d{2})度").find(text)?.let { if (action == "set_hvac_mode") data.put("temperature", it.groupValues[1].toDouble()) }
    if (after != null) {
        val min = (after.groupValues[1].toIntOrNull() ?: 0) * 60 + (if (after.groupValues[2].isNotEmpty()) 30 else 0) + (after.groupValues[3].toIntOrNull() ?: 0)
        if (min <= 0) return null
        return DeviceTimerCommand(e, action, data, min, null, label)
    }
    val m = at!!
    var h = m.groupValues[2].toInt()
    val mi = if (m.groupValues[3] == "半") 30 else m.groupValues[4].toIntOrNull() ?: 0
    if ((m.groupValues[1] == "午後" || m.groupValues[1] == "夜") && h < 12) h += 12
    if (h > 23 || mi > 59) return null
    var t = LocalDate.now().atTime(h, mi)
    if (t.isBefore(LocalDateTime.now())) t = t.plusDays(1)
    return DeviceTimerCommand(e, action, data, null, t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), label)
}
