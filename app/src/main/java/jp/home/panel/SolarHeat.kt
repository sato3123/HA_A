@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package jp.home.panel

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** ホーム画面のエコキュート（残りのお湯・沸き上げの状態・おひさま沸き上げ） */
@Composable
fun EcoCuteCard(vm: MainViewModel) {
    val v by vm.solarHeat.collectAsState()
    val enabled by vm.shEnabled.collectAsState()
    val s = v ?: return
    if (s.device == null && !s.server) return
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("🛁 " + (s.deviceName ?: "エコキュート"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(
                    when {
                        s.serverError != null -> "サーバーに届きません"
                        !s.online -> "応答なし"
                        s.heating == true -> "♨ 沸き上げ中"
                        else -> "沸き上げ停止中"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = if (s.heating == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (s.remainL != null) {
                val pct = s.tankL?.takeIf { it > 0 }?.let { (s.remainL * 100 / it).coerceIn(0, 100) }
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("残りのお湯", style = MaterialTheme.typography.bodySmall)
                    Text("${s.remainL}L", fontSize = 22.sp, fontWeight = FontWeight.Medium)
                    s.tankL?.let { Text("／ ${it}L" + (pct?.let { p -> "（$p%）" } ?: ""), style = MaterialTheme.typography.bodySmall) }
                }
                pct?.let { LinearProgressIndicator(progress = { it / 100f }, modifier = Modifier.fillMaxWidth()) }
            }
            Text(
                "☀ おひさま沸き上げ：" + if (enabled) s.status else "オフ",
                style = MaterialTheme.typography.bodySmall,
            )
            if (enabled) s.plan?.let { p ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        (if (p.ok) "✓ " else "✕ ") + "毎日の設定（${planTime(p.at)}）：" + p.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (p.ok) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                    if (!p.ok) TextButton(onClick = { vm.retrySolarPlan() }) { Text("もう一度設定") }
                }
            }
            if (enabled && s.history.size >= 2) SurplusChart(s.history, Modifier.fillMaxWidth().height(48.dp))
            if (s.shift == true) {
                Text("今夜の沸き上げは昼にずらす設定です" + (s.shiftHour?.let { "（${it}時から）" } ?: ""), style = MaterialTheme.typography.bodySmall)
            }
            if (s.canManual) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.ecoManualStart() }) { Text("今すぐ沸き増し") }
                    if (s.manual == true) TextButton(onClick = { vm.ecoBackToAuto() }) { Text("自動に戻す") }
                }
            }
        }
    }
}

