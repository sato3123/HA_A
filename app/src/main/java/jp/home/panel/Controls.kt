package jp.home.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

// ---------- 属性の読み取り ----------

fun JSONObject.strings(key: String): List<String> {
    val a = optJSONArray(key) ?: return emptyList()
    return (0 until a.length()).map { a.optString(it) }
}

fun JSONObject.num(key: String): Double? =
    if (has(key) && !isNull(key)) optDouble(key).takeUnless { it.isNaN() } else null

private val COLOR_MODES = setOf("hs", "xy", "rgb", "rgbw", "rgbww")

val Entity.unavailable: Boolean get() = state == "unavailable" || state == "unknown"
val Entity.colorModes: List<String> get() = attributes.strings("supported_color_modes")
val Entity.dimmable: Boolean get() = colorModes.any { it != "onoff" }
val Entity.hasPercentage: Boolean get() = domain == "fan" && attributes.has("percentage")
val Entity.hasPosition: Boolean get() = domain == "cover" && attributes.num("current_position") != null
val Entity.canStop: Boolean get() = (attributes.optInt("supported_features") and 8) != 0

/** 2マス分の幅で表示するか（操作部品が多い機器） */
fun isWide(e: Entity): Boolean = when (e.domain) {
    "climate" -> true
    "light" -> e.dimmable
    "fan" -> e.hasPercentage
    "cover" -> e.hasPosition
    "camera" -> true
    else -> false
}

fun fmt(d: Double): String = if (d % 1.0 == 0.0) d.toInt().toString() else String.format("%.1f", d)

