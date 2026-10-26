@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package jp.home.panel

import androidx.compose.foundation.layout.heightIn

import androidx.compose.foundation.clickable

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
    val diag by vm.elDiagnostics.collectAsState()
    var ipInput by rememberSaveable { mutableStateOf("") }
    var ipError by remember { mutableStateOf<String?>(null) }
    var rename by remember { mutableStateOf<ElDevice?>(null) }
    var linking by remember { mutableStateOf<ElDevice?>(null) }
    val links by vm.elLinks.collectAsState()
    val mode by vm.ctlMode.collectAsState()
    val states by vm.allStates.collectAsState()
    var linkMsg by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SwitchRow(
            "ECHONET Lite の機器と直接通信する",
            enabled,
            sub = "家の Wi-Fi の中で、エアコン・照明・電動カーテン・シャッター・換気扇などの ECHONET Lite 対応機器を探して、" +
                "Home Assistant を通さずに操作します。Home Assistant が止まっていても使えます。",
        ) { vm.setElEnabled(it) }

        if (enabled) {
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(onClick = { vm.discoverEchonet(false) }, enabled = !busy) { Text("🔍 機器を探す") }
                androidx.compose.material3.OutlinedButton(onClick = { vm.discoverEchonet(true) }, enabled = !busy) {
                    Text("🔎 すべてのアドレスに問い合わせ")
                }
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            Text(
                "見つからないときは「すべてのアドレスに問い合わせ」を試してください。ルーターが一斉の呼びかけ（マルチキャスト）を通さなくても、1台ずつ直接問い合わせて探します（20秒ほどかかります）。",
                style = MaterialTheme.typography.bodySmall,
            )
            status?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            if (diag == null && !busy) {
                Text("「機器を探す」か、下の IP アドレスの「調べる」を押すと、ここに診断結果が出ます。", style = MaterialTheme.typography.bodySmall)
            }
            diag?.let { d ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(10.dp)) {
                        Text("診断結果", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Text(d, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

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
                                            ElKind.WATER_HEATER -> "・おひさま沸き上げで使います"
                                            else -> ""
                                        },
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            if (d.haDomain in SUPPORTED_DOMAINS) {
                                TextButton(onClick = { linking = d }) { Text(if (links.containsKey(d.entityId)) "🔗 連携中" else "🔗 連携") }
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

                // ---- Home Assistant の機器との連携（家の中は ECHONET Lite、外出先は Home Assistant） ----
                Text("Home Assistant の機器との連携", style = MaterialTheme.typography.labelLarge)
                Text(
                    "ECHONET Lite の機器を Home Assistant の同じ機器と「🔗 連携」すると、ホーム画面では1つにまとまり、" +
                        "家の中では ECHONET Lite で直接（速く、Home Assistant が止まっていても動く）、外出先では Home Assistant 経由で操作します。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.OutlinedButton(onClick = {
                        val n = vm.autoLinkEl()
                        linkMsg = if (n > 0) "${n}台を自動で連携しました。違っていれば「🔗」から直してください。" else "自動で連携できる機器はありませんでした。「🔗 連携」から選んでください。"
                    }) { Text("自動で連携") }
                    Text("連携中：${links.size}台", style = MaterialTheme.typography.bodySmall)
                }
                linkMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Text("操作のしかた", style = MaterialTheme.typography.labelMedium)
                listOf(
                    "auto" to "自動：家の中は ECHONET Lite、外出先は Home Assistant（おすすめ）",
                    "echonet" to "いつも ECHONET Lite（家の中だけ）",
                    "ha" to "いつも Home Assistant（つながらないときだけ ECHONET Lite）",
                ).forEach { (k, t) ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { vm.setCtlMode(k) }) {
                        androidx.compose.material3.RadioButton(selected = mode == k, onClick = { vm.setCtlMode(k) })
                        Text(t, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Text("ホーム画面の上部の「操作：…」でも切り替えられます。", style = MaterialTheme.typography.bodySmall)
            }

            Text("見つからない機器を IP アドレスで追加", style = MaterialTheme.typography.labelLarge)
            Text(
                "ルーターによっては、機器を探す呼びかけ（マルチキャスト）が届かないことがあります。その場合は、機器（またはその機器の HEMS アダプター）の IP アドレスを入れてください。",
                style = MaterialTheme.typography.bodySmall,
            )
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
                    TextButton(onClick = { vm.probeEl(ip) }, enabled = !busy) { Text("🩺 調べる") }
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

    linking?.let { d ->
        val cands = remember(d.key) { vm.linkCandidates(d) }
        AlertDialog(
            onDismissRequest = { linking = null },
            title = { Text("「${d.name}」と連携する機器") },
            text = {
                androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    item {
                        Text("Home Assistant の同じ機器を選んでください（名前が似ている順）。", style = MaterialTheme.typography.bodySmall)
                    }
                    items(cands.size) { i ->
                        val e = cands[i]
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { vm.linkEl(d.entityId, e.entityId); linking = null }) {
                            androidx.compose.material3.RadioButton(selected = links[d.entityId] == e.entityId, onClick = { vm.linkEl(d.entityId, e.entityId); linking = null })
                            Column {
                                Text(e.name, style = MaterialTheme.typography.bodyLarge)
                                Text(e.entityId, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    if (cands.isEmpty()) item { Text("同じ種類の機器が Home Assistant にありません。", style = MaterialTheme.typography.bodyMedium) }
                }
            },
            confirmButton = { TextButton(onClick = { linking = null }) { Text("閉じる") } },
            dismissButton = {
                if (links.containsKey(d.entityId)) TextButton(onClick = { vm.linkEl(d.entityId, null); linking = null }) { Text("連携を解除") }
            },
        )
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