/** 設定画面：おひさま沸き上げ */
@Composable
fun SolarHeatSettings(vm: MainViewModel) {
    val enabled by vm.shEnabled.collectAsState()
    val device by vm.shDevice.collectAsState()
    val start by vm.shStart.collectAsState()
    val end by vm.shEnd.collectAsState()
    val threshold by vm.shThreshold.collectAsState()
    val sensor by vm.shSensor.collectAsState()
    val invert by vm.shInvert.collectAsState()
    val shift by vm.shShift.collectAsState()
    val log by vm.shLog.collectAsState()
    val planHour by vm.shPlanHour.collectAsState()
    val source by vm.shSource.collectAsState()
    val view by vm.solarHeat.collectAsState()
    val elOn by vm.elEnabled.collectAsState()
    var pickSensor by remember { mutableStateOf(false) }
    val chipColors = FilterChipDefaults.filterChipColors(
        selectedContainerColor = MaterialTheme.colorScheme.primary,
        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
    )

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "太陽光で余った電気でエコキュートのお湯を沸かして、電気を買う量を減らします。\n" +
                "・前の日の夜に天気予報を見て、晴れならエコキュートの「昼間沸き上げ（エネルギーシフト）」を使い、夜の沸き上げを昼にずらします。\n" +
                "・昼の時間帯に売電が続いたら、沸き増しを始めます。余らなくなったら、自動の沸き上げに戻します。\n" +
                "・エコキュートとは ECHONET Lite で直接やりとりします。お湯を止める操作はしません。",
            style = MaterialTheme.typography.bodySmall,
        )
        // 動かす場所
        val where by vm.shWhere.collectAsState()
        val server by vm.shServer.collectAsState()
        val serverMsg by vm.shServerMsg.collectAsState()
        var serverText by remember(server) { mutableStateOf(server.removePrefix("http://")) }
        Text("動かす場所", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = where == "server", onClick = { vm.setSolarWhere("server", serverText) }, label = { Text("サーバー（おすすめ）") }, colors = chipColors)
            FilterChip(selected = where == "local", onClick = { vm.setSolarWhere("local") }, label = { Text("このタブレット") }, colors = chipColors)
        }
        Text(
            if (where == "server") "ホームパネルのサーバー（Proxmox のコンテナ）が、天気予報の確認・売電のリアルタイム確認・沸き上げの調整をします。このタブレットやスマートフォンを切っていても動き続けます。この画面では状態を見たり設定を変えたりできます。"
            else "このタブレットのアプリが動いている間だけ働きます。",
            style = MaterialTheme.typography.bodySmall,
        )
        if (where == "server") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(serverText, { serverText = it.trim() }, singleLine = true, label = { Text("サーバーの IP アドレス") },
                    modifier = Modifier.weight(1f))
                TextButton(onClick = { vm.setSolarWhere("server", serverText) }) { Text("保存") }
            }
            Button(onClick = { vm.pushSolarToServer() }) { Text("このタブレットの設定をサーバーに送る") }
            Text("見つけたエコキュートとスマートメーター、Home Assistant の自宅の位置（天気予報に使います）と、下の時間帯などの設定をサーバーに送ります。最初に1回押してください。", style = MaterialTheme.typography.bodySmall)
            serverMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            view?.serverError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
        if (!elOn && where != "server") {
            Text("先に ⚙ 設定 →「ECHONET Lite」をオンにして、「機器を探す」でエコキュートを見つけてください。", color = MaterialTheme.colorScheme.error)
        }
        SwitchRow("おひさま沸き上げを使う", enabled, sub = view?.status) { vm.setSolarHeat(enabled = it) }

        // 毎日の設定の結果と、手動でのやり直し
        Text("毎日の設定", style = MaterialTheme.typography.labelLarge)
        val plan = view?.plan
        Text(
            if (plan == null) "まだ設定していません（毎日 ${planHour}時に、翌日の天気予報を見て設定します）"
            else (if (plan.ok) "✓ " else "✕ ") + planTime(plan.at) + "　" + plan.message,
            style = MaterialTheme.typography.bodyMedium,
            color = if (plan != null && !plan.ok) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        Text("毎日 ${planHour}時に設定して、読み直して確かめます。うまくいかなければ10分ごとに自動でやり直し、朝7時にも設定が残っているか確かめます。", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.retrySolarPlan() }, enabled = enabled) { Text(if (plan != null && !plan.ok) "もう一度設定する" else "今すぐ設定する") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("設定する時刻", modifier = Modifier.padding(end = 8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(18, 19, 20, 21, 22).forEach { h ->
                    FilterChip(selected = planHour == h, onClick = { vm.setSolarHeat(planHour = h) }, label = { Text("${h}時") }, colors = chipColors)
                }
            }
        }

        // エコキュート
        Text("エコキュート", style = MaterialTheme.typography.labelLarge)
        val heaters = vm.waterHeaters
        if (heaters.isEmpty()) {
            Text("見つかっていません。⚙ 設定 →「ECHONET Lite」で「機器を探す」を押してください（AiSEG2 や無線 LAN アダプターでつながっているエコキュートが見つかります）。",
                style = MaterialTheme.typography.bodySmall)
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                heaters.forEachIndexed { i, h ->
                    FilterChip(selected = device == h.key || (device.isBlank() && i == 0), onClick = { vm.setSolarHeat(device = h.key) },
                        label = { Text("${h.name}（${h.ip}）") }, colors = chipColors)
                }
            }
            view?.let { v ->
                Text(
                    "このエコキュートでできること：" + listOfNotNull(
                        if (v.canManual) "沸き増し" else null,
                        if (v.canShift) "昼間沸き上げ（エネルギーシフト）" else null,
                    ).joinToString("・").ifBlank { "表示のみ（操作に対応していません）" },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        // 時間帯
        Text("昼に沸かす時間帯", style = MaterialTheme.typography.labelLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("開始", modifier = Modifier.padding(end = 8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(9, 10, 11, 12).forEach { h ->
                    FilterChip(selected = start == h, onClick = { vm.setSolarHeat(start = h) }, label = { Text("${h}時") }, colors = chipColors)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("終了", modifier = Modifier.padding(end = 8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(13, 14, 15, 16).filter { it > start }.forEach { h ->
                    FilterChip(selected = end == h, onClick = { vm.setSolarHeat(end = h) }, label = { Text("${h}時") }, colors = chipColors)
                }
            }
        }

        // 沸き増しを始める余剰
        Text("沸き増しを始める余剰電力", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(800, 1000, 1200, 1500, 2000).forEach { w ->
                FilterChip(selected = threshold == w, onClick = { vm.setSolarHeat(threshold = w) }, label = { Text("${w / 1000.0}kW") }, colors = chipColors)
            }
        }
        Text("エコキュートが沸かすときの電力（多くの機種で 1〜1.5kW）より少し多めにすると、電気を買わずに沸かせます。", style = MaterialTheme.typography.bodySmall)

        // 余剰電力の読み取り先
        Text("売電の量の読み取り先", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("auto" to "自動", "meter" to "スマートメーター（ECHONET Lite）", "ha" to "Home Assistant のセンサー").forEach { (k, l) ->
                FilterChip(selected = source == k, onClick = { vm.setSolarHeat(source = k) }, label = { Text(l) }, colors = chipColors)
            }
        }
        Text(
            (if (vm.hasMeter) "スマートメーターが見つかりました。15秒ごとに売電・買電をリアルタイムに読み、沸き増し中に電気を買い始めたら3分で自動に戻します。"
            else "スマートメーター（ECHONET Lite の低圧スマート電力量メータ）は見つかっていません。HEMS（AiSEG2 など）がスマートメーターとつながっていれば、「機器を探す」で見つかります。") +
                "「自動」は、スマートメーターがあればそれを、なければ Home Assistant のセンサーを使います。",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "いま：" + (view?.surplusW?.let { if (it >= 0) "売電 ${(it / 100).toInt() / 10.0}kW" else "買電 ${(-it / 100).toInt() / 10.0}kW" } ?: "－") +
                (view?.sensorName?.let { "（$it）" } ?: ""),
            style = MaterialTheme.typography.bodyMedium,
        )
        view?.history?.takeIf { it.size >= 2 }?.let { SurplusChart(it, Modifier.fillMaxWidth().height(80.dp)) }

        Text("Home Assistant のセンサー（読み取り先が Home Assistant のとき）", style = MaterialTheme.typography.labelLarge)
        Text(
            (if (sensor.isBlank()) "自動（Home Assistant のエネルギーの売電）：" else "") + (view?.sensorName ?: "見つかりません") +
                (view?.surplusW?.let { "　いま ${(it / 100).toInt() / 10.0}kW" } ?: ""),
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { pickSensor = true }) { Text("センサーを選ぶ") }
            if (sensor.isNotBlank()) TextButton(onClick = { vm.setSolarHeat(sensor = "") }) { Text("自動に戻す") }
        }
        Text(
            "売電の電力（W）のセンサーがあれば、それがいちばん正確です。電力量（kWh）のセンサーでは、5分以上の増え方から計算します" +
                "（スマートメーターの値は30分ごとにしか変わらないため、反応が遅くなります）。",
            style = MaterialTheme.typography.bodySmall,
        )
        SwitchRow("センサーの向きを逆にする", invert, sub = "買電がプラス・売電がマイナスで表示される電力センサーのときにオン") { vm.setSolarHeat(invert = it) }

        SwitchRow(
            "晴れの日は夜の沸き上げを昼にずらす", shift,
            sub = "前の日の20時以降に、翌日の天気予報（ホーム画面の天気）で決めます" +
                if (view?.canShift == false) "。このエコキュートは対応していないため、昼の沸き増しだけ行います" else "",
        ) { vm.setSolarHeat(shift = it) }

        Text("エコキュートの対応状況", style = MaterialTheme.typography.labelLarge)
        val diag by vm.ecoDiag.collectAsState()
        OutlinedButton(onClick = { vm.diagnoseEco() }) { Text("🩺 対応状況を調べる") }
        Text("「設定できませんでした」と出るときは、ここでエコキュートが設定を受け付ける項目を確かめられます。", style = MaterialTheme.typography.bodySmall)
        diag?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

        Text("動作の記録", style = MaterialTheme.typography.labelLarge)
        if (log.isEmpty()) Text("まだありません", style = MaterialTheme.typography.bodySmall)
        log.take(25).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
    }

    if (pickSensor) {
        var q by remember { mutableStateOf("") }
        val all = remember { vm.surplusSensorCandidates() }
        AlertDialog(
            onDismissRequest = { pickSensor = false },
            title = { Text("売電のセンサーを選ぶ") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedTextField(q, { q = it }, singleLine = true, label = { Text("名前で絞り込み（例：売電）") }, modifier = Modifier.fillMaxWidth())
                    androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth().padding(top = 4.dp).heightIn(max = 360.dp)) {
                        items(all.filter { q.isBlank() || it.name.contains(q, true) || it.entityId.contains(q, true) }.size) { i ->
                            val e = all.filter { q.isBlank() || it.name.contains(q, true) || it.entityId.contains(q, true) }[i]
                            Column(Modifier.fillMaxWidth().clickable { vm.setSolarHeat(sensor = e.entityId); pickSensor = false }.padding(vertical = 6.dp)) {
                                Text(e.name, style = MaterialTheme.typography.bodyLarge)
                                Text("${e.state} ${e.attributes.optString("unit_of_measurement")}・${e.entityId}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickSensor = false }) { Text("閉じる") } },
        )
    }
}


private fun planTime(ms: Long): String =
    java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("M/d H:mm"))

/** 直近1時間の売電（上・緑）と買電（下・赤）の変化 */
@Composable
private fun SurplusChart(pts: List<Pair<Long, Double>>, modifier: Modifier) {
    val grid = MaterialTheme.colorScheme.outlineVariant
    androidx.compose.foundation.Canvas(modifier) {
        val end = System.currentTimeMillis()
        val start = end - 3_600_000L
        val maxAbs = (pts.maxOf { kotlin.math.abs(it.second) }).coerceAtLeast(1000.0)
        val mid = size.height / 2
        fun x(t: Long) = size.width * ((t - start).toFloat() / (end - start)).coerceIn(0f, 1f)
        fun y(v: Double) = mid - (v / maxAbs).toFloat() * (mid - 2)
        drawLine(grid, androidx.compose.ui.geometry.Offset(0f, mid), androidx.compose.ui.geometry.Offset(size.width, mid), 1f)
        for (i in 1 until pts.size) {
            val a = pts[i - 1]; val b = pts[i]
            val c = if (b.second >= 0) androidx.compose.ui.graphics.Color(0xFF66BB6A) else androidx.compose.ui.graphics.Color(0xFFEF5350)
            drawLine(c, androidx.compose.ui.geometry.Offset(x(a.first), y(a.second)), androidx.compose.ui.geometry.Offset(x(b.first), y(b.second)), 3f)
        }
    }
}
