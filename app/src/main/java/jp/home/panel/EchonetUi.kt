package jp.home.panel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/** 設定画面：ECHONET Lite（Home Assistant を通さない直接通信） */
@Composable
fun EchonetSettings(vm: MainViewModel) {
    val enabled by vm.elEnabled.collectAsState()
    val devices by vm.elDevices.collectAsState()
    val status by vm.elStatus.collectAsState()
    val ips by vm.elManualIps.collectAsState()
    val busy by vm.elBusy.collectAsState()
    var ipInput by rememberSaveable { mutableStateOf("") }
    var ipError by remember { mutableStateOf<String?>(null) }
    var rename by remember { mutableStateOf<ElDevice?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SwitchRow(
            "ECHONET Lite の機器と直接通信する",
            enabled,
            sub = "家の Wi-Fi の中で、エアコン・照明・電動カーテン・シャッター・換気扇などの ECHONET Lite 対応機器を探して、" +
                "Home Assistant を通さずに操作します。Home Assistant が止まっていても使えます。",
        ) { vm.setElEnabled(it) }

        if (enabled) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = vm::discoverEchonet, enabled = !busy) { Text("🔍 機器を探す") }
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            status?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

            if (devices.isNotEmpty()) {
                Text("見つかった機器（${devices.size}）", style = MaterialTheme.typography.labelLarge)
                devices.forEach { d ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            DeviceIcon(d.toEntity(), 36.dp)
                            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                Text(d.name, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "${d.cls.name}・${d.ip}・" + (if (d.online) "応答あり" else "応答なし") +
                                        when (d.cls.kind) {
                                            ElKind.READONLY -> "・表示のみ（安全のため操作しません）"
                                            ElKind.METER, ElKind.SOLAR, ElKind.BATTERY -> "・電気のカードに表示"
                                            else -> ""
                                        },
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            TextButton(onClick = { rename = d }) { Text("名前") }
                            TextButton(onClick = { vm.removeEl(d.key) }) { Text("削除") }
                        }
                    }
                }
                Text(
                    "操作できる機器は、ホーム画面の機器の一覧に一緒に並びます。「表示する機器」で表示・非表示や部屋を変えられます。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Text("見つからない機器を IP アドレスで追加", style = MaterialTheme.typography.labelLarge)
            Text(
                "ルーターによっては、機器を探す呼びかけ（マルチキャスト）が届かないことがあります。その場合は、機器（またはその機器の HEMS アダプター）の IP アドレスを入れてください。",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    ipInput, { ipInput = it; ipError = null },
                    modifier = Modifier.widthIn(max = 260.dp),
                    singleLine = true,
                    label = { Text("例：192.168.1.50") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                TextButton(onClick = {
                    ipError = vm.addElIp(ipInput)
                    if (ipError == null) ipInput = ""
                }) { Text("追加して探す") }
            }
            ipError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            ips.sorted().forEach { ip ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("・$ip", modifier = Modifier.weight(1f))
                    TextButton(onClick = { vm.removeElIp(ip) }) { Text("外す") }
                }
            }
            Text(
                "※ ECHONET Lite には暗証番号などの仕組みがないため、家の Wi-Fi の中だけで使ってください。" +
                    "電気錠・電動ゲート・給湯器などは、安全のため操作の対象から外しています。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    rename?.let { d ->
        var name by remember(d.key) { mutableStateOf(d.name) }
        AlertDialog(
            onDismissRequest = { rename = null },
            title = { Text("機器の名前") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { vm.renameEl(d.key, name); rename = null }) { Text("保存") } },
            dismissButton = { TextButton(onClick = { rename = null }) { Text("キャンセル") } },
        )
    }
}