// ---------- タイル（一覧画面） ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntityTile(e: Entity, vm: MainViewModel, onDetail: () -> Unit) {
    if (e.domain == "camera") {
        CameraTile(e, vm)
        return
    }
    val active = e.isOn && e.domain !in setOf("scene", "script")
    // スイッチ類・シーンはタップで実行、それ以外はタップで詳細パネル
    val simple = e.domain in setOf("switch", "input_boolean", "scene", "script")
    Card(
        onClick = { if (simple) vm.primaryAction(e) else onDetail() },
        enabled = !e.unavailable,
        modifier = Modifier.fillMaxWidth().heightIn(min = 92.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (active) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            EntityHeader(e, vm, large = false)
            if (!e.unavailable) {
                when (e.domain) {
                    "light" -> if (e.dimmable && e.state == "on") BrightnessSlider(e, vm)
                    "fan" -> if (e.hasPercentage) FanSpeedSlider(e, vm)
                    "cover" -> {
                        CoverButtons(e, vm)
                        if (e.hasPosition) CoverPositionSlider(e, vm)
                    }
                    "climate" -> {
                        TemperatureControls(e, vm)
                        ChipGroup(null, e.attributes.strings("hvac_modes"), e.state, ::hvacLabel) {
                            vm.call(e, "set_hvac_mode", JSONObject().put("hvac_mode", it))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EntityHeader(e: Entity, vm: MainViewModel, large: Boolean) {
    val toggle = !e.unavailable && e.domain in setOf("light", "fan", "switch", "input_boolean")
    val info: @Composable () -> Unit = {
        // 動作の詳細（状態・最後に変わった時刻・履歴）
        androidx.compose.material3.TextButton(
            onClick = { vm.showInfo(e.entityId) },
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
            modifier = Modifier.size(36.dp),
        ) { Text("ⓘ", fontSize = 18.sp) }
    }
    val names: @Composable (Modifier) -> Unit = { m ->
        Column(m) {
            Text(domainLabel(e.domain), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                e.name,
                style = if (large) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(stateLabel(e), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= 300.dp) {
            // 広いとき：アイコン・名前・ⓘ・スイッチを1行に
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DeviceIcon(e, 40.dp)
                names(Modifier.weight(1f))
                info()
                if (toggle) Switch(checked = e.state == "on", onCheckedChange = { vm.call(e, if (it) "turn_on" else "turn_off") })
            }
        } else {
            // 狭いとき（小さいタイル）：上の段にアイコン・ⓘ・スイッチ、下の段に名前
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DeviceIcon(e, 40.dp)
                    Spacer(Modifier.weight(1f))
                    info()
                    if (toggle) Switch(checked = e.state == "on", onCheckedChange = { vm.call(e, if (it) "turn_on" else "turn_off") })
                }
                names(Modifier.fillMaxWidth())
            }
        }
    }
}

// ---------- 詳細パネル（タイルをタップしたとき） ----------

@Composable
fun EntityDetail(e: Entity, vm: MainViewModel) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        EntityHeader(e, vm, large = true)
        if (e.unavailable) {
            Text("この機器は現在利用できません")
            return@Column
        }
        when (e.domain) {
            "light" -> {
                if (e.dimmable) BrightnessSlider(e, vm)
                if ("color_temp" in e.colorModes) ColorTempSlider(e, vm)
                if (e.colorModes.any { it in COLOR_MODES }) ColorPresets(e, vm)
            }
            "fan" -> {
                if (e.hasPercentage) FanSpeedSlider(e, vm)
                ChipGroup("モード", e.attributes.strings("preset_modes"), e.attributes.optString("preset_mode"), ::presetLabel) {
                    vm.call(e, "set_preset_mode", JSONObject().put("preset_mode", it))
                }
                if (e.attributes.has("oscillating")) {
                    SwitchRow("首振り", e.attributes.optBoolean("oscillating")) {
                        vm.call(e, "oscillate", JSONObject().put("oscillating", it))
                    }
                }
            }
            "cover" -> {
                CoverButtons(e, vm)
                if (e.hasPosition) CoverPositionSlider(e, vm)
            }
            "climate" -> {
                val info = listOfNotNull(
                    e.attributes.num("current_temperature")?.let { "室温 ${fmt(it)}℃" },
                    e.attributes.num("current_humidity")?.let { "湿度 ${fmt(it)}%" },
                )
                if (info.isNotEmpty()) Text(info.joinToString("　"), style = MaterialTheme.typography.titleMedium)
                TemperatureControls(e, vm)
                ChipGroup("運転モード", e.attributes.strings("hvac_modes"), e.state, ::hvacLabel) {
                    vm.call(e, "set_hvac_mode", JSONObject().put("hvac_mode", it))
                }
                ChipGroup("風量", e.attributes.strings("fan_modes"), e.attributes.optString("fan_mode"), ::fanModeLabel) {
                    vm.call(e, "set_fan_mode", JSONObject().put("fan_mode", it))
                }
                ChipGroup("風向き", e.attributes.strings("swing_modes"), e.attributes.optString("swing_mode"), ::swingLabel) {
                    vm.call(e, "set_swing_mode", JSONObject().put("swing_mode", it))
                }
                ChipGroup("プリセット", e.attributes.strings("preset_modes"), e.attributes.optString("preset_mode"), ::presetLabel) {
                    vm.call(e, "set_preset_mode", JSONObject().put("preset_mode", it))
                }
            }
            else -> FilledTonalButton(onClick = { vm.primaryAction(e) }) {
                Text(if (e.domain in setOf("scene", "script")) "実行する" else "切り替える")
            }
        }
    }
}

// ---------- 部品 ----------

/** ドラッグ中は手元の値を表示し、指を離したときだけ Home Assistant に送る */
@Composable
private fun ValueSlider(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    label: (Float) -> String,
    onCommit: (Float) -> Unit,
) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(value) { dragging = null }
    val shown = (dragging ?: value).coerceIn(range.start, range.endInclusive)
    Column {
        Text(label(shown), style = MaterialTheme.typography.labelLarge)
        Slider(
            value = shown,
            onValueChange = { dragging = it },
            onValueChangeFinished = { dragging?.let(onCommit) },
            valueRange = range,
            steps = steps,
        )
    }
}

@Composable
private fun BrightnessSlider(e: Entity, vm: MainViewModel) {
    val pct = if (e.state == "on") ((e.attributes.num("brightness") ?: 0.0) / 255.0 * 100).toFloat() else 0f
    ValueSlider(pct, 0f..100f, label = { "明るさ ${it.roundToInt()}%" }) { v ->
        if (v < 1f) vm.call(e, "turn_off")
        else vm.call(e, "turn_on", JSONObject().put("brightness_pct", v.roundToInt()))
    }
}

@Composable
private fun ColorTempSlider(e: Entity, vm: MainViewModel) {
    val min = (e.attributes.num("min_color_temp_kelvin") ?: 2000.0).toFloat()
    val max = (e.attributes.num("max_color_temp_kelvin") ?: 6500.0).toFloat()
    if (max <= min) return
    val cur = (e.attributes.num("color_temp_kelvin") ?: ((min + max) / 2.0)).toFloat()
    ValueSlider(cur, min..max, label = { "色温度 ${it.roundToInt()}K（左：電球色 ⇔ 右：昼光色）" }) { v ->
        vm.call(e, "turn_on", JSONObject().put("color_temp_kelvin", v.roundToInt()))
    }
}

private data class ColorPreset(val name: String, val hue: Int, val sat: Int, val color: Color)

private val PRESETS = listOf(
    ColorPreset("赤", 0, 100, Color(0xFFFF3B30)),
    ColorPreset("オレンジ", 30, 100, Color(0xFFFF9500)),
    ColorPreset("黄", 55, 100, Color(0xFFFFE620)),
    ColorPreset("緑", 120, 100, Color(0xFF34C759)),
    ColorPreset("水色", 190, 100, Color(0xFF32D2F5)),
    ColorPreset("青", 230, 100, Color(0xFF2F5BFF)),
    ColorPreset("紫", 280, 100, Color(0xFFAF52DE)),
    ColorPreset("ピンク", 330, 60, Color(0xFFFF7EB6)),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorPresets(e: Entity, vm: MainViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("色", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PRESETS.forEach { p ->
                Box(
                    Modifier
                        .size(44.dp)
                        .background(p.color, CircleShape)
                        .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                        .clickable {
                            vm.call(e, "turn_on", JSONObject().put("hs_color", JSONArray().put(p.hue).put(p.sat)))
                        },
                )
            }
        }
    }
}

@Composable
private fun FanSpeedSlider(e: Entity, vm: MainViewModel) {
    val step = (e.attributes.num("percentage_step") ?: 1.0).coerceAtLeast(1.0)
    val steps = if (step > 1.0) (100.0 / step).roundToInt() - 1 else 0
    val pct = if (e.state == "on") (e.attributes.num("percentage") ?: 0.0).toFloat() else 0f
    ValueSlider(pct, 0f..100f, steps.coerceAtLeast(0), label = { "風量 ${it.roundToInt()}%" }) { v ->
        if (v < 1f) vm.call(e, "turn_off")
        else vm.call(e, "set_percentage", JSONObject().put("percentage", v.roundToInt()))
    }
}

@Composable
private fun CoverButtons(e: Entity, vm: MainViewModel) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(onClick = { vm.call(e, "open_cover") }) { Text("▲ 開") }
        if (e.canStop) FilledTonalButton(onClick = { vm.call(e, "stop_cover") }) { Text("■") }
        FilledTonalButton(onClick = { vm.call(e, "close_cover") }) { Text("▼ 閉") }
    }
}

