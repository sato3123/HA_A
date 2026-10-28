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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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

/** 設定画面：TimeTree の連携（ログイン情報はホームパネルのサーバーにだけ置く） */
@Composable
fun CalendarSettings(vm: MainViewModel) {
    val cals by vm.calendarEntities.collectAsState()
    val sel by vm.calendarSel.collectAsState()
    val status by vm.eventsStatus.collectAsState()
    val selected = sel ?: vm.selectedCalendars().map { it.entityId }.toSet()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "TimeTree の予定を Home Assistant 経由で表示します。ホームパネルのサーバー（${vm.shServer.value}）が30分ごとに TimeTree から予定を取り出し、" +
                "Home Assistant の「Remote Calendar」がそれを読みます。TimeTree のメールアドレスとパスワードはサーバーの中にだけ保存し、" +
                "このアプリには保存しません（アプリに保存するのは、表示するカレンダーの選択だけです）。",
            style = MaterialTheme.typography.bodySmall,
        )
        // サーバーでの取り込みの様子
        var tt by remember { mutableStateOf<org.json.JSONObject?>(null) }
        var ttMsg by remember { mutableStateOf<String?>("サーバーでの取り込みの様子を読んでいます…") }
        LaunchedEffect(Unit) {
            try {
                tt = org.json.JSONObject(String(vm.serverBytes("/calendar/status.json")))
                ttMsg = null
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                ttMsg = "サーバーでの取り込みはまだ設定されていません（下の手順の 1〜2）"
            }
        }
        tt?.let { j ->
            val list = j.optJSONArray("calendars")
            Text("サーバーでの取り込み：最後に取り出した時刻 ${j.optString("at_text")}", style = MaterialTheme.typography.labelLarge)
            for (i in 0 until (list?.length() ?: 0)) {
                val c = list!!.optJSONObject(i) ?: continue
                Text(
                    (if (c.optBoolean("ok")) "✓ " else "✕ ") + c.optString("name") + "：" +
                        (if (c.optBoolean("ok")) "${c.optInt("events")}件" else c.optString("error")),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (c.optBoolean("ok")) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                )
            }
        }
        ttMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("準備（1回だけ）", style = MaterialTheme.typography.labelLarge)
                listOf(
                    "1. サーバーのセットアップ（setup.sh）を新しい版でやり直す",
                    "2. Proxmox のシェルで pct enter 146 → homepanel-calendar setup を実行し、TimeTree のメールアドレス・パスワードと、取り込むカレンダーを選ぶ",
                    "3. 最後に表示される URL（http://192.168.1.46/calendar/〇〇.ics）を、Home Assistant の「設定 → デバイスとサービス → 統合を追加 → Remote Calendar」に1つずつ登録する。" +
                        "カレンダーの名前は「TimeTree 家族」のように TimeTree から始めると、ここで最初から選ばれます",
                    "4. このアプリでカレンダーにチェックを入れる",
                ).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                Text(
                    "※ TimeTree の公式の連携（API）は 2023年12月で終了しているため、非公式の道具（timetree-exporter）を使っています。TimeTree 側の変更で止まることがあり、そのときはサーバーが毎日道具を新しくします。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (cals.isEmpty()) {
            Text("Home Assistant にカレンダーがまだありません。上の手順で Remote Calendar を追加すると、ここに表示されます。", style = MaterialTheme.typography.bodyMedium)
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
