package jp.home.panel

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun App(vm: MainViewModel, onMic: () -> Unit, onEnableWake: () -> Unit) {
    val screen by vm.screen.collectAsState()
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (val s = screen) {
            Screen.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            Screen.Login -> LoginScreen(vm)
            is Screen.Blocked -> BlockedScreen(s.reason, vm)
            Screen.Home -> HomeScreen(vm, onMic, onEnableWake)
        }
        AlarmOverlay(vm)
    }
}

// ================= ログイン =================

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
            "最初の1回だけ、家の中で Home Assistant にログインします。\n" +
                "パスワードは保存せず、ログイン情報は端末内に暗号化して保存します。",
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

// ================= 接続できないとき =================

@Composable
private fun BlockedScreen(reason: String, vm: MainViewModel) {
    var remoteInput by rememberSaveable { mutableStateOf(vm.remoteUrl.value) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("🏠", fontSize = 56.sp)
        Text("Home Assistant に接続できません", style = MaterialTheme.typography.titleLarge)
        Text(reason, style = MaterialTheme.typography.bodyLarge)
        Button(onClick = vm::start) { Text("もう一度試す") }
        OutlinedTextField(
            remoteInput, { remoteInput = it; error = null },
            modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(),
            singleLine = true,
            label = { Text("外出先用URL（https://xxxx.ui.nabu.casa）") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        TextButton(onClick = { error = vm.saveRemoteUrl(remoteInput) }) { Text("外出先用URLを保存して接続") }
        TextButton(onClick = vm::logout) { Text("ログアウト") }
    }
}

// ================= ホーム =================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(vm: MainViewModel, onMic: () -> Unit, onEnableWake: () -> Unit) {
    val entities by vm.entities.collectAsState()
    val conn by vm.conn.collectAsState()
    val route by vm.route.collectAsState()
    val message by vm.message.collectAsState()
    val voice by vm.voice.collectAsState()
    val lastDone by vm.lastDone.collectAsState()
    val wake by vm.wakeEnabled.collectAsState()
    val weather by vm.weather.collectAsState()
    val forecast by vm.forecast.collectAsState()
    val rooms by vm.rooms.collectAsState()
    val overrides by vm.roomOverrides.collectAsState()
    val areas by vm.entityAreas.collectAsState()
    val groupRooms by vm.groupRooms.collectAsState()
    val roomFilter by vm.roomFilter.collectAsState()

    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showAuto by rememberSaveable { mutableStateOf(false) }
    var showTimers by rememberSaveable { mutableStateOf(false) }
    // 初めてホーム画面を開いたときは使い方を自動で表示
    var showHelp by rememberSaveable { mutableStateOf(!vm.helpShown) }
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    var text by remember { mutableStateOf("") }

    BackHandler(enabled = showSettings || showHelp || showAuto) {
        if (showHelp) vm.helpShown = true
        showSettings = false
        showHelp = false
        showAuto = false
    }
    if (showAuto) {
        AutomationScreen(vm, onClose = { showAuto = false })
        return
    }
    if (showHelp) {
        HelpScreen(vm, onClose = { showHelp = false; vm.helpShown = true })
        return
    }
    if (showSettings) {
        SettingsScreen(vm, onClose = { showSettings = false }, onEnableWake = onEnableWake)
        return
    }

    val full: (androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope) -> GridItemSpan =
        { GridItemSpan(it.maxLineSpan) }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(140.dp),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { full(this) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ConnectionBadge(conn, route)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { showHelp = true }) { Text("？ 使い方") }
                TextButton(onClick = { showTimers = true }) { Text("⏱ タイマー") }
                TextButton(onClick = { showAuto = true }) { Text("⏰ 自動化") }
                TextButton(onClick = { showSettings = true }) { Text("⚙ 設定") }
            }
        }
        item(span = { full(this) }) { UpdateCard(vm, showWhenIdle = false) }
        item(span = { full(this) }) { ClockWeather(weather, forecast) }
        item(span = { full(this) }) {
            VoicePanel(
                voice = voice,
                lastDone = lastDone,
                wakeOn = wake && route?.remote == false,
                wakePausedRemote = wake && route?.remote == true,
                enabled = conn == Conn.CONNECTED,
                onMic = onMic,
                text = text,
                onTextChange = { text = it },
                onSend = { vm.sendText(text); text = "" },
            )
        }
        item(span = { full(this) }) { TimerStrip(vm, onOpen = { showTimers = true }) }
        message?.let { m ->
            item(span = { full(this) }) {
                Text(m, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (entities.isEmpty()) {
            item(span = { full(this) }) {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    if (conn == Conn.CONNECTED) {
                        Text("表示する機器がありません（⚙ 設定 から選べます）")
                    } else {
                        CircularProgressIndicator()
                    }
                }
            }
        }
        // ---- 部屋ごとの見出しと絞り込み ----
        val keyOf = { e: Entity -> roomKeyOf(e.entityId, overrides, areas, rooms) }
        val present = groupByRoom(entities, rooms, keyOf).map { it.first }
        val activeFilter = roomFilter?.takeIf { f -> present.any { it.key == f } }
        if (present.size > 1) {
            item(span = { full(this) }) {
                RoomFilterRow(present, activeFilter) { vm.setRoomFilter(it) }
            }
        }
        val shown = if (activeFilter == null) entities else entities.filter { keyOf(it) == activeFilter }
        val sections: List<Pair<Room?, List<Entity>>> =
            if (groupRooms && present.size > 1) groupByRoom(shown, rooms, keyOf) else listOf(null to shown)
        sections.forEach { (room, list) ->
            if (room != null) {
                item(span = { full(this) }, key = "room_" + room.key) {
                    Text(
                        room.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            items(
                list,
                key = { it.entityId },
                span = { GridItemSpan(if (isWide(it)) minOf(2, maxLineSpan) else 1) },
            ) { e ->
                EntityTile(e, vm, onDetail = { detailId = e.entityId })
            }
        }
    }

    if (showTimers) TimerDialog(vm, onDismiss = { showTimers = false })
    ScreensaverOverlay(vm)

    val detail = detailId?.let { id -> entities.firstOrNull { it.entityId == id } }
    if (detail != null) {
        ModalBottomSheet(
            onDismissRequest = { detailId = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            EntityDetail(detail, vm)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun RoomFilterRow(rooms: List<Room>, selected: String?, onSelect: (String?) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FilterChip(selected = selected == null, onClick = { onSelect(null) }, label = { Text("すべて") })
        rooms.forEach { r ->
            FilterChip(selected = selected == r.key, onClick = { onSelect(r.key) }, label = { Text(r.name) })
        }
    }
}

@Composable
private fun ConnectionBadge(conn: Conn, route: Route?) {
    val (color, label) = when (conn) {
        Conn.CONNECTED -> Color(0xFF66BB6A) to if (route?.remote == true) "外出先（Cloud）" else "家の中"
        Conn.CONNECTING -> Color(0xFFFFCA28) to "接続しています"
        Conn.RETRYING -> Color(0xFFEF5350) to "再接続待ち"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Text(" $label", style = MaterialTheme.typography.labelMedium)
    }
}

// ================= 時計と天気 =================

@Composable
fun rememberNow(): LocalDateTime {
    val now by produceState(LocalDateTime.now()) {
        while (true) {
            value = LocalDateTime.now()
            delay(1_000 - System.currentTimeMillis() % 1_000)
        }
    }
    return now
}

/** 時計と天気（コンパクト：1行にまとめて、機器を多く表示できるようにする） */
@Composable
private fun ClockWeather(weather: Entity?, forecast: List<JSONObject>) {
    val now = rememberNow()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth > 560.dp) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                ClockBlock(now)
                WeatherBlock(weather, forecast, Modifier.weight(1f))
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ClockBlock(now)
                WeatherBlock(weather, forecast, Modifier.fillMaxWidth())
            }
        }
    }
}

val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("H:mm", Locale.JAPAN)
val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("M月d日（E）", Locale.JAPAN)
private val DOW_FMT = DateTimeFormatter.ofPattern("E", Locale.JAPAN)

@Composable
private fun ClockBlock(now: LocalDateTime) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(now.format(TIME_FMT), fontSize = 52.sp, fontWeight = FontWeight.Light, lineHeight = 56.sp)
        Text(now.format(DATE_FMT), fontSize = 17.sp, modifier = Modifier.padding(bottom = 8.dp))
    }
}

