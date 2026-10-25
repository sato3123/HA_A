@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package jp.home.panel

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SettingsScreen(vm: MainViewModel, onClose: () -> Unit, onEnableWake: () -> Unit) {
    val all by vm.allEntities.collectAsState()
    val sel by vm.selection.collectAsState()
    val pipelines by vm.pipelines.collectAsState()
    val preferred by vm.preferredPipeline.collectAsState()
    val pipelineId by vm.pipelineId.collectAsState()
    val wake by vm.wakeEnabled.collectAsState()
    val weathers by vm.weatherEntities.collectAsState()
    val weather by vm.weather.collectAsState()
    val remote by vm.remoteUrl.collectAsState()
    val route by vm.route.collectAsState()
    val themeId by vm.themeId.collectAsState()
    val rooms by vm.rooms.collectAsState()
    val overrides by vm.roomOverrides.collectAsState()
    val areas by vm.entityAreas.collectAsState()
    val groupRooms by vm.groupRooms.collectAsState()
    var assignFor by remember { mutableStateOf<Entity?>(null) }
    var renameRoom by remember { mutableStateOf<Room?>(null) }
    var newRoom by rememberSaveable { mutableStateOf("") }

    var query by rememberSaveable { mutableStateOf("") }
    var remoteInput by rememberSaveable(remote) { mutableStateOf(remote) }
    var remoteError by remember { mutableStateOf<String?>(null) }
    var confirmLogout by remember { mutableStateOf(false) }
    // いま開いている設定のページ（null = メニュー）
    var page by rememberSaveable { mutableStateOf<String?>(null) }
    androidx.activity.compose.BackHandler(enabled = page != null) { page = null }

    val selected = sel ?: all.map { it.entityId }.toSet()
    val effectivePipeline = pipelineId ?: preferred
    val q = query.trim()
    val grouped = all
        .filter { q.isEmpty() || it.name.contains(q, ignoreCase = true) || it.entityId.contains(q, ignoreCase = true) }
        .groupBy { it.domain }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (page == null) {
                    TextButton(onClick = onClose) { Text("← 戻る") }
                    Text("設定", style = MaterialTheme.typography.titleLarge)
                } else {
                    TextButton(onClick = { page = null }) { Text("← 設定") }
                    Text(SETTINGS_MENU.firstOrNull { it.key == page }?.title ?: "", style = MaterialTheme.typography.titleLarge)
                }
            }
        }
        // ---- メニュー（項目ごとに分ける） ----
        if (page == null) {
            items(SETTINGS_MENU, key = { "m_" + it.key }) { m ->
                MenuRow(m) { page = m.key }
            }
        }

        if (page == "display") {
            // ---- 表示のユーザー ----
            item { SectionTitle("表示のユーザー（家族ごとの表示）") }
            item { ProfileSettings(vm) }

            // ---- 配色 ----
            item { SectionTitle("配色") }
            item { ThemePicker(themeId) { vm.setTheme(it) } }

            // ---- 天気 ----
            if (weathers.size > 1) {
                item { SectionTitle("天気") }
                items(weathers, key = { "w_" + it.entityId }) { w ->
                    RadioRow(w.name, w.entityId, w.entityId == weather?.entityId) { vm.setWeatherEntity(w.entityId) }
                }
            }
        }

        if (page == "rooms") {
            // ---- 部屋 ----
            item { SectionTitle("部屋") }
            item {
                SwitchRow(
                    "部屋ごとに分けて表示する",
                    groupRooms,
                    sub = "Home Assistant のエリアを部屋として使います。アプリで部屋を追加したり、機器の部屋を変えたりもできます（下の「表示する機器」で機器ごとに選べます）。",
                ) { vm.setGroupRooms(it) }
            }
            item {
                Text("▲▼ で、ホーム画面に並べる部屋の順番を変えられます。", style = MaterialTheme.typography.bodySmall)
            }
            itemsIndexed(rooms, key = { _, r -> "r_" + r.key }) { i, r ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = { vm.moveRoom(r.key, -1) }, enabled = i > 0) { Text("▲") }
                    TextButton(onClick = { vm.moveRoom(r.key, 1) }, enabled = i < rooms.size - 1) { Text("▼") }
                    Column(Modifier.weight(1f)) {
                        Text(r.name, style = MaterialTheme.typography.bodyLarge)
                        Text(if (r.fromHa) "Home Assistant のエリア" else "アプリで追加した部屋", style = MaterialTheme.typography.bodySmall)
                    }
                    if (!r.fromHa) {
                        TextButton(onClick = { renameRoom = r }) { Text("名前を変える") }
                        TextButton(onClick = { vm.deleteRoom(r.key) }) { Text("削除") }
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        newRoom, { newRoom = it },
                        modifier = Modifier.widthIn(max = 360.dp).weight(1f, fill = false),
                        singleLine = true,
                        label = { Text("部屋を追加（例：子ども部屋）") },
                    )
                    TextButton(onClick = { vm.addRoom(newRoom); newRoom = "" }, enabled = newRoom.isNotBlank()) { Text("追加") }
                }
            }

            // ---- 表示する機器 ----
            item { SectionTitle("表示する機器（${all.count { it.entityId in selected }}／${all.size}）") }
            item {
                Column {
                    OutlinedTextField(
                        query, { query = it },
                        modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
                        singleLine = true,
                        label = { Text("名前で絞り込み") },
                    )
                    Row {
                        TextButton(onClick = vm::selectAll) { Text("すべて選択") }
                        TextButton(onClick = vm::selectNone) { Text("すべて解除") }
                    }
                }
            }
            grouped.forEach { (domain, list) ->
                item(key = "h_$domain") {
                    Text(domainLabel(domain), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                }
                items(list, key = { "e_" + it.entityId }) { e ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { vm.toggleSelection(e.entityId) },
                    ) {
                        Checkbox(checked = e.entityId in selected, onCheckedChange = { vm.toggleSelection(e.entityId) })
                        Column(Modifier.weight(1f)) {
                            Text(e.name, style = MaterialTheme.typography.bodyLarge)
                            Text(e.entityId, style = MaterialTheme.typography.bodySmall)
                        }
                        val rk = roomKeyOf(e.entityId, overrides, areas, rooms)
                        OutlinedButton(onClick = { assignFor = e }) {
                            Text("🏠 " + (rooms.firstOrNull { it.key == rk }?.name ?: "その他"))
                        }
                    }
                }
            }
        }

        if (page == "thermo") {
            // ---- 温度計 ----
            item { SectionTitle("温度・湿度（SwitchBot などの温度計）") }
            item { ThermoSettings(vm) }
        }

        if (page == "voice") {
            // ---- 自宅の位置 ----
            item { SectionTitle("自宅の位置") }
            item { HomeLocationSettings(vm) }

            // ---- 音声 ----
            item { SectionTitle("音声") }
            item {
                SwitchRow(
                    "呼びかけで反応する",
                    wake,
                    sub = "Home Assistant の音声アシスタントに設定したウェイクワードで反応します。" +
                        "家の中にいて、この画面を表示している間だけ待ち受けます。",
                ) { if (it) onEnableWake() else vm.setWakeEnabled(false) }
            }
            if (pipelines.isNotEmpty()) {
                item {
                    Text("使うアシスタント（AIの会話エージェントはここで選んだものが答えます）", style = MaterialTheme.typography.labelLarge)
                }
                items(pipelines, key = { "p_" + it.id }) { p ->
                    val tags = listOfNotNull(
                        if (p.id == preferred) "優先" else null,
                        if (p.hasWakeWord) "呼びかけ対応" else "呼びかけ未設定",
                        p.agent.takeIf { it.isNotBlank() },
                    )
                    RadioRow(p.name, tags.joinToString("・"), p.id == effectivePipeline) { vm.setPipeline(p.id) }
                }
            }

            item { Text("呼びかけに反応しないときは、下のマイクのテストと動作ログで原因を確認できます。", style = MaterialTheme.typography.bodySmall) }
            item { VoiceDiagnostics(vm) }
        }

        if (page == "screensaver") {
            // ---- スクリーンセーバー ----
            item { SectionTitle("スクリーンセーバー") }
            item { ScreensaverSettings(vm) }
        }

        if (page == "calendar") {
            // ---- カレンダー（TimeTree） ----
            item { SectionTitle("カレンダー（TimeTree）") }
            item { CalendarSettings(vm) }
        }

        if (page == "cameras") {
            item { SectionTitle("カメラ（直接つなぐ）") }
            item { DirectCameraSettings(vm) }
        }

        if (page == "echonet") {
            // ---- ECHONET Lite ----
            item { SectionTitle("ECHONET Lite（Home Assistant を通さずに直接つなぐ）") }
            item { EchonetSettings(vm) }
        }

        if (page == "connection") {
            // ---- 外出先 ----
            item { SectionTitle("外出先からの接続（Home Assistant Cloud）") }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Home Assistant の「設定 → Home Assistant Cloud → リモートコントロール」に表示されるURLを入力します。" +
                            "家の中ではローカル接続、外では自動でこのURLを使います。空にすると家の中専用に戻ります。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        remoteInput, { remoteInput = it; remoteError = null },
                        modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
                        singleLine = true,
                        label = { Text("リモートURL（例：https://xxxx.ui.nabu.casa）") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    )
                    remoteError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Button(onClick = { remoteError = vm.saveRemoteUrl(remoteInput) }) { Text("保存") }
                        if (remote.isNotEmpty()) {
                            TextButton(onClick = { remoteInput = ""; vm.saveRemoteUrl("") }) { Text("削除") }
                        }
                    }
                    Text(
                        "現在の接続：" + when (route?.remote) {
                            true -> "外出先（Home Assistant Cloud）"
                            false -> "家の中（ローカル）"
                            null -> "未接続"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        if (page == "account") {
            // ---- アカウント ----
            item { SectionTitle("アカウント") }
            item { AccountSection(vm, onLogout = { confirmLogout = true }) }
        }

        if (page == "system") {
            // ---- 設定の書き出し ----
            item { SectionTitle("設定の書き出し・読み込み") }
            item { BackupSettings(vm) }

            // ---- 更新 ----
            item { SectionTitle("アプリの更新") }
            item { UpdateCard(vm, showWhenIdle = true) }
        }

        item { Spacer(Modifier.size(32.dp)) }
    }

    assignFor?.let { e ->
        val current = roomKeyOf(e.entityId, overrides, areas, rooms)
        AlertDialog(
            onDismissRequest = { assignFor = null },
            title = { Text("「${e.name}」の部屋") },
            text = {
                LazyColumn {
                    items(rooms + NO_ROOM_ITEM, key = { it.key }) { r ->
                        RadioRow(r.name, null, r.key == current) {
                            vm.assignRoom(e.entityId, r.key)
                            assignFor = null
                        }
                    }
                    if (overrides.containsKey(e.entityId)) {
                        item {
                            TextButton(onClick = { vm.assignRoom(e.entityId, null); assignFor = null }) {
                                Text("Home Assistant のエリアに戻す")
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { assignFor = null }) { Text("閉じる") } },
        )
    }

    renameRoom?.let { r ->
        var name by remember(r.key) { mutableStateOf(r.name) }
        AlertDialog(
            onDismissRequest = { renameRoom = null },
            title = { Text("部屋の名前") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { vm.renameRoom(r.key, name); renameRoom = null }) { Text("保存") } },
            dismissButton = { TextButton(onClick = { renameRoom = null }) { Text("キャンセル") } },
        )
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("ログアウトしますか？") },
            text = { Text("もう一度使うには、家の中でユーザー名とパスワードを入力する必要があります。") },
            confirmButton = {
                TextButton(onClick = { confirmLogout = false; vm.logout() }) { Text("ログアウト") }
            },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("キャンセル") } },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Column(Modifier.padding(top = 16.dp)) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        HorizontalDivider(Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun RadioRow(title: String, sub: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (!sub.isNullOrBlank()) Text(sub, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ThemePicker(selected: String, onSelect: (String) -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PALETTES.forEach { p ->
            val sel = p.id == selected
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .size(width = 104.dp, height = 96.dp)
                    .background(Color(p.background), RoundedCornerShape(14.dp))
                    .border(if (sel) 3.dp else 1.dp, if (sel) Color(p.primary) else Color(0x55888888), RoundedCornerShape(14.dp))
                    .clickable { onSelect(p.id) }
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.size(22.dp).background(Color(p.primary), CircleShape))
                    Box(Modifier.size(22.dp).background(Color(p.container), CircleShape))
                    Box(Modifier.size(22.dp).background(Color(p.surfaceVariant), CircleShape))
                }
                Text(
                    (if (sel) "✓ " else "") + p.name,
                    color = Color(p.onSurface),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 2,
                )
            }
        }
    }
}

