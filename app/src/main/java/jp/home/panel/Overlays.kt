@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package jp.home.panel

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import org.json.JSONObject

// ================= タイマー =================

/** 動いているタイマーの残り時間（ホーム画面に常に表示） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TimerStrip(vm: MainViewModel, onOpen: () -> Unit) {
    val timers by vm.timers.collectAsState()
    if (timers.isEmpty()) return
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(250) } }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        timers.forEach { t ->
            AssistChip(
                onClick = onOpen,
                label = {
                    Text(
                        "⏱ " + (if (t.label.isNotBlank()) t.label + " " else "") + formatRemaining(t.remaining(now)) +
                            if (t.running) "" else "（停止中）",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                    )
                },
            )
        }
    }
}

/** タイマーの設定・一覧 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TimerDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    val timers by vm.timers.collectAsState()
    var minutes by rememberSaveable { mutableIntStateOf(3) }
    var seconds by rememberSaveable { mutableIntStateOf(0) }
    var label by rememberSaveable { mutableStateOf("") }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(250) } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("⏱ タイマー") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("すぐ始める", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(1, 3, 5, 10, 15, 30, 60).forEach { m ->
                        FilledTonalButton(onClick = { vm.addTimer(m * 60L, label); label = "" }) {
                            Text(if (m >= 60) "${m / 60}時間" else "${m}分")
                        }
                    }
                }
                Text("時間を指定", style = MaterialTheme.typography.labelLarge)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { minutes = (minutes - 1).coerceAtLeast(0) }) { Text("−") }
                    Text("${minutes}分", fontSize = 22.sp)
                    TextButton(onClick = { minutes += 1 }) { Text("＋") }
                    TextButton(onClick = { seconds = (seconds - 10 + 60) % 60 }) { Text("−") }
                    Text("${seconds}秒", fontSize = 22.sp)
                    TextButton(onClick = { seconds = (seconds + 10) % 60 }) { Text("＋") }
                }
                OutlinedTextField(label, { label = it }, singleLine = true, label = { Text("名前（例：パスタ）※なくてもOK") })
                Button(
                    onClick = { vm.addTimer(minutes * 60L + seconds, label); label = "" },
                    enabled = minutes * 60 + seconds > 0,
                ) { Text("スタート") }
                if (timers.isNotEmpty()) {
                    Text("動いているタイマー", style = MaterialTheme.typography.labelLarge)
                    timers.forEach { t ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(formatRemaining(t.remaining(now)), fontSize = 24.sp, fontWeight = FontWeight.Medium)
                                Text(
                                    (t.label.ifBlank { "タイマー" }) + "（" + speakRemaining(t.totalMs) + "）",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            TextButton(onClick = { vm.pauseResumeTimer(t.id) }) { Text(if (t.running) "一時停止" else "再開") }
                            TextButton(onClick = { vm.cancelTimer(t.id) }) { Text("取消") }
                        }
                    }
                }
                DeviceTimerList(vm)
                Text("音声でも「5分のタイマー」「パスタの3分タイマー」「タイマーの残りは？」「タイマーを止めて」と言えます。", style = MaterialTheme.typography.bodySmall)
                Text("エアコンや照明などを「1時間後に切る」「6時半に暖房」にするときは、その機器の名前をタップして開く画面の「⏲ タイマー」から設定します。", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("閉じる") } },
    )
}

/** 時間になったタイマー（どの画面の上にも出す） */
@Composable
fun AlarmOverlay(vm: MainViewModel) {
    val ringing by vm.ringing.collectAsState()
    if (ringing.isEmpty()) return
    Box(
        Modifier.fillMaxSize().background(Color(0xE6000000)).clickable(
            interactionSource = remember { MutableInteractionSource() }, indication = null,
        ) { vm.stopAlarm() },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("⏰", fontSize = 80.sp)
            Text("タイマーが終わりました", fontSize = 34.sp, color = Color.White, fontWeight = FontWeight.Medium)
            ringing.forEach {
                Text(
                    (it.label.ifBlank { "タイマー" }) + "（" + speakRemaining(it.totalMs) + "）",
                    fontSize = 22.sp, color = Color.White,
                )
            }
            Button(onClick = vm::stopAlarm, modifier = Modifier.widthIn(min = 200.dp)) { Text("止める", fontSize = 22.sp) }
        }
    }
}

