package jp.home.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun App(vm: MainViewModel, onMic: () -> Unit) {
    val screen by vm.screen.collectAsState()
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (val s = screen) {
            Screen.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            Screen.Login -> LoginScreen(vm)
            is Screen.Blocked -> BlockedScreen(s.reason, onRetry = vm::start, onLogout = vm::logout)
            Screen.Home -> HomeScreen(vm, onMic)
        }
    }
}

@Composable
private fun LoginScreen(vm: MainViewModel) {
    var url by rememberSaveable { mutableStateOf(vm.savedUrl.ifEmpty { "http://192.168.1.10:8123" }) }
    var user by rememberSaveable { mutableStateOf("") }
    var pass by rememberSaveable { mutableStateOf("") }
    var otp by rememberSaveable { mutableStateOf("") }
    val busy by vm.loginBusy.collectAsState()
    val error by vm.loginError.collectAsState()
    val needOtp by vm.needOtp.collectAsState()
    val field = Modifier.widthIn(max = 480.dp).fillMaxWidth()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.heightIn(min = 24.dp))
        Text("ホームパネル", style = MaterialTheme.typography.headlineMedium)
        Text(
            "最初の1回だけ Home Assistant にログインします。\nパスワードは保存せず、ログイン情報は端末内に暗号化して保存します。",
            style = MaterialTheme.typography.bodyMedium,
            modifier = field,
        )
        OutlinedTextField(
            url, { url = it }, modifier = field, singleLine = true,
            label = { Text("Home Assistant のURL（家の中のアドレス）") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        OutlinedTextField(user, { user = it }, modifier = field, singleLine = true, label = { Text("ユーザー名") })
        OutlinedTextField(
            pass, { pass = it }, modifier = field, singleLine = true, label = { Text("パスワード") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        if (needOtp) {
            OutlinedTextField(
                otp, { otp = it }, modifier = field, singleLine = true, label = { Text("2段階認証コード") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            )
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = field) }
        Button(
            onClick = { vm.login(url, user, pass, otp) },
            enabled = !busy && user.isNotBlank() && pass.isNotBlank(),
            modifier = field.heightIn(min = 52.dp),
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("ログイン")
        }
    }
}

@Composable
private fun BlockedScreen(reason: String, onRetry: () -> Unit, onLogout: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("🏠", fontSize = 56.sp)
        Text("このアプリは家の中でのみ使えます", style = MaterialTheme.typography.titleLarge)
        Text(reason, style = MaterialTheme.typography.bodyLarge)
        Button(onClick = onRetry) { Text("もう一度確認する") }
        TextButton(onClick = onLogout) { Text("ログアウト") }
    }
}

@Composable
private fun HomeScreen(vm: MainViewModel, onMic: () -> Unit) {
    val entities by vm.entities.collectAsState()
    val conn by vm.conn.collectAsState()
    val message by vm.message.collectAsState()
    val voice by vm.voice.collectAsState()
    var text by remember { mutableStateOf("") }
    var confirmLogout by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("ホームパネル", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            ConnectionBadge(conn)
            TextButton(onClick = { confirmLogout = true }) { Text("ログアウト") }
        }
        message?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.size(8.dp))
        VoicePanel(
            voice = voice,
            enabled = conn == Conn.CONNECTED,
            onMic = onMic,
            text = text,
            onTextChange = { text = it },
            onSend = { vm.sendText(text); text = "" },
        )
        Spacer(Modifier.size(12.dp))
        if (entities.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (conn == Conn.CONNECTED) Text("操作できる機器がありません") else CircularProgressIndicator()
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(160.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 16.dp),
                modifier = Modifier.weight(1f),
            ) {
                items(entities, key = { it.entityId }) { e ->
                    EntityCard(e, onClick = { vm.toggle(e) }, onAdjust = { d -> vm.adjustClimate(e, d) })
                }
            }
        }
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("ログアウトしますか？") },
            text = { Text("もう一度使うにはユーザー名とパスワードが必要になります。") },
            confirmButton = {
                TextButton(onClick = { confirmLogout = false; vm.logout() }) { Text("ログアウト") }
            },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("キャンセル") } },
        )
    }
}