@Composable
private fun CoverPositionSlider(e: Entity, vm: MainViewModel) {
    val pos = (e.attributes.num("current_position") ?: 0.0).toFloat()
    ValueSlider(pos, 0f..100f, label = { "開き具合 ${it.roundToInt()}%" }) { v ->
        vm.call(e, "set_cover_position", JSONObject().put("position", v.roundToInt()))
    }
}

/** エアコンの設定温度。冷暖自動（上下限）にも対応 */
@Composable
private fun TemperatureControls(e: Entity, vm: MainViewModel) {
    if (e.state == "off") return
    val step = e.attributes.num("target_temp_step") ?: 0.5
    val min = e.attributes.num("min_temp") ?: 16.0
    val max = e.attributes.num("max_temp") ?: 32.0
    val single = e.attributes.num("temperature")
    val low = e.attributes.num("target_temp_low")
    val high = e.attributes.num("target_temp_high")
    when {
        single != null -> TempStepper("設定温度", single, min, max, step) {
            vm.call(e, "set_temperature", JSONObject().put("temperature", it))
        }
        low != null && high != null -> {
            TempStepper("下限", low, min, high, step) {
                vm.call(e, "set_temperature", JSONObject().put("target_temp_low", it).put("target_temp_high", high))
            }
            TempStepper("上限", high, low, max, step) {
                vm.call(e, "set_temperature", JSONObject().put("target_temp_low", low).put("target_temp_high", it))
            }
        }
    }
}