/** 普段使いのログインと、自動化を編集するための管理者ログイン */
@Composable
private fun AccountSection(vm: MainViewModel, onLogout: () -> Unit) {
    val user by vm.userName.collectAsState()
    val admin by vm.isAdmin.collectAsState()
    val adminUser by vm.adminUser.collectAsState()
    val busy by vm.adminBusy.collectAsState()
    val error by vm.adminError.collectAsState()
    var name by rememberSaveable { mutableStateOf("") }
    var pass by rememberSaveable { mutableStateOf("") }
    var otp by rememberSaveable { mutableStateOf("") }
    val field = Modifier.widthIn(max = 420.dp).fillMaxWidth()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "普段使いのユーザー：${user ?: "（確認中）"}" + when (admin) {
                true -> "（管理者）"
                false -> "（管理者以外）"
                null -> ""
            },
            style = MaterialTheme.typography.bodyLarge,
        )
        when {
            admin == true -> Text(
                "このユーザーは管理者なので、オートメーション・スケジュールの作成や編集もできます。",
                style = MaterialTheme.typography.bodySmall,
            )
            adminUser != null -> {
                Text("管理者ログイン（自動化の編集用）：$adminUser", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "機器の操作は普段使いのユーザーで行い、オートメーション・スケジュールの編集だけ管理者の権限を使います。",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = vm::adminLogout) { Text("管理者ログインを解除") }
            }
            else -> {
                Text(
                    "オートメーション・スケジュールを作成・編集するには、管理者ユーザーでのログインを追加してください。" +
                        "普段の操作はこのまま管理者以外のユーザーで行えます。",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(name, { name = it }, modifier = field, singleLine = true, label = { Text("管理者のユーザー名") })
                OutlinedTextField(
                    pass, { pass = it }, modifier = field, singleLine = true, label = { Text("パスワード") },
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                OutlinedTextField(otp, { otp = it }, modifier = field, singleLine = true, label = { Text("2段階認証コード（使っている場合）") })
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(onClick = { vm.adminLogin(name, pass, otp); pass = "" }, enabled = !busy && name.isNotBlank() && pass.isNotBlank()) {
                    Text(if (busy) "確認しています…" else "管理者ログインを追加")
                }
            }
        }
        Spacer(Modifier.size(8.dp))
        OutlinedButton(onClick = onLogout) { Text("ログアウト（すべて）") }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ScreensaverSettings(vm: MainViewModel) {
    val enabled by vm.ssEnabled.collectAsState()
    val minutes by vm.ssMinutes.collectAsState()
    val interval by vm.ssInterval.collectAsState()
    val album by vm.ssAlbum.collectAsState()
    val status by vm.photoStatus.collectAsState()
    var input by rememberSaveable(album) { mutableStateOf(album) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SwitchRow(
            "操作していないときに写真を表示する",
            enabled,
            sub = "時計・天気・タイマーも一緒に表示します。画面に触れると元に戻ります。写真を設定しない場合は時計だけを表示します。",
        ) { vm.setScreensaver(enabled = it) }
        Text("表示するまでの時間", style = MaterialTheme.typography.labelLarge)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(1, 3, 5, 10, 15, 30).forEach { m ->
                androidx.compose.material3.FilterChip(minutes == m, { vm.setScreensaver(minutes = m) }, { Text("${m}分") })
            }
        }
        Text("写真を切り替える間隔", style = MaterialTheme.typography.labelLarge)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(10, 20, 30, 60, 300).forEach { sec ->
                androidx.compose.material3.FilterChip(interval == sec, { vm.setScreensaver(interval = sec) }, { Text(if (sec >= 60) "${sec / 60}分" else "${sec}秒") })
            }
        }
        Text(
            "Google フォトのアルバムを「共有」して「リンクを作成」し、そのリンク（https://photos.app.goo.gl/…）を貼り付けてください。" +
                "Google の仕様変更で、共有リンクにしたアルバムだけ表示できます（非公式の方法のため、Google 側の変更で使えなくなることがあります）。",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            input, { input = it },
            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
            singleLine = true,
            label = { Text("Google フォトの共有アルバムのリンク") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(onClick = { vm.setAlbum(input) }) { Text("保存して読み込む") }
            OutlinedButton(onClick = vm::showScreensaverNow) { Text("今すぐ表示して試す") }
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun HomeLocationSettings(vm: MainViewModel) {
    val loc by vm.homeLocation.collectAsState()
    var input by rememberSaveable(loc) { mutableStateOf(loc) }
    var note by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "「近くのスーパーの営業時間は？」のような場所に関わる質問のとき、ここに登録した場所を基準に AI が答えます。" +
                "機器の操作などには使いません。住所は番地まで入れなくても、町名や最寄り駅までで十分です。",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            input, { input = it; note = null },
            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
            singleLine = true,
            label = { Text("自宅の場所（例：滋賀県大津市○○町、JR○○駅の近く）") },
        )
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(onClick = { vm.setHomeLocation(input); note = if (input.isBlank()) "自宅の位置を削除しました" else "保存しました" }) { Text("保存") }
            OutlinedButton(onClick = { vm.useHaLocation { err -> note = err ?: "Home Assistant に登録されている位置を入れました" } }) {
                Text("Home Assistant の位置を使う")
            }
        }
        note?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        Text(
            "※ OpenAI 連携の「Include home location」をオンにしている場合は、Home Assistant の「設定 → システム → 一般」の位置も使われます。",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

data class MenuItem(val key: String, val icon: String, val title: String, val sub: String)

val SETTINGS_MENU = listOf(
    MenuItem("display", "🎨", "ホーム画面の表示", "家族ごとの表示・並び順、配色、天気"),
    MenuItem("rooms", "🚪", "部屋と機器", "部屋の並び順・追加、表示する機器"),
    MenuItem("thermo", "🌡", "温度計", "表示する温度計、名前"),
    MenuItem("voice", "🎤", "音声と AI", "呼びかけ、アシスタント、マイクのテスト、自宅の位置"),
    MenuItem("screensaver", "🖼", "スクリーンセーバー", "Google フォトのアルバム、表示までの時間"),
    MenuItem("calendar", "📅", "カレンダー（TimeTree）", "予定を表示するカレンダー"),
    MenuItem("cameras","📷","カメラ（直接つなぐ）","Home Assistant を通さずに見るネットワークカメラ"),
    MenuItem("echonet", "📡", "ECHONET Lite", "Home Assistant を通さずに直接つなぐ機器"),
    MenuItem("connection", "🌐", "外出先からの接続", "Home Assistant Cloud のリモートURL"),
    MenuItem("account", "👤", "アカウント", "管理者ログイン、ログアウト"),
    MenuItem("system", "⚙", "アプリの更新・設定の保存", "更新の確認、設定の書き出し・読み込み"),
)

@Composable
private fun MenuRow(m: MenuItem, onClick: () -> Unit) {
    androidx.compose.material3.Card(
        onClick = onClick,
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(m.icon, fontSize = 24.sp, modifier = Modifier.padding(end = 14.dp))
            Column(Modifier.weight(1f)) {
                Text(m.title, style = MaterialTheme.typography.titleMedium)
                Text(m.sub, style = MaterialTheme.typography.bodySmall)
            }
            Text("›", fontSize = 24.sp, color = MaterialTheme.colorScheme.primary)
        }
    }
}
