package jp.home.panel

import android.app.TimePickerDialog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

@Composable
fun AutomationScreen(vm: MainViewModel, onClose: () -> Unit) {
    val autos by vm.automations.collectAsState()
    val rules by vm.rules.collectAsState()
    val loading by vm.rulesLoading.collectAsState()
    val msg by vm.ruleMessage.collectAsState()
    val states by vm.allStates.collectAsState()
    val admin by vm.isAdmin.collectAsState()
    val adminUser by vm.adminUser.collectAsState()
    val canEdit = admin == true || adminUser != null

    var tab by rememberSaveable { mutableStateOf(0) } // 0 = スケジュール, 1 = オートメーション
    var editing by remember { mutableStateOf<Rule?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(canEdit, autos.size) { if (canEdit) vm.loadRules() }

    editing?.let { r ->
        BackHandler { editing = null }
        RuleEditor(vm, r, isNew = !rules.containsKey(r.id), onDone = { editing = null })
        return
    }

    val nameOf = { id: String -> states[id]?.name ?: id }
    val list = autos.filter {
        val id = it.attributes.optString("id")
        if (tab == 0) isScheduleId(id) else !isScheduleId(id)
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose) { Text("← 戻る") }
            Text("自動化", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.weight(1f))
            if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("⏰ スケジュール") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("⚡ オートメーション") })
        }
        Text(
            if (tab == 0) "決まった時刻・曜日に機器を自動で操作します（例：平日の7時にエアコンをつける）。"
            else "「人感センサーが反応したら照明をつける」「日の入りにカーテンを閉める」のように、きっかけに合わせて自動で操作します。",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "設定は Home Assistant に保存されるので、このタブレットの電源が切れていても実行されます。",
            style = MaterialTheme.typography.bodySmall,
        )
        if (!canEdit) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Text(
                    "作成・編集には管理者の権限が必要です。⚙ 設定 → アカウント で「管理者ログインを追加」してください。オン/オフと今すぐ実行は、このままでも使えます。",
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
        (notice ?: msg)?.let { Text(it, color = MaterialTheme.colorScheme.primary) }

        Button(
            onClick = { editing = newRule(schedule = tab == 0) },
            enabled = canEdit,
        ) { Text(if (tab == 0) "＋ 新しいスケジュール" else "＋ 新しいオートメーション") }

        if (list.isEmpty()) {
            Text(
                if (tab == 0) "スケジュールはまだありません。" else "オートメーションはまだありません。",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
            items(list, key = { it.entityId }) { a ->
                val id = a.attributes.optString("id")
                val app = isAppRuleId(id)
                val rule = rules[id]
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth().clickable {
                        when {
                            !canEdit -> notice = "編集するには管理者ログインが必要です（⚙ 設定 → アカウント）"
                            !app -> notice = "「${a.name}」は Home Assistant で作られた自動化なので、Home Assistant の画面で編集してください"
                            rule == null -> notice = "「${a.name}」の設定を読み込めませんでした。Home Assistant の画面で確認してください"
                            else -> { notice = null; editing = rule }
                        }
                    },
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(a.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                when {
                                    rule != null -> ruleSummary(rule, nameOf)
                                    !app -> "Home Assistant で作成"
                                    else -> "（内容を読み込み中、または読み込めません）"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                            a.attributes.optString("last_triggered").takeIf { it.isNotBlank() && it != "null" }?.let {
                                Text("最後に実行：" + it.take(16).replace("T", " "), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        TextButton(onClick = { vm.runAutomation(a); notice = "「${a.name}」を実行しました" }) { Text("今すぐ実行") }
                        Switch(checked = a.state == "on", onCheckedChange = { vm.setAutomationEnabled(a, it) })
                    }
                }
            }
        }
    }
}

// ================= 編集画面 =================

private enum class PickFor { TRIGGER, ACTION }

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun RuleEditor(vm: MainViewModel, initial: Rule, isNew: Boolean, onDone: () -> Unit) {
    val states by vm.allStates.collectAsState()
    var rule by remember { mutableStateOf(initial) }
    var pick by remember { mutableStateOf<PickFor?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val nameOf = { id: String -> states[id]?.name ?: id }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onDone) { Text("← キャンセル") }
            Text(
                (if (isNew) "新しい" else "") + (if (rule.schedule) "スケジュール" else "オートメーション"),
                style = MaterialTheme.typography.titleLarge,
            )
        }
        OutlinedTextField(
            rule.name, { rule = rule.copy(name = it) },
            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
            singleLine = true,
            label = { Text("名前（空欄なら内容から自動で付けます）") },
        )

        // ---- きっかけ ----
        Label(if (rule.schedule) "時刻" else "きっかけ")
        if (!rule.schedule) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val t = rule.trigger
                FilterChip(t is Trig.Time, { rule = rule.copy(trigger = Trig.Time("07:00")) }, { Text("時刻") })
                FilterChip(t is Trig.State, { if (t !is Trig.State) rule = rule.copy(trigger = Trig.State("", "on")) }, { Text("機器の状態") })
                FilterChip(t is Trig.Sun && t.event == "sunrise", { rule = rule.copy(trigger = Trig.Sun("sunrise", 0)) }, { Text("日の出") })
                FilterChip(t is Trig.Sun && t.event == "sunset", { rule = rule.copy(trigger = Trig.Sun("sunset", 0)) }, { Text("日の入り") })
            }
        }
        when (val t = rule.trigger) {
            is Trig.Time -> TimeButton(t.at) { rule = rule.copy(trigger = Trig.Time(it)) }
            is Trig.State -> {
                OutlinedButton(onClick = { pick = PickFor.TRIGGER }) {
                    Text(if (t.entityId.isBlank()) "機器を選ぶ" else "機器：" + nameOf(t.entityId))
                }
                if (t.entityId.isNotBlank()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        stateOptions(t.entityId.substringBefore('.')).forEach { (v, label) ->
                            FilterChip(t.to == v, { rule = rule.copy(trigger = t.copy(to = v)) }, { Text(label) })
                        }
                    }
                }
            }
            is Trig.Sun -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { rule = rule.copy(trigger = t.copy(offsetMin = t.offsetMin - 10)) }) { Text("−10分") }
                Text(
                    when {
                        t.offsetMin < 0 -> "${-t.offsetMin}分前"
                        t.offsetMin > 0 -> "${t.offsetMin}分後"
                        else -> "ちょうど"
                    },
                    fontSize = 20.sp,
                    modifier = Modifier.widthIn(min = 90.dp),
                )
                FilledTonalButton(onClick = { rule = rule.copy(trigger = t.copy(offsetMin = t.offsetMin + 10)) }) { Text("＋10分") }
            }
        }

        // ---- 曜日 ----
        Label("曜日（選ばなければ毎日）")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            WEEKDAYS.forEach { d ->
                FilterChip(d in rule.weekdays, {
                    rule = rule.copy(weekdays = if (d in rule.weekdays) rule.weekdays - d else rule.weekdays + d)
                }, { Text(WEEKDAY_LABEL[d]!!) })
            }
            TextButton(onClick = { rule = rule.copy(weekdays = setOf("mon", "tue", "wed", "thu", "fri")) }) { Text("平日") }
            TextButton(onClick = { rule = rule.copy(weekdays = setOf("sat", "sun")) }) { Text("土日") }
            TextButton(onClick = { rule = rule.copy(weekdays = emptySet()) }) { Text("毎日") }
        }

        // ---- 時間帯（オートメーションのみ） ----
        if (!rule.schedule) {
            SwitchRow("時間帯で絞る", rule.after != null || rule.before != null, sub = "例：夜（18:00〜23:00）だけ動かす") { on ->
                rule = if (on) rule.copy(after = "18:00", before = "23:00") else rule.copy(after = null, before = null)
            }
            if (rule.after != null || rule.before != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TimeButton(rule.after ?: "00:00") { rule = rule.copy(after = it) }
                    Text("〜")
                    TimeButton(rule.before ?: "23:59") { rule = rule.copy(before = it) }
                }
            }
        }

        // ---- 実行すること ----
        Label("実行すること")
        rule.actions.forEachIndexed { i, a ->
            ActionCard(
                act = a,
                entity = states[a.entityId],
                name = nameOf(a.entityId),
                onChange = { na -> rule = rule.copy(actions = rule.actions.toMutableList().also { it[i] = na }) },
                onRemove = { rule = rule.copy(actions = rule.actions.toMutableList().also { it.removeAt(i) }) },
            )
        }
        OutlinedButton(onClick = { pick = PickFor.ACTION }) { Text("＋ 操作を追加") }

        // ---- まとめ ----
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Text("内容：" + ruleSummary(rule, nameOf), modifier = Modifier.padding(12.dp))
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = {
                    error = validate(rule)
                    if (error == null) {
                        saving = true
                        vm.saveRule(rule) { err ->
                            saving = false
                            if (err == null) onDone() else error = err
                        }
                    }
                },
                enabled = !saving,
            ) { Text(if (saving) "保存しています…" else "保存") }
            if (!isNew) OutlinedButton(onClick = { confirmDelete = true }) { Text("削除") }
        }
        Spacer(Modifier.size(32.dp))
    }

    pick?.let { p ->
        val domains = if (p == PickFor.TRIGGER) TRIGGER_DOMAINS else SUPPORTED_DOMAINS
        EntityPicker(
            entities = states.values.filter { it.domain in domains }.sortedWith(compareBy({ domains.indexOf(it.domain) }, { it.name })),
            onDismiss = { pick = null },
        ) { e ->
            if (p == PickFor.TRIGGER) {
                rule = rule.copy(trigger = Trig.State(e.entityId, stateOptions(e.domain).first().first))
            } else {
                rule = rule.copy(actions = rule.actions + opsFor(e.domain).first().toAct(e.entityId, e.domain))
            }
            pick = null
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("削除しますか？") },
            text = { Text("「${rule.name.ifBlank { "この自動化" }}」を Home Assistant から削除します。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.deleteRule(rule.id) { err -> if (err == null) onDone() else error = err }
                }) { Text("削除") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("キャンセル") } },
        )
    }
}