@Composable
private fun ConnectionBadge(conn: Conn) {
    val (color, label) = when (conn) {
        Conn.CONNECTED -> Color(0xFF66BB6A) to "接続中"
        Conn.CONNECTING -> Color(0xFFFFCA28) to "接続しています"
        Conn.RETRYING -> Color(0xFFEF5350) to "再接続待ち"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Text(" $label", style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun VoicePanel(
    voice: VoiceState,
    enabled: Boolean,
    onMic: () -> Unit,
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    val listening = voice == VoiceState.Listening
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = onMic,
                    enabled = enabled,
                    shape = CircleShape,
                    modifier = Modifier.size(72.dp),
                    contentPadding = PaddingValues(0.dp),
                    colors = if (listening) ButtonDefaults.buttonColors(containerColor = Color(0xFFEF5350))
                    else ButtonDefaults.buttonColors(),
                ) { Text(if (listening) "■" else "🎤", fontSize = 28.sp) }
                Spacer(Modifier.size(12.dp))
                Text(
                    when (voice) {
                        VoiceState.Idle -> "マイクを押して話しかけてください\n例：「リビングの電気をつけて」"
                        VoiceState.Listening -> "聞いています…（話し終わったら■で終了）"
                        is VoiceState.Processing -> "「${voice.heard}」を処理しています…"
                        is VoiceState.Done -> "「${voice.heard}」\n→ ${voice.reply.ifBlank { "完了しました" }}"
                        is VoiceState.Failed -> voice.message
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = text,
                    onValueChange = onTextChange,
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("文字で命令する") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { onSend() }),
                )
                TextButton(onClick = onSend, enabled = enabled && text.isNotBlank()) { Text("送信") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EntityCard(e: Entity, onClick: () -> Unit, onAdjust: (Double) -> Unit) {
    val unavailable = e.state == "unavailable" || e.state == "unknown"
    val active = e.isOn && e.domain !in setOf("scene", "script")
    Card(
        onClick = onClick,
        enabled = !unavailable,
        modifier = Modifier.fillMaxWidth().heightIn(min = 104.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (active) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(domainLabel(e.domain), style = MaterialTheme.typography.labelSmall)
            Text(e.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(stateLabel(e), style = MaterialTheme.typography.bodyMedium)
            val target = e.attributes.optDouble("temperature")
            if (e.domain == "climate" && !target.isNaN() && e.state != "off") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { onAdjust(-0.5) }) { Text("−", fontSize = 20.sp) }
                    Text("$target℃")
                    TextButton(onClick = { onAdjust(0.5) }) { Text("＋", fontSize = 20.sp) }
                }
            }
        }
    }
}

private fun domainLabel(domain: String) = when (domain) {
    "light" -> "照明"
    "switch" -> "スイッチ"
    "fan" -> "ファン"
    "cover" -> "カーテン・シャッター"
    "climate" -> "エアコン"
    "input_boolean" -> "切り替え"
    "scene" -> "シーン"
    "script" -> "スクリプト"
    else -> domain
}

private fun stateLabel(e: Entity): String {
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
            val mode = when (e.state) {
                "off" -> "停止"
                "heat" -> "暖房"
                "cool" -> "冷房"
                "auto", "heat_cool" -> "自動"
                "dry" -> "除湿"
                "fan_only" -> "送風"
                else -> e.state
            }
            val cur = e.attributes.optDouble("current_temperature")
            if (cur.isNaN()) mode else "$mode・室温 $cur℃"
        }
        else -> when (e.state) {
            "on" -> "オン"
            "off" -> "オフ"
            else -> e.state
        }
    }
}
