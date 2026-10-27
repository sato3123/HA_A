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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
    val status by vm.climateStatus.collectAsState()
    val thermos by vm.thermos.collectAsState()
    val acs = entities.filter { it.domain == "climate" }
    val chip = FilterChipDefaults.filterChipColors(
        selectedContainerColor = MaterialTheme.colorScheme.primary,
        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "温度の範囲を決めておくと、部屋の温度がその範囲を外れたときに、エアコンを自動で冷房・暖房にして範囲内に戻します。" +
                "温度は、エアコンの室温か、部屋に置いた温度計（SwitchBot など）から選べます。このタブレットのアプリが動いている間、1分ごとに確かめます。",
            style = MaterialTheme.typography.bodySmall,
        )
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
}
