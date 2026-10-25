package jp.home.panel

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** マイクボタンとマイクレベルだけの小さな操作部（時計の横に置く） */
@Composable
fun MicControl(voice: VoiceState, micLevel: Int?, waiting: Boolean, enabled: Boolean, onMic: () -> Unit, compact: Boolean = false) {
    val listening = voice == VoiceState.Listening
    val busy = voice is VoiceState.Processing || voice is VoiceState.Speaking
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp)) {
        // マイクレベル（録音中だけ）。音が入っていなければ赤
        Box(
            Modifier.width(if (compact) 48.dp else 90.dp).height(8.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
        ) {
            if (micLevel != null) {
                Box(
                    Modifier.fillMaxWidth((micLevel.coerceIn(0, 100) / 100f).coerceAtLeast(0.04f)).height(8.dp)
                        .background(if (micLevel == 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, CircleShape),
                )
            }
        }
        Button(
            onClick = onMic,
            enabled = enabled,
            shape = CircleShape,
            modifier = Modifier.size(if (compact) 48.dp else 60.dp),
            contentPadding = PaddingValues(0.dp),
            colors = when {
                listening -> ButtonDefaults.buttonColors(containerColor = Color(0xFFEF5350))
                busy -> ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                waiting && voice == VoiceState.WaitingWake -> ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                else -> ButtonDefaults.buttonColors()
            },
        ) {
            Text(
                when {
                    listening -> "■"
                    voice is VoiceState.Speaking -> "🔇"
                    else -> "🎤"
                },
                fontSize = if (compact) 20.sp else 24.sp,
            )
        }
    }
}

/**
 * 聞き取った言葉と回答のポップアップ。
 * 処理中から表示し、回答が終わったら少しして自動で閉じる（タップでも閉じる）。
 */
@Composable
fun VoicePopup(vm: MainViewModel) {
    val voice by vm.voice.collectAsState()
    var hiddenFor by remember { mutableStateOf<VoiceState?>(null) }
    val show = when (voice) {
        is VoiceState.Processing, is VoiceState.Speaking, is VoiceState.Done, is VoiceState.Failed -> hiddenFor !== voice
        else -> false
    }
    LaunchedEffect(voice) {
        when (val v = voice) {
            is VoiceState.Done -> {
                delay((8_000L + v.reply.length * 120L).coerceAtMost(30_000L))
                hiddenFor = v
            }
            is VoiceState.Failed -> {
                delay(7_000L)
                hiddenFor = v
            }
            else -> {}
        }
    }
    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(show, enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut() + slideOutVertically { it / 2 }) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth().clickable {
                    hiddenFor = voice
                    vm.onUserInteraction()
                },
            ) {
                Box {
                androidx.compose.material3.TextButton(
                    onClick = { hiddenFor = voice; vm.onUserInteraction() },
                    modifier = Modifier.align(Alignment.TopEnd),
                ) { Text("✕ 閉じる") }
                Column(
                    Modifier.padding(start = 18.dp, end = 18.dp, top = 40.dp, bottom = 18.dp).heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    when (val v = voice) {
                        is VoiceState.Processing -> {
                            Text("「${v.heard}」", style = MaterialTheme.typography.titleMedium)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                Text("考えています…", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        is VoiceState.Speaking -> {
                            Text("「${v.heard}」", style = MaterialTheme.typography.titleMedium)
                            Text(v.reply.ifBlank { "完了しました" }, style = MaterialTheme.typography.bodyLarge)
                            Text("🔊 マイクを押すと読み上げを止めて、次の質問を聞きます", style = MaterialTheme.typography.labelSmall)
                        }
                        is VoiceState.Done -> {
                            Text("「${v.heard}」", style = MaterialTheme.typography.titleMedium)
                            Text(v.reply.ifBlank { "完了しました" }, style = MaterialTheme.typography.bodyLarge)
                        }
                        is VoiceState.Failed -> Text(v.message, style = MaterialTheme.typography.bodyLarge)
                        else -> {}
                    }
                }
                }
            }
        }
    }
}
