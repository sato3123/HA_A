@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package jp.home.panel

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.LocalDate

/** ホーム画面の「今日・明日の予定」（1行ずつ。タップで1週間分） */
@Composable
fun CalendarStrip(vm: MainViewModel, onOpen: () -> Unit) {
    val events by vm.events.collectAsState()
    val cals by vm.calendarEntities.collectAsState()
    if (cals.isEmpty() || vm.selectedCalendars().isEmpty()) return
    val today = LocalDate.now()
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth().clickable { onOpen() },
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            listOf(0L, 1L).forEach { off ->
                val day = today.plusDays(off)
                val list = events.filter { it.isOn(day) }
                Column(Modifier.weight(1f)) {
                    Text("📅 ${dayLabel(day)}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    if (list.isEmpty()) {
                        Text("予定なし", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        list.take(3).forEach { e ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(e.timeText(), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, modifier = Modifier.width(44.dp))
                                Text(e.summary, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        if (list.size > 3) Text("ほか${list.size - 3}件", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

/** 1週間分の予定 */
@Composable
fun CalendarDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    val events by vm.events.collectAsState()
    val today = LocalDate.now()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("📅 1週間の予定") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                (0L until 7L).forEach { off ->
                    val day = today.plusDays(off)
                    val list = events.filter { it.isOn(day) }
                    Text(dayLabel(day, today) + if (off <= 1) "（" + day.format(java.time.format.DateTimeFormatter.ofPattern("M/d（E）", java.util.Locale.JAPAN)) + "）" else "",
                        style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    if (list.isEmpty()) Text("予定なし", style = MaterialTheme.typography.bodySmall)
                    list.forEach { e ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(e.timeText(), fontWeight = FontWeight.Medium, modifier = Modifier.width(48.dp))
                            Column {
                                Text(e.summary)
                                val sub = listOfNotNull(e.location.takeIf { it.isNotBlank() }, e.calendarName)
                                Text(sub.joinToString("・"), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("閉じる") } },
    )
}

/** 設定画面：TimeTree の連携（ログイン情報は Home Assistant 側にだけ置く） */
@Composable
fun CalendarSettings(vm: MainViewModel) {
    val cals by vm.calendarEntities.collectAsState()
    val sel by vm.calendarSel.collectAsState()
    val status by vm.eventsStatus.collectAsState()
    val selected = sel ?: vm.selectedCalendars().map { it.entityId }.toSet()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "TimeTree の予定を Home Assistant 経由で表示します。TimeTree のメールアドレスやパスワードは Home Assistant にだけ登録し、" +
                "このアプリには保存しません（アプリに保存するのは、表示するカレンダーの選択だけです）。",
            style = MaterialTheme.typography.bodySmall,
        )
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Home Assistant 側の準備（1回だけ）", style = MaterialTheme.typography.labelLarge)
                listOf(
                    "1. HACS を入れていなければ入れる（Home Assistant のコミュニティ製ストア）",
                    "2. HACS →「⋮」→「カスタムリポジトリ」に github.com/C0wb0yEngineer/timetree-homeassistant を「Integration」として追加し、ダウンロード",
                    "3. Home Assistant を再起動",
                    "4.「設定 → デバイスとサービス → 統合を追加 → TimeTree」で、TimeTree のメールアドレスとパスワードを入力し、カレンダーを選ぶ",
                    "5. このアプリでカレンダーにチェックを入れる（「calendar.timetree_…」という名前で出てきます）",
                ).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                Text(
                    "※ TimeTree の公式の連携（API）は 2023年12月で終了しているため、この連携は個人が作った非公式のものです。TimeTree 側の変更で使えなくなることがあります。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (cals.isEmpty()) {
            Text("Home Assistant にカレンダーがまだありません。上の手順で TimeTree を追加すると、ここに表示されます。", style = MaterialTheme.typography.bodyMedium)
        } else {
            Text("ホーム画面に予定を出すカレンダー", style = MaterialTheme.typography.labelLarge)
            cals.forEach { c ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable { vm.toggleCalendar(c.entityId) },
                ) {
                    Checkbox(checked = c.entityId in selected, onCheckedChange = { vm.toggleCalendar(c.entityId) })
                    Column {
                        Text(c.name, style = MaterialTheme.typography.bodyLarge)
                        Text(c.entityId, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(onClick = vm::loadEvents) { Text("予定を読み込み直す") }
                status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            Text("音声で「今日の予定は？」「明日の予定」と聞くと、ここで選んだカレンダーの予定を読み上げます。", style = MaterialTheme.typography.bodySmall)
        }
    }
}
