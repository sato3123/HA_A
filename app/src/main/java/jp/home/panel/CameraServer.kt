@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package jp.home.panel

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 設定画面：ホーム画面のカメラの見せ方 */
@Composable
fun CameraTileSettings(vm: MainViewModel) {
    val mode by vm.camTile.collectAsState()
    val chip = FilterChipDefaults.filterChipColors(
        selectedContainerColor = MaterialTheme.colorScheme.primary,
        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = mode == "live", onClick = { vm.setCamTile("live") }, label = { Text("動画（タップしなくても映る）") }, colors = chip)
            FilterChip(selected = mode == "snapshot", onClick = { vm.setCamTile("snapshot") }, label = { Text("静止画（10秒ごとに更新）") }, colors = chip)
        }
        Text(
            if (mode == "live") "ホーム画面のカメラのタイルに、そのまま動画を映します（音は出しません）。画質の方式はカメラごとに選んだもの（直接つなぐカメラは軽量の映像）を使います。" +
                "スクリーンセーバー中や全画面で見ている間、アプリが裏に回っている間は止めます。カメラが多くて動きが重いときは「静止画」にしてください。"
            else "ホーム画面には10秒ごとの静止画を出し、タップすると動画になります。",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** 設定画面：カメラの映像をサーバーで記録（2日分）し、動きのある時間・ない時間を分析する */
@Composable
fun CameraRecordingSettings(vm: MainViewModel) {
    val entities by vm.allEntities.collectAsState()
    val rec by vm.camRecording.collectAsState()
    val st by vm.camServerStatus.collectAsState()
    val msg by vm.camServerMsg.collectAsState()
    val tokenMsg by vm.serverTokenMsg.collectAsState()
    val scope = rememberCoroutineScope()
    val tests = remember { mutableStateMapOf<String, String>() }
    var motion by remember { mutableStateOf<MainViewModel.CamRec?>(null) }
    LaunchedEffect(Unit) { while (true) { vm.loadCamServer(); delay(60_000) } }
    val cams = entities.filter { it.domain == "camera" }.sortedBy { it.name }
    val chip = FilterChipDefaults.filterChipColors(
        selectedContainerColor = MaterialTheme.colorScheme.primary,
        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "選んだカメラの画像を、ホームパネルのサーバー（${vm.shServer.value}）が1分ごとに保存し（2日分。古いものは自動で消します）、" +
                "画面の動きの量を記録します。動きのある時間・ない時間をグラフで見られ、夜に動きがなくなった時刻（寝た時刻の目安）も分かります。" +
                "画像はサーバーの中だけに保存し、外には送りません。",
            style = MaterialTheme.typography.bodySmall,
        )
        st?.let { s ->
            if (!s.optBoolean("ffmpeg", true)) Text("⚠ サーバーに ffmpeg が入っていません。サーバーのセットアップ（setup.sh）をやり直してください。",
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            val needToken = rec.any { vm.ipCamera(it.entity) == null } && !s.optBoolean("ha_token_set")
            if (needToken) {
                Text("⚠ Home Assistant のカメラを記録するには、サーバーに Home Assistant の鍵（トークン）が必要です。", color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { vm.registerServerToken() }) { Text("サーバーに鍵を登録") }
                tokenMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
        msg?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (cams.isEmpty()) Text("カメラがありません。", style = MaterialTheme.typography.bodyMedium)
        val statusById = HashMap<String, org.json.JSONObject>()
        st?.optJSONArray("cameras")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { statusById[it.optString("id")] = it } }
        val tf = DateTimeFormatter.ofPattern("H:mm")
        cams.forEach { e ->
            val on = rec.any { it.entity == e.entityId }
            val sv = statusById[vm.camServerId(e.entityId)]?.optJSONObject("status")
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("📷 " + e.name, style = MaterialTheme.typography.bodyLarge)
                            Text(if (isDirectCamera(e.entityId)) "直接つなぐカメラ" else "Home Assistant のカメラ", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = on, onCheckedChange = { vm.setCameraRecording(e.entityId, e.name, it) })
                    }
                    if (on) {
                        val line = when {
                            sv == null -> "記録を待っています（1分ほどで始まります）"
                            !sv.isNull("error") && sv.optString("error").isNotBlank() -> "✕ 記録できません：" + sv.optString("error")
                            else -> "✓ 記録中・最後に記録した時刻 " + Instant.ofEpochMilli((sv.optDouble("at") * 1000).toLong()).atZone(ZoneId.systemDefault()).format(tf) +
                                "・動きの量 ${sv.optDouble("score")}%"
                        }
                        Text(line, style = MaterialTheme.typography.bodySmall,
                            color = if (line.startsWith("✕")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { motion = MainViewModel.CamRec(e.entityId, e.name) }) { Text("🎞 動きを見る") }
                            TextButton(onClick = {
                                tests[e.entityId] = "試しています…"
                                scope.launch { tests[e.entityId] = vm.testCamServer(e.entityId) }
                            }) { Text("いま記録して試す") }
                        }
                        tests[e.entityId]?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
        if (rec.isNotEmpty()) {
            val th = st?.optDouble("threshold")?.takeIf { !it.isNaN() } ?: 2.0
            Text("「動きあり」とみなす量：$th%（小さいほど少しの動きでも反応します）", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0.5, 1.0, 2.0, 3.0, 5.0).forEach { v ->
                    FilterChip(selected = th == v, onClick = { vm.setCamThreshold(v) }, label = { Text("$v%") }, colors = chip)
                }
            }
            Text("暗い部屋では、カメラの赤外線（夜間モード）の画像で動きを調べます。寝返りなど小さな動きも拾いたいときは小さめにしてください。",
                style = MaterialTheme.typography.bodySmall)
        }
    }
    motion?.let { r -> MotionDialog(vm, r.entity, r.name) { motion = null } }
}

/** 48時間の動きのグラフ・寝た/起きた時刻の目安・その時刻の画像 */
@Composable
fun MotionDialog(vm: MainViewModel, entity: String, name: String, onDismiss: () -> Unit) {
    var data by remember { mutableStateOf<org.json.JSONObject?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var hours by remember { mutableStateOf(48) }
    var sel by remember { mutableStateOf<Long?>(null) }
    var img by remember { mutableStateOf<ImageBitmap?>(null) }
    var imgMsg by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(entity) {
        try { data = vm.camMotion(entity, 48) } catch (e: CancellationException) { throw e } catch (e: Exception) { err = e.message }
    }
    LaunchedEffect(sel) {
        val t = sel ?: return@LaunchedEffect
        delay(250)
        imgMsg = "画像を読んでいます…"
        try {
            val b = vm.camFrame(entity, t / 1000)
            img = withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(b, 0, b.size)?.asImageBitmap() }
            imgMsg = if (img == null) "画像を読めませんでした" else null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            img = null; imgMsg = "この時刻の画像はありません"
        }
    }
    val tf = DateTimeFormatter.ofPattern("M/d H:mm")
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("閉じる") } },
        title = { Text("🎞 $name の動き") },
        text = {
            Column(Modifier.heightIn(max = 620.dp).widthIn(min = 300.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                err?.let { Text("読めませんでした：$it", color = MaterialTheme.colorScheme.error) }
                val d = data
                if (d == null && err == null) Text("読んでいます…")
                if (d != null) {
                    val th = d.optDouble("threshold", 2.0)
                    val m = d.optJSONArray("motion")
                    val pts = (0 until (m?.length() ?: 0)).mapNotNull { m?.optJSONArray(it) }.map { (it.optLong(0) * 1000) to it.optDouble(1) }
                    // 寝た・起きた時刻の目安
                    val sl = d.optJSONArray("sleep")
                    Text("寝た・起きた時刻の目安", style = MaterialTheme.typography.titleSmall)
                    if (sl == null || sl.length() == 0) Text("まだ分かりません（夜の記録がたまると表示します）", style = MaterialTheme.typography.bodySmall)
                    else (0 until sl.length()).mapNotNull { sl.optJSONObject(it) }.forEach { n ->
                        val day = runCatching { java.time.LocalDate.parse(n.optString("night")) }.getOrNull()
                        val label = day?.let { "${it.monthValue}/${it.dayOfMonth}（${"月火水木金土日"[it.dayOfWeek.value - 1]}）の夜" } ?: n.optString("night")
                        Text(
                            "🌙 $label：寝た " + (n.optString("slept").takeIf { it.isNotBlank() && it != "null" } ?: "－") +
                                "　☀ 起きた " + (n.optString("woke").takeIf { it.isNotBlank() && it != "null" } ?: "－"),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                    Text("（夜19時〜3時に動きが30分以上止まり始めた時刻を「寝た」、朝4時〜11時に動きが続き始めた時刻を「起きた」としています）",
                        style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(12 to "12時間", 24 to "24時間", 48 to "48時間").forEach { (h, l) ->
                            FilterChip(selected = hours == h, onClick = { hours = h }, label = { Text(l) })
                        }
                    }
                    if (pts.isEmpty()) Text("まだ記録がありません", style = MaterialTheme.typography.bodySmall)
                    else {
                        MotionChart(pts, th, hours, sel) { sel = it }
                        Text("濃い棒＝動きあり（$th% 以上）、薄い棒＝少しの動き、灰色の帯＝夜（19時〜7時）。グラフをタップ・なぞると、その時刻の画像を出します。",
                            style = MaterialTheme.typography.bodySmall)
                        // 時間帯ごとのまとめ（動きのあった分の割合）
                        val end = System.currentTimeMillis()
                        val recent = pts.filter { it.first >= end - hours * 3_600_000L }
                        val active = recent.count { it.second >= th }
                        if (recent.isNotEmpty()) Text("この${hours}時間で動きがあったのは 約${active}分（記録した ${recent.size} 分のうち ${active * 100 / recent.size}%）",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    sel?.let { t ->
                        Text(Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()).format(tf) + " ごろの画像", style = MaterialTheme.typography.titleSmall)
                        img?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)) }
                        imgMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        },
    )
}

@Composable
private fun MotionChart(pts: List<Pair<Long, Double>>, th: Double, hours: Int, sel: Long?, onPick: (Long) -> Unit) {
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val strong = MaterialTheme.colorScheme.primary
    val weak = strong.copy(alpha = 0.35f)
    val night = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)
    val grid = MaterialTheme.colorScheme.outlineVariant
    val selColor = MaterialTheme.colorScheme.error
    val end = System.currentTimeMillis()
    val start = end - hours * 3_600_000L
    val bin = 10 * 60_000L
    val nBins = (hours * 6)
    val bins = DoubleArray(nBins) { -1.0 }
    pts.forEach { (t, v) ->
        val i = ((t - start) / bin).toInt()
        if (i in 0 until nBins) bins[i] = maxOf(bins[i], v)
    }
    val top = (th * 3).coerceAtLeast(bins.maxOrNull() ?: 0.0).coerceAtMost(th * 6).coerceAtLeast(1.0)
    val zone = ZoneId.systemDefault()
    var widthPx by remember { mutableStateOf(1f) }
    val left = 8f
    fun tAt(x: Float) = start + ((x - left) / (widthPx - left)).coerceIn(0f, 1f).toDouble().let { (it * (end - start)).toLong() }
    Canvas(
        Modifier.fillMaxWidth().height(150.dp)
            .onSizeChanged { widthPx = it.width.toFloat() }
            .pointerInput(hours) { detectTapGestures { onPick(tAt(it.x)) } }
            .pointerInput(hours) { detectDragGestures { c, _ -> onPick(tAt(c.position.x)) } },
    ) {
        val bottom = size.height - 16.dp.toPx()
        val w = size.width - left
        fun x(t: Long) = left + w * ((t - start).toFloat() / (end - start))
        // 夜の帯（19時〜翌7時）
        var d = Instant.ofEpochMilli(start).atZone(zone).toLocalDate().minusDays(1)
        while (d.atStartOfDay(zone).toInstant().toEpochMilli() < end) {
            val ns = d.atTime(19, 0).atZone(zone).toInstant().toEpochMilli()
            val ne = d.plusDays(1).atTime(7, 0).atZone(zone).toInstant().toEpochMilli()
            val a = x(ns.coerceAtLeast(start)); val b = x(ne.coerceAtMost(end))
            if (b > a) drawRect(night, Offset(a, 0f), Size(b - a, bottom))
            d = d.plusDays(1)
        }
        // しきい値の線
        val yTh = bottom - (th / top).toFloat() * bottom
        drawLine(grid, Offset(left, yTh), Offset(size.width, yTh), 1f)
        val bw = w / nBins
        bins.forEachIndexed { i, v ->
            if (v < 0) return@forEachIndexed
            val h = ((v / top).toFloat().coerceIn(0.02f, 1f)) * bottom
            drawRect(if (v >= th) strong else weak, Offset(left + i * bw, bottom - h), Size((bw * 0.85f).coerceAtLeast(1f), h))
        }
        // 時刻の目安
        val step = if (hours <= 12) 3 else 6
        var tick = Instant.ofEpochMilli(start).atZone(zone).withMinute(0).withSecond(0).withNano(0).plusHours(1)
        while (tick.hour % step != 0) tick = tick.plusHours(1)
        while (tick.toInstant().toEpochMilli() < end) {
            val xx = x(tick.toInstant().toEpochMilli())
            val tl = measurer.measure(if (tick.hour == 0) "${tick.monthValue}/${tick.dayOfMonth}" else "${tick.hour}時", style)
            drawText(tl, topLeft = Offset((xx - tl.size.width / 2).coerceIn(0f, size.width - tl.size.width), bottom + 1))
            tick = tick.plusHours(step.toLong())
        }
        sel?.let { drawLine(selColor, Offset(x(it), 0f), Offset(x(it), bottom), 2.dp.toPx()) }
    }
}