private fun validate(r: Rule): String? = when {
    r.actions.isEmpty() -> "「＋ 操作を追加」で、実行することを1つ以上選んでください"
    r.trigger is Trig.State && (r.trigger as Trig.State).entityId.isBlank() -> "きっかけにする機器を選んでください"
    else -> null
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

/** 時刻を選ぶボタン（押すと時計の画面が開く） */
@Composable
private fun TimeButton(value: String, onChange: (String) -> Unit) {
    val ctx = LocalContext.current
    val (h, m) = value.split(":").let { (it.getOrNull(0)?.toIntOrNull() ?: 7) to (it.getOrNull(1)?.toIntOrNull() ?: 0) }
    FilledTonalButton(onClick = {
        TimePickerDialog(ctx, { _, hh, mm -> onChange(String.format("%02d:%02d", hh, mm)) }, h, m, true).show()
    }) { Text(String.format("%d:%02d", h, m), fontSize = 26.sp) }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ActionCard(act: Act, entity: Entity?, name: String, onChange: (Act) -> Unit, onRemove: () -> Unit) {
    val domain = act.entityId.substringBefore('.')
    val current = opOf(act)
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(domainLabel(domain), style = MaterialTheme.typography.labelSmall)
                    Text(name, style = MaterialTheme.typography.titleMedium)
                }
                TextButton(onClick = onRemove) { Text("削除") }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                opsFor(domain).forEach { op ->
                    FilterChip(op == current, { onChange(op.toAct(act.entityId, domain)) }, { Text(op.label) })
                }
            }
            when (current?.param) {
                Param.BRIGHTNESS -> {
                    val v = (act.data["brightness_pct"] as? Number)?.toFloat() ?: 100f
                    Text("明るさ ${v.roundToInt()}%")
                    Slider(value = v, onValueChange = { onChange(act.copy(data = mapOf("brightness_pct" to it.roundToInt()))) }, valueRange = 1f..100f)
                }
                Param.POSITION -> {
                    val v = (act.data["position"] as? Number)?.toFloat() ?: 50f
                    Text("開き具合 ${v.roundToInt()}%")
                    Slider(value = v, onValueChange = { onChange(act.copy(data = mapOf("position" to it.roundToInt()))) }, valueRange = 0f..100f)
                }
                Param.TEMPERATURE -> {
                    val v = (act.data["temperature"] as? Number)?.toDouble() ?: 25.0
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { onChange(act.copy(data = mapOf("temperature" to v - 0.5))) }) { Text("−") }
                        Text("${fmt(v)}℃", fontSize = 26.sp)
                        FilledTonalButton(onClick = { onChange(act.copy(data = mapOf("temperature" to v + 0.5))) }) { Text("＋") }
                    }
                }
                Param.HVAC_MODE -> {
                    val modes = entity?.attributes?.strings("hvac_modes")?.filter { it != "off" }?.ifEmpty { null }
                        ?: listOf("cool", "heat", "dry", "fan_only", "auto")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        modes.forEach { m ->
                            FilterChip(act.data["hvac_mode"] == m, { onChange(act.copy(data = mapOf("hvac_mode" to m))) }, { Text(hvacLabel(m)) })
                        }
                    }
                }
                null -> {}
            }
        }
    }
}

@Composable
private fun EntityPicker(entities: List<Entity>, onDismiss: () -> Unit, onPick: (Entity) -> Unit) {
    var q by remember { mutableStateOf("") }
    val list = entities.filter { q.isBlank() || it.name.contains(q.trim(), ignoreCase = true) || it.entityId.contains(q.trim()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("機器を選ぶ") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(q, { q = it }, singleLine = true, label = { Text("名前で絞り込み") }, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(list, key = { it.entityId }) { e ->
                        Column(
                            Modifier.fillMaxWidth().clickable { onPick(e) }.padding(vertical = 10.dp, horizontal = 4.dp),
                        ) {
                            Text(e.name, style = MaterialTheme.typography.bodyLarge)
                            Text("${domainLabel(e.domain)}・${e.entityId}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("閉じる") } },
    )
}
