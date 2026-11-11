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

    val thermos by vm.thermos.collectAsState()
    // 温度計は、アプリで付けた名前で「寝室の温度」のように出す
    val nameOf = { id: String ->
        thermos.firstOrNull { it.tempEntity == id }?.let { it.name + "の温度" }
            ?: thermos.firstOrNull { it.humEntity == id }?.let { it.name + "の湿度" }
            ?: states[id]?.name?.let { withRoom(vm.roomNameOf(id), it) } ?: id
    }
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
        // スケジュールの時刻は Home Assistant の時間帯で動くので、日本時間でなければ知らせる
        val haTz by vm.haTimeZone.collectAsState()
        LaunchedEffect(Unit) { vm.loadHaTimeZone() }
        haTz?.takeIf { it != "Asia/Tokyo" }?.let { tz ->
            Text("⚠ Home Assistant の時間帯が「$tz」になっています。スケジュールの時刻がずれるので、Home Assistant の「設定 → システム → 一般」で時間帯を「Asia/Tokyo」にしてください。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
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
                                Text("最後に実行：" + agoText(it), style = MaterialTheme.typography.labelSmall)
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

private enum class PickFor { TRIGGER, ACTION, CONDITION }

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun RuleEditor(vm: MainViewModel, initial: Rule, isNew: Boolean, onDone: () -> Unit) {
    val haStates by vm.allStates.collectAsState()
    // ECHONET Lite で直接つなぐ機器（Home Assistant に無い機器）も操作に選べる
    val merged by vm.allEntities.collectAsState()
    val states = haStates + merged.filter { isEchonetEntity(it.entityId) }.associateBy { it.entityId }
    var rule by remember { mutableStateOf(initial) }
    var pick by remember { mutableStateOf<PickFor?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val thermos by vm.thermos.collectAsState()
    val nameOf = { id: String ->
        thermos.firstOrNull { it.tempEntity == id }?.let { it.name + "の温度" }
            ?: thermos.firstOrNull { it.humEntity == id }?.let { it.name + "の湿度" }
            ?: states[id]?.name?.let { withRoom(vm.roomNameOf(id), it) } ?: id
    }

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
                FilterChip(t is Trig.Distance, {
                    if (t !is Trig.Distance) rule = rule.copy(trigger = Trig.Distance(states.values.firstOrNull { it.domain == "person" }?.entityId ?: "", 1.0, true))
                }, { Text("家からの距離") })
                FilterChip(t is Trig.Numeric, {
                    if (t !is Trig.Numeric) {
                        val first = thermos.firstOrNull { it.tempEntity != null }
                        rule = rule.copy(trigger = Trig.Numeric(first?.tempEntity ?: "", true, Math.round((first?.temperature ?: 27.0) + 1.0).toDouble()))
                    }
                }, { Text("温度計（温度・湿度）") })
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
            is Trig.Distance -> {
                val people = states.values.filter { it.domain == "person" || it.domain == "device_tracker" }.sortedWith(compareBy({ it.domain != "person" }, { it.name }))
                Text("だれの位置で決めるか（スマートフォンの Home Assistant アプリで位置を送っている人）", style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    people.forEach { p -> FilterChip(t.personId == p.entityId, { rule = rule.copy(trigger = t.copy(personId = p.entityId)) }, { Text(p.name) }) }
                }
                if (people.isEmpty()) Text("Home Assistant に人（person）がいません。スマートフォンに Home Assistant アプリを入れて、位置情報を送るようにしてください。", color = MaterialTheme.colorScheme.error)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(t.approach, { rule = rule.copy(trigger = t.copy(approach = true)) }, { Text("近づいたとき（帰宅前）") })
                    FilterChip(!t.approach, { rule = rule.copy(trigger = t.copy(approach = false)) }, { Text("離れたとき（外出後）") })
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0.3, 0.5, 1.0, 2.0, 3.0, 5.0, 10.0, 20.0).forEach { km ->
                        FilterChip(t.km == km, { rule = rule.copy(trigger = t.copy(km = km)) }, { Text(kmText(km)) })
                    }
                }
                states[t.personId]?.let { p ->
                    val lat = p.attributes.optDouble("latitude")
                    Text(if (lat.isNaN()) "※ ${p.name} の位置はまだ届いていません" else "${p.name} の位置は届いています", style = MaterialTheme.typography.bodySmall)
                }
            }
            is Trig.Numeric -> {
                val isHum = thermos.any { it.humEntity == t.entityId }
                val step = if (isHum) 5.0 else 0.5
                val unit = if (isHum) "%" else "℃"
                Text("どの温度計で決めるか", style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    thermos.forEach { th ->
                        th.tempEntity?.let { id ->
                            FilterChip(t.entityId == id, {
                                if (t.entityId != id) rule = rule.copy(trigger = t.copy(entityId = id, value = if (isHum) Math.round((th.temperature ?: 27.0) + 1.0).toDouble() else t.value))
                            }, { Text("🌡 ${th.name}" + (th.temperature?.let { " ${fmt(Math.round(it * 10) / 10.0)}℃" } ?: "")) })
                        }
                        th.humEntity?.let { id ->
                            FilterChip(t.entityId == id, {
                                if (t.entityId != id) rule = rule.copy(trigger = t.copy(entityId = id, value = if (!isHum) ((Math.round((th.humidity ?: 60.0) / 5) * 5) + 5).toDouble() else t.value))
                            }, { Text("💧 ${th.name}" + (th.humidity?.let { " ${Math.round(it)}%" } ?: "")) })
                        }
                    }
                }
                if (thermos.isEmpty()) Text("温度計が見つかりません（SwitchBot 温湿度計などを Home Assistant に取り込むと出てきます）。", color = MaterialTheme.colorScheme.error)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(t.above, { rule = rule.copy(trigger = t.copy(above = true)) }, { Text(if (isHum) "上回ったとき（じめじめ）" else "上回ったとき（暑い）") })
                    FilterChip(!t.above, { rule = rule.copy(trigger = t.copy(above = false)) }, { Text(if (isHum) "下回ったとき（乾燥）" else "下回ったとき（寒い）") })
                }
                NumField(t.value, { rule = rule.copy(trigger = t.copy(value = it)) }, unit = unit, step = step,
                    min = if (isHum) 0.0 else -10.0, max = if (isHum) 100.0 else 50.0, big = true)
                Text("続いたら動かす（すぐ上下するときの、むだな動きを防ぐ）", style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0 to "すぐ", 5 to "5分", 10 to "10分", 30 to "30分").forEach { (m, l) ->
                        FilterChip(t.forMin == m, { rule = rule.copy(trigger = t.copy(forMin = m)) }, { Text(l) })
                    }
                }
                Text("※ 境目をまたいだときに1回動きます（${numText(t.value)}$unit より${if (t.above) "上" else "下"}のあいだ、くり返しは動きません）。", style = MaterialTheme.typography.labelSmall)
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

        // ---- 条件（すべて満たすときだけ動かす） ----
        Label("条件（選ばなければ、いつも動かす）")
        Text("${if (rule.schedule) "その時刻" else "きっかけ"}の時点で、ここに並べた条件をすべて満たすときだけ実行します。例：6時の時点で寝室が20℃より低ければ暖房をつける。",
            style = MaterialTheme.typography.bodySmall)
        rule.conds.forEachIndexed { i, c ->
            CondCard(c, thermos, states, nameOf,
                onChange = { nc -> rule = rule.copy(conds = rule.conds.toMutableList().also { it[i] = nc }) },
                onRemove = { rule = rule.copy(conds = rule.conds.toMutableList().also { it.removeAt(i) }) })
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                val th = thermos.firstOrNull { it.tempEntity != null }
                rule = rule.copy(conds = rule.conds + Cond.Numeric(th?.tempEntity ?: "", false, Math.round(th?.temperature ?: 20.0).toDouble()))
            }) { Text("＋ 温度計の条件") }
            OutlinedButton(onClick = { pick = PickFor.CONDITION }) { Text("＋ 機器の状態の条件") }
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
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
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
            if (!isNew) {
                // 保存と離して、危険な色で（UI-008）。確認のダイアログはこれまでどおり
                Spacer(Modifier.weight(1f))
                OutlinedButton(
                    onClick = { confirmDelete = true },
                    colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("この自動化を削除") }
            }
        }
        Spacer(Modifier.size(32.dp))
    }

    pick?.let { p ->
        val domains = if (p == PickFor.ACTION) SUPPORTED_DOMAINS - "camera" else TRIGGER_DOMAINS
        EntityPicker(
            entities = states.values.filter { it.domain in domains && (p == PickFor.ACTION || !isEchonetEntity(it.entityId)) }
                .sortedWith(compareBy({ vm.roomNameOf(it.entityId) == null }, { vm.roomNameOf(it.entityId) ?: "" }, { domains.indexOf(it.domain) }, { it.name })),
            roomOf = { vm.roomNameOf(it) },
            onDismiss = { pick = null },
        ) { e ->
            if (p == PickFor.TRIGGER) {
                rule = rule.copy(trigger = Trig.State(e.entityId, stateOptions(e.domain).first().first))
            } else if (p == PickFor.CONDITION) {
                rule = rule.copy(conds = rule.conds + Cond.State(e.entityId, condStateOptions(e.domain).first().first))
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
    r.conds.any { it is Cond.Numeric && it.entityId.isBlank() } -> "条件の温度計を選んでください"
    r.actions.isEmpty() -> "「＋ 操作を追加」で、実行することを1つ以上選んでください"
    r.trigger is Trig.State && (r.trigger as Trig.State).entityId.isBlank() -> "きっかけにする機器を選んでください"
    r.trigger is Trig.Distance && (r.trigger as Trig.Distance).personId.isBlank() -> "だれの位置で決めるかを選んでください"
    r.trigger is Trig.Numeric && (r.trigger as Trig.Numeric).entityId.isBlank() -> "きっかけにする温度計を選んでください"
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
                    Text(domainLabel(domain) + if (isEchonetEntity(act.entityId)) "・ECHONET Lite（ホームパネルのサーバーが操作）" else "", style = MaterialTheme.typography.labelSmall)
                    Text(name, style = MaterialTheme.typography.titleMedium)
                }
                TextButton(onClick = onRemove, colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("削除") }
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
                    NumField(v, { onChange(act.copy(data = act.data + ("temperature" to it))) }, step = entity?.attributes?.num("target_temp_step") ?: 0.5,
                        min = entity?.attributes?.num("min_temp") ?: 16.0, max = entity?.attributes?.num("max_temp") ?: 32.0, big = true)
                }
                Param.FAN -> FanChoice(act, entity, allowKeep = false, onChange)
                Param.HVAC_MODE, Param.MODE_TEMP -> {
                    val modes = entity?.attributes?.strings("hvac_modes")?.filter { it != "off" }?.ifEmpty { null }
                        ?: listOf("cool", "heat", "dry", "fan_only", "auto")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        modes.forEach { m ->
                            FilterChip(act.data["hvac_mode"] == m, { onChange(act.copy(data = act.data + ("hvac_mode" to m))) }, { Text(hvacLabel(m)) })
                        }
                    }
                    FanChoice(act, entity, allowKeep = true, onChange)
                    if (current.param == Param.MODE_TEMP && act.data["hvac_mode"] !in setOf("fan_only", "dry")) {
                        val v = (act.data["temperature"] as? Number)?.toDouble() ?: 25.0
                        Text("設定温度", style = MaterialTheme.typography.labelLarge)
                        NumField(v, { onChange(act.copy(data = act.data + ("temperature" to it))) }, step = entity?.attributes?.num("target_temp_step") ?: 0.5,
                            min = entity?.attributes?.num("min_temp") ?: 16.0, max = entity?.attributes?.num("max_temp") ?: 32.0, big = true)
                    }
                }
                null -> {}
            }
        }
    }
}