@Composable
fun WeatherBlock(weather: Entity?, forecast: List<JSONObject>, modifier: Modifier, showDays: Boolean = true) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (weather == null || weather.unavailable) {
            Text("天気の情報がありません", style = MaterialTheme.typography.bodySmall)
            return@Row
        }
        val unit = weather.attributes.optString("temperature_unit", "°C")
        val today = LocalDate.now()
        val days = forecast.mapNotNull { f -> forecastDate(f)?.let { it to f } }
        val todayF = days.firstOrNull { it.first == today }?.second ?: days.firstOrNull()?.second

        Text(conditionEmoji(weather.state), fontSize = 36.sp)
        Column {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                weather.attributes.num("temperature")?.let {
                    Text("${fmt(it)}$unit", fontSize = 26.sp, fontWeight = FontWeight.Light)
                }
                Text(conditionLabel(weather.state), fontSize = 15.sp, modifier = Modifier.padding(bottom = 3.dp))
            }
            val sub = listOfNotNull(
                todayF?.num("temperature")?.let { "最高${fmt(it)}°" },
                todayF?.num("templow")?.let { "最低${fmt(it)}°" },
                todayF?.num("precipitation_probability")?.let { "☂${fmt(it)}%" },
            )
            if (sub.isNotEmpty()) Text(sub.joinToString(" "), style = MaterialTheme.typography.labelMedium)
        }
        if (showDays) {
            val upcoming = days.filter { it.first.isAfter(today) }.take(4)
            Spacer(Modifier.size(4.dp))
            upcoming.forEach { (date, f) ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(date.format(DOW_FMT), style = MaterialTheme.typography.labelSmall)
                    Text(conditionEmoji(f.optString("condition")), fontSize = 18.sp)
                    Text(
                        listOfNotNull(f.num("temperature")?.let { fmt(it) }, f.num("templow")?.let { fmt(it) })
                            .joinToString("/") + "°",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

private fun forecastDate(f: JSONObject): LocalDate? = runCatching {
    OffsetDateTime.parse(f.optString("datetime")).atZoneSameInstant(ZoneId.systemDefault()).toLocalDate()
}.getOrNull()

private fun conditionEmoji(c: String) = when (c) {
    "sunny" -> "☀️"
    "clear-night" -> "🌙"
    "partlycloudy" -> "⛅"
    "cloudy" -> "☁️"
    "fog" -> "🌫️"
    "rainy" -> "🌧️"
    "pouring" -> "🌧️"
    "lightning" -> "⚡"
    "lightning-rainy" -> "⛈️"
    "snowy" -> "❄️"
    "snowy-rainy" -> "🌨️"
    "hail" -> "🧊"
    "windy", "windy-variant" -> "💨"
    "exceptional" -> "⚠️"
    else -> "🌡️"
}

private fun conditionLabel(c: String) = when (c) {
    "sunny" -> "晴れ"
    "clear-night" -> "晴れ（夜）"
    "partlycloudy" -> "晴れ時々くもり"
    "cloudy" -> "くもり"
    "fog" -> "霧"
    "rainy" -> "雨"
    "pouring" -> "大雨"
    "lightning" -> "雷"
    "lightning-rainy" -> "雷雨"
    "snowy" -> "雪"
    "snowy-rainy" -> "みぞれ"
    "hail" -> "ひょう"
    "windy", "windy-variant" -> "強風"
    "exceptional" -> "警戒"
    else -> c
}

// ================= 音声 =================

@Composable
private fun VoicePanel(
    voice: VoiceState,
    lastDone: VoiceState.Done?,
    wakeOn: Boolean,
    wakePausedRemote: Boolean,
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
                    modifier = Modifier.size(56.dp),
                    contentPadding = PaddingValues(0.dp),
                    colors = if (listening) ButtonDefaults.buttonColors(containerColor = Color(0xFFEF5350))
                    else ButtonDefaults.buttonColors(),
                ) { Text(if (listening) "■" else if (voice is VoiceState.Speaking) "🔇" else "🎤", fontSize = 22.sp) }
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        when (voice) {
                            VoiceState.Idle -> if (wakeOn) "呼びかけの準備をしています…"
                            else "マイクを押して話しかけてください\n例：「リビングの電気をつけて」「近くのスーパーの営業時間は？」"
                            VoiceState.WaitingWake -> "呼びかけを待っています（マイクボタンでもすぐに話せます）"
                            VoiceState.Listening -> "聞いています…（話し終わったら■で終了）"
                            is VoiceState.Processing -> "「${voice.heard}」を処理しています…"
                            is VoiceState.Speaking -> "「${voice.heard}」🔊（マイクを押すと止めて次を聞けます）"
                            is VoiceState.Done -> "「${voice.heard}」"
                            is VoiceState.Failed -> voice.message
                        },
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    val reply = (voice as? VoiceState.Done)?.reply ?: (voice as? VoiceState.Speaking)?.reply
                        ?: lastDone?.takeIf { voice == VoiceState.WaitingWake || voice == VoiceState.Idle }?.let {
                            "前回：「${it.heard}」→ ${it.reply}"
                        }
                    reply?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 6,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (wakePausedRemote) {
                        Text(
                            "外出先では通信量を抑えるため呼びかけ待ち受けを止めています",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = text,
                    onValueChange = onTextChange,
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("文字で命令・質問する") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { onSend() }),
                )
                TextButton(onClick = onSend, enabled = enabled && text.isNotBlank()) { Text("送信") }
            }
        }
    }
}
