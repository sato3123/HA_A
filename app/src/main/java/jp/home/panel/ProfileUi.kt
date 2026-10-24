package jp.home.panel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp

/** ホーム画面上部の「👤 名前 ▾」（表示のユーザーの切り替え） */
@Composable
fun ProfileSwitcher(profiles: List<Profile>, current: Profile, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text("👤 ${current.name} ▾") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            profiles.forEach { p ->
                DropdownMenuItem(
                    text = { Text((if (p.id == current.id) "✓ " else "　") + p.name) },
                    onClick = { open = false; onSelect(p.id) },
                )
            }
        }
    }
}

/** オートメーション・シーン・スクリプトをすぐ実行するボタン */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AutomationQuick(vm: MainViewModel) {
    val states by vm.allStates.collectAsState()
    var done by remember { mutableStateOf<String?>(null) }
    val list = states.values.filter { it.domain in setOf("automation", "scene", "script") && !it.unavailable }
        .sortedWith(compareBy({ listOf("scene", "script", "automation").indexOf(it.domain) }, { it.name }))
    if (list.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("⚡ すぐ実行", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            list.forEach { e ->
                FilledTonalButton(onClick = {
                    if (e.domain == "automation") vm.runAutomation(e) else vm.call(e, "turn_on")
                    done = "「${e.name}」を実行しました"
                }) {
                    Text(
                        (when (e.domain) { "scene" -> "🎬 "; "script" -> "▶ "; else -> if (e.state == "on") "⚡ " else "⏸ " }) + e.name,
                    )
                }
            }
        }
        done?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

/** 設定画面：表示のユーザーと、ホーム画面の並び順 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ProfileSettings(vm: MainViewModel) {
    val profiles by vm.profiles.collectAsState()
    val current by vm.activeProfile.collectAsState()
    var newName by rememberSaveable { mutableStateOf("") }
    var rename by remember { mutableStateOf<Profile?>(null) }
    var confirmDelete by remember { mutableStateOf<Profile?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "家族それぞれが、ホーム画面の並び順・表示する項目・配色を自分用に変えられます。" +
                "ホーム画面上部の「👤 名前」から切り替えます（この端末の中だけの設定で、ログインとは別です）。",
            style = MaterialTheme.typography.bodySmall,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            profiles.forEach { p ->
                FilterChip(p.id == current.id, { vm.switchProfile(p.id) }, { Text("👤 " + p.name) })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                newName, { newName = it },
                modifier = Modifier.widthIn(max = 280.dp),
                singleLine = true,
                label = { Text("ユーザーを追加（例：お父さん）") },
            )
            TextButton(onClick = { vm.addProfile(newName); newName = "" }, enabled = newName.isNotBlank()) { Text("追加") }
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.fillMaxWidth().widthIn(max = 640.dp).let { it }.then(Modifier), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "「${current.name}」のホーム画面（上から順に表示）",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f).then(Modifier),
                    )
                    TextButton(onClick = { rename = current }) { Text("名前を変える") }
                    if (profiles.size > 1) TextButton(onClick = { confirmDelete = current }) { Text("削除") }
                }
                current.sections.forEachIndexed { i, sp ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}. ${sp.section.label}", modifier = Modifier.weight(1f))
                        TextButton(onClick = { vm.moveSection(sp.section, -1) }, enabled = i > 0) { Text("▲") }
                        TextButton(onClick = { vm.moveSection(sp.section, 1) }, enabled = i < current.sections.size - 1) { Text("▼") }
                        Switch(checked = sp.visible, onCheckedChange = { vm.toggleSection(sp.section) })
                    }
                }
                Text(
                    "配色は上の「配色」で選ぶと、このユーザーの配色として覚えます。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }

    rename?.let { p ->
        var name by remember(p.id) { mutableStateOf(p.name) }
        AlertDialog(
            onDismissRequest = { rename = null },
            title = { Text("ユーザーの名前") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { vm.renameProfile(p.id, name); rename = null }) { Text("保存") } },
            dismissButton = { TextButton(onClick = { rename = null }) { Text("キャンセル") } },
        )
    }
    confirmDelete?.let { p ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("「${p.name}」を削除しますか？") },
            text = { Text("このユーザーの並び順と配色の設定が消えます。") },
            confirmButton = { TextButton(onClick = { vm.deleteProfile(p.id); confirmDelete = null }) { Text("削除") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("キャンセル") } },
        )
    }
}

/** 設定画面：設定の書き出し・読み込み */
@Composable
fun BackupSettings(vm: MainViewModel) {
    val msg by vm.backupMessage.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "このアプリの設定（表示する機器・部屋・ユーザーごとの表示・配色・音声・スクリーンセーバー・自宅の位置など）を、" +
                "Android の「ファイル」（ダウンロードフォルダなど）に書き出せます。別の端末や入れ直したときに読み込めば、同じ設定に戻せます。" +
                "ログイン情報・外出先用URLなどの接続情報は書き出しません。",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.requestFile("export") }) { Text("📤 設定を書き出す") }
            OutlinedButton(onClick = { vm.requestFile("import") }) { Text("📥 設定を読み込む") }
        }
        msg?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
}