@Composable
private fun EntityPicker(entities: List<Entity>, onDismiss: () -> Unit, roomOf: (String) -> String? = { null }, onPick: (Entity) -> Unit) {
    var q by remember { mutableStateOf("") }
    val list = entities.filter {
        q.isBlank() || it.name.contains(q.trim(), ignoreCase = true) || it.entityId.contains(q.trim()) || roomOf(it.entityId)?.contains(q.trim()) == true
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("機器を選ぶ") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(q, { q = it }, singleLine = true, label = { Text("名前・部屋で絞り込み") }, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(list, key = { it.entityId }) { e ->
                        Column(
                            Modifier.fillMaxWidth().clickable { onPick(e) }.padding(vertical = 10.dp, horizontal = 4.dp),
                        ) {
                            Text((roomOf(e.entityId)?.let { "$it・" } ?: "") + e.name, style = MaterialTheme.typography.bodyLarge)
                            Text(domainLabel(e.domain) + if (isEchonetEntity(e.entityId)) "・ECHONET Lite（サーバーから直接操作）" else "・${e.entityId}",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("閉じる") } },
    )
}


/** 条件の1行（温度計の値／機器の状態）。右の ✕ で消す */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CondCard(c: Cond, thermos: List<Thermo>, states: Map<String, Entity>, nameOf: (String) -> String, onChange: (Cond) -> Unit, onRemove: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("もし " + condLabel(c, nameOf), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = onRemove) { Text("✕") }
            }
            when (c) {
                is Cond.Numeric -> {
                    val isHum = thermos.any { it.humEntity == c.entityId }
                    val step = if (isHum) 5.0 else 0.5
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        thermos.forEach { th ->
                            th.tempEntity?.let { id ->
                                FilterChip(c.entityId == id, { onChange(c.copy(entityId = id, value = if (isHum) Math.round(th.temperature ?: 20.0).toDouble() else c.value)) },
                                    { Text("🌡 ${th.name}" + (th.temperature?.let { " ${fmt(Math.round(it * 10) / 10.0)}℃" } ?: "")) })
                            }
                            th.humEntity?.let { id ->
                                FilterChip(c.entityId == id, { onChange(c.copy(entityId = id, value = if (!isHum) (Math.round((th.humidity ?: 50.0) / 5) * 5).toDouble() else c.value)) },
                                    { Text("💧 ${th.name}" + (th.humidity?.let { " ${Math.round(it)}%" } ?: "")) })
                            }
                        }
                    }
                    if (thermos.isEmpty()) Text("温度計が見つかりません", color = MaterialTheme.colorScheme.error)
                    FlowRow(verticalArrangement = Arrangement.Center, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(!c.above, { onChange(c.copy(above = false)) }, { Text("より低い") })
                        FilterChip(c.above, { onChange(c.copy(above = true)) }, { Text("より高い") })
                        NumField(c.value, { onChange(c.copy(value = it)) }, unit = if (isHum) "%" else "℃", step = step,
                            min = if (isHum) 0.0 else -10.0, max = if (isHum) 100.0 else 50.0)
                    }
                }
                is Cond.State -> {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        condStateOptions(c.entityId.substringBefore('.')).forEach { (v, l) ->
                            FilterChip(c.state == v, { onChange(c.copy(state = v)) }, { Text(l) })
                        }
                    }
                    states[c.entityId]?.let { Text("いまの状態：${it.state}", style = MaterialTheme.typography.labelSmall) }
                }
            }
        }
    }
}


/** 「寝室・エアコン」。名前にもう部屋の名前が入っていれば付けない */
fun withRoom(room: String?, name: String): String = if (room.isNullOrBlank() || name.contains(room)) name else "$room・$name"


/** エアコンの風量（自動・1〜8 など）。allowKeep なら「そのまま」（風量は変えない）も選べる */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FanChoice(act: Act, entity: Entity?, allowKeep: Boolean, onChange: (Act) -> Unit) {
    val fans = entity?.attributes?.strings("fan_modes")?.ifEmpty { null } ?: listOf("auto", "low", "medium", "high")
    val cur = act.data["fan_mode"]?.toString()
    Text("風量", style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (allowKeep) FilterChip(cur == null, { onChange(act.copy(data = act.data - "fan_mode")) }, { Text("そのまま") })
        fans.forEach { f ->
            FilterChip(cur == f, { onChange(act.copy(data = act.data + ("fan_mode" to f))) }, { Text(fanModeLabel(f)) })
        }
    }
}
