package jp.home.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val LEVEL_COLORS = listOf(Color(0xFF64B5F6), Color(0xFF4DD0E1), Color(0xFF81C784), Color(0xFFFFB74D), Color(0xFFE57373))

/** ホーム画面の「温度・湿度」（SwitchBot 温湿度計など）。タップで詳細（履歴） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ThermoStrip(vm: MainViewModel) {
    val list by vm.thermos.collectAsState()
    val hidden by vm.thermoHidden.collectAsState()
    val shown = list.filter { it.key !in hidden }
    if (shown.isEmpty()) return
    val compact = LocalConfiguration.current.screenWidthDp < 600
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        shown.forEach { t ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier
                    .width(if (compact) 160.dp else 176.dp)
                    .clickable(enabled = t.tempEntity != null || t.humEntity != null) {
                        vm.showInfo(t.tempEntity ?: t.humEntity!!)
                    },
            ) {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).background(LEVEL_COLORS[tempLevel(t.temperature)], CircleShape))
                    Column(Modifier.padding(start = 8.dp)) {
                        Text(t.name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                if (t.unavailable) "－" else t.temperature?.let { fmt1(it) + "℃" } ?: "－",
                                fontSize = 22.sp, fontWeight = FontWeight.Medium,
                            )
                            t.humidity?.let { Text("💧" + fmt1(it).removeSuffix(".0") + "%", style = MaterialTheme.typography.bodyMedium) }
                        }
                        t.battery?.takeIf { it < 20 }?.let {
                            Text("🔋 電池残り ${it.toInt()}%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}

private fun fmt1(v: Double) = String.format("%.1f", v)

/** 設定画面：ホーム画面に出す温度計 */
@Composable
fun ThermoSettings(vm: MainViewModel) {
    val list by vm.thermos.collectAsState()
    val hidden by vm.thermoHidden.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "Home Assistant に取り込んだ温度計（SwitchBot 温湿度計など）の温度と湿度を、ホーム画面に表示します。" +
                "温度と湿度のセンサーは、同じ機器のものを1つにまとめて表示します。",
            style = MaterialTheme.typography.bodySmall,
        )
        if (list.isEmpty()) {
            Text(
                "温度計が見つかりません。Home Assistant に SwitchBot の連携（SwitchBot Bluetooth または SwitchBot Cloud）を追加すると、ここに表示されます。",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        list.forEach { t ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { vm.toggleThermo(t.key) },
            ) {
                Checkbox(checked = t.key !in hidden, onCheckedChange = { vm.toggleThermo(t.key) })
                Column(Modifier.weight(1f)) {
                    Text(t.name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        listOfNotNull(
                            t.temperature?.let { fmt1(it) + "℃" },
                            t.humidity?.let { fmt1(it) + "%" },
                            t.battery?.let { "電池 ${it.toInt()}%" },
                        ).joinToString("・").ifBlank { "値なし" },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}