// ================= スクリーンセーバー =================

/** 操作がないときに出す写真のスライドショー（写真が無ければ時計だけ） */
@Composable
fun ScreensaverOverlay(vm: MainViewModel) {
    val on by vm.screensaver.collectAsState()
    if (!on) return
    val photos by vm.photos.collectAsState()
    val interval by vm.ssInterval.collectAsState()
    val weather by vm.weather.collectAsState()
    val forecast by vm.forecast.collectAsState()
    val timers by vm.timers.collectAsState()
    var index by remember { mutableIntStateOf(0) }
    LaunchedEffect(photos, interval) {
        while (photos.isNotEmpty()) {
            delay(interval * 1000L)
            index = (index + 1) % photos.size
        }
    }
    val now = rememberNow()
    var tick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { tick = System.currentTimeMillis(); delay(1000) } }

    BoxWithConstraints(
        Modifier.fillMaxSize().background(Color.Black).clickable(
            interactionSource = remember { MutableInteractionSource() }, indication = null,
        ) { vm.onUserInteraction() },
    ) {
        val density = LocalDensity.current
        val w = with(density) { maxWidth.roundToPx() }.coerceAtMost(1920)
        val h = with(density) { maxHeight.roundToPx() }.coerceAtMost(1920)
        if (photos.isNotEmpty()) {
            Crossfade(targetState = index % photos.size, animationSpec = tween(1500), label = "photo") { i ->
                AsyncImage(
                    model = photoUrl(photos[i], w, h),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        // 時計・天気・タイマー（左下）
        Column(
            Modifier.align(Alignment.BottomStart).padding(28.dp)
                .background(Color(0x66000000), MaterialTheme.shapes.large).padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            Text(now.format(TIME_FMT), fontSize = 72.sp, color = Color.White, fontWeight = FontWeight.Light)
            Text(now.format(DATE_FMT), fontSize = 22.sp, color = Color.White)
            ScreensaverWeather(weather, forecast)
            timers.forEach {
                Text(
                    "⏱ " + (if (it.label.isNotBlank()) it.label + " " else "") + formatRemaining(it.remaining(tick)),
                    fontSize = 22.sp, color = Color.White,
                )
            }
        }
    }
}

@Composable
private fun ScreensaverWeather(weather: Entity?, forecast: List<JSONObject>) {
    if (weather == null || weather.unavailable) return
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides Color.White) {
        WeatherBlock(weather, forecast, Modifier, showDays = false)
    }
}

// ================= マイクテスト・動作ログ（設定画面） =================

@Composable
fun VoiceDiagnostics(vm: MainViewModel) {
    val level by vm.micLevel.collectAsState()
    val result by vm.micTestResult.collectAsState()
    val log by vm.voiceLog.collectAsState()
    var showLog by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FilledTonalButton(onClick = vm::runMicTest, enabled = level == null) { Text("🎤 マイクのテスト（5秒）") }
            TextButton(onClick = { showLog = !showLog }) { Text(if (showLog) "動作ログを閉じる" else "音声の動作ログを見る") }
        }
        level?.let { l ->
            Box(Modifier.fillMaxWidth().height(14.dp).background(MaterialTheme.colorScheme.surfaceVariant)) {
                Box(Modifier.fillMaxWidth(l / 100f).height(14.dp).background(MaterialTheme.colorScheme.primary))
            }
        }
        result?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        if (showLog) {
            Column(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.medium).padding(10.dp),
            ) {
                if (log.isEmpty()) Text("まだ記録はありません。マイクボタンや呼びかけを試すと、ここに流れが表示されます。", style = MaterialTheme.typography.bodySmall)
                log.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