/** −／＋を連打しても、最後に押してから少し待ってまとめて送る */
@Composable
private fun TempStepper(label: String, value: Double, min: Double, max: Double, step: Double, onSet: (Double) -> Unit) {
    var pending by remember(value) { mutableStateOf<Double?>(null) }
    val shown = pending ?: value
    LaunchedEffect(pending) {
        val p = pending ?: return@LaunchedEffect
        delay(700)
        onSet(p)
    }
    fun snap(v: Double) = (Math.round(v / step) * step).coerceIn(min, max)
    Text(label, style = MaterialTheme.typography.labelLarge)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
        FilledTonalButton(onClick = { pending = snap(shown - step) }, enabled = shown > min) {
            Text("−", fontSize = 22.sp)
        }
        Text("${fmt(shown)}℃", fontSize = 30.sp, modifier = Modifier.padding(horizontal = 12.dp))
        FilledTonalButton(onClick = { pending = snap(shown + step) }, enabled = shown < max) {
            Text("＋", fontSize = 22.sp)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ChipGroup(
    title: String?,
    options: List<String>,
    selected: String?,
    label: (String) -> String,
    onSelect: (String) -> Unit,
) {
    if (options.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        title?.let { Text(it, style = MaterialTheme.typography.labelLarge) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            options.forEach { o ->
                FilterChip(
                    selected = o == selected,
                    onClick = { onSelect(o) },
                    label = { Text(label(o)) },
                    colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                )
            }
        }
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, sub: String? = null, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            sub?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

// ---------- 表示名 ----------

fun domainLabel(domain: String) = when (domain) {
    "light" -> "照明"
    "switch" -> "スイッチ"
    "fan" -> "ファン"
    "cover" -> "カーテン・シャッター"
    "climate" -> "エアコン"
    "input_boolean" -> "切り替え"
    "scene" -> "シーン"
    "script" -> "スクリプト"
    "binary_sensor" -> "センサー"
    "person" -> "人"
    "device_tracker" -> "位置"
    "lock" -> "鍵"
    "camera" -> "カメラ"
    "automation" -> "自動化"
    else -> domain
}

fun hvacLabel(m: String) = when (m) {
    "off" -> "停止"
    "heat" -> "暖房"
    "cool" -> "冷房"
    "auto" -> "自動"
    "heat_cool" -> "冷暖自動"
    "dry" -> "除湿"
    "fan_only" -> "送風"
    else -> m
}

fun fanModeLabel(m: String) = when (m.lowercase()) {
    "auto" -> "自動"
    "low" -> "弱"
    "medium", "middle", "mid" -> "中"
    "high" -> "強"
    "quiet", "silent" -> "静音"
    "turbo", "strong", "powerful" -> "パワフル"
    "on" -> "オン"
    "off" -> "オフ"
    else -> m
}

fun swingLabel(m: String) = when (m.lowercase()) {
    "off" -> "固定"
    "on" -> "スイング"
    "vertical" -> "上下"
    "horizontal" -> "左右"
    "both" -> "上下左右"
    else -> m
}

fun presetLabel(m: String) = when (m.lowercase()) {
    "none" -> "なし"
    "eco" -> "エコ"
    "away" -> "外出"
    "home" -> "在宅"
    "boost" -> "ブースト"
    "comfort" -> "快適"
    "sleep" -> "おやすみ"
    "activity" -> "活動"
    "auto" -> "自動"
    "natural", "nature" -> "自然風"
    else -> m
}

fun stateLabel(e: Entity): String {
    if (e.state == "unavailable") return "利用できません"
    if (e.state == "unknown") return "不明"
    return when (e.domain) {
        "scene", "script" -> "タップで実行"
        "cover" -> when (e.state) {
            "open" -> "開"
            "closed" -> "閉"
            "opening" -> "開いています…"
            "closing" -> "閉じています…"
            else -> e.state
        }
        "climate" -> {
            val cur = e.attributes.num("current_temperature")
            if (cur == null) hvacLabel(e.state) else "${hvacLabel(e.state)}・室温 ${fmt(cur)}℃"
        }
        "light" -> if (e.state == "on") {
            e.attributes.num("brightness")?.let { "オン ${(it / 255 * 100).roundToInt()}%" } ?: "オン"
        } else "オフ"
        else -> when (e.state) {
            "on" -> "オン"
            "off" -> "オフ"
            else -> e.state
        }
    }
}
