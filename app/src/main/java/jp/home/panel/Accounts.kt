@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package jp.home.panel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun Line(label: String, value: String, ok: Boolean? = null) {
    Row(verticalAlignment = Alignment.Top) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(190.dp))
        Text(
            (when (ok) { true -> "✓ "; false -> "－ "; null -> "" }) + value,
            style = MaterialTheme.typography.bodyMedium,
            color = if (ok == false) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** このアプリ・サーバーが使っているアカウントの一覧（パスワードやトークンそのものは表示しない） */
@Composable
fun AccountList(vm: MainViewModel) {
    val user by vm.userName.collectAsState()
    val admin by vm.isAdmin.collectAsState()
    val adminUser by vm.adminUser.collectAsState()
    val remote by vm.remoteUrl.collectAsState()
    val cams by vm.ipCameras.collectAsState()
    val all by vm.allEntities.collectAsState()
    val scope = rememberCoroutineScope()
    var server by remember { mutableStateOf<Pair<JSONObject?, JSONObject?>?>(null) }
    var users by remember { mutableStateOf<List<JSONObject>?>(null) }
    var usersMsg by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { server = vm.serverAccounts() }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("このアプリとホームパネルのサーバーが使っているアカウントです。パスワードや鍵（トークン）の中身は表示しません。", style = MaterialTheme.typography.bodySmall)
        Group("📱 このアプリ（Home Assistant）") {
            Line("普段使いのユーザー", (user ?: "確認中") + when (admin) { true -> "（管理者）"; false -> "（管理者以外）"; null -> "" }, true)
            Line("管理者ログイン（自動化の編集用）", adminUser ?: if (admin == true) "普段使いのユーザーが管理者" else "なし", adminUser != null || admin == true)
            Line("外出先からの接続", if (remote.isNotBlank()) "登録あり（Home Assistant Cloud）" else "なし", remote.isNotBlank())
            Text("ログインの情報は、この端末の中に暗号化して保存しています（設定の書き出しには含めません）。", style = MaterialTheme.typography.bodySmall)
        }
        Group("🖥 ホームパネルのサーバー（${vm.shServer.value}）") {
            val sv = server
            if (sv == null) Text("読んでいます…", style = MaterialTheme.typography.bodySmall)
            else if (sv.first == null && sv.second == null) Text("サーバーにつながりません", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            else {
                val c = sv.first?.optJSONObject("config")
                val haTok = c?.optBoolean("ha_token_set") == true
                Line("Home Assistant の鍵（温度調整）", if (haTok) "登録あり（${c?.optString("ha_url")}）" else "なし", haTok)
                val camTok = sv.second?.optBoolean("ha_token_set") == true
                Line("Home Assistant の鍵（カメラの記録）", if (camTok) "登録あり" else "なし", camTok)
                val aiseg = c?.optString("aiseg_url").orEmpty()
                val aisegPw = if (c?.optBoolean("aiseg_password_set") == true) "登録あり" else "なし"
                Line("AiSEG2", if (aiseg.isNotBlank()) "$aiseg・パスワード$aisegPw" else "なし", aiseg.isNotBlank())
                val rec = sv.second?.optJSONArray("cameras")?.length() ?: 0
                Line("記録しているカメラ", "${rec}台", rec > 0)
                Text("鍵は Home Assistant のプロフィール →「セキュリティ」→「長期アクセストークン」にも表示され、そこで無効にできます。", style = MaterialTheme.typography.bodySmall)
            }
        }
        Group("📷 カメラのアカウント") {
            if (cams.isEmpty()) Text("直接つなぐカメラはありません", style = MaterialTheme.typography.bodySmall)
            cams.forEach { c ->
                val pw = if (vm.ipCameraPassword(c.id).isNotEmpty()) "登録あり" else "なし"
                Line(c.name, if (c.user.isNotBlank()) "ユーザー名：${c.user}・パスワード$pw" else "ログインなし", c.user.isNotBlank())
            }
        }
        Group("📅 その他のサービス") {
            val tt = all.count { it.entityId.startsWith("calendar.timetree") }
            Line("TimeTree", if (tt > 0) "Home Assistant に登録（カレンダー${tt}個）。ログイン情報はアプリにはありません" else "なし", tt > 0)
        }
        Group("👥 Home Assistant のユーザー") {
            val list = users
            if (list == null) {
                Text("家族などのユーザーと、その権限の一覧です（管理者の権限で読みます）。", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = {
                    usersMsg = "読んでいます…"
                    scope.launch {
                        try { users = vm.haUsers(); usersMsg = null } catch (e: CancellationException) { throw e } catch (e: Exception) { usersMsg = e.message }
                    }
                }) { Text("ユーザーの一覧を読む") }
            } else {
                val people = list.filter { !it.optBoolean("system_generated") }
                val system = list.size - people.size
                people.sortedWith(compareByDescending<JSONObject> { it.optBoolean("is_owner") }.thenBy { it.optString("name") }).forEach { u ->
                    val groups = u.optJSONArray("group_ids")
                    val isAdm = (0 until (groups?.length() ?: 0)).any { groups?.optString(it) == "system-admin" }
                    val tags = listOfNotNull(
                        if (u.optBoolean("is_owner")) "所有者" else null,
                        if (isAdm) "管理者" else "ユーザー",
                        if (u.optBoolean("local_only")) "家の中だけ" else null,
                        if (!u.optBoolean("is_active", true)) "無効" else null,
                        u.optString("username").takeIf { it.isNotBlank() && it != "null" }?.let { "ログイン名：$it" },
                    )
                    Line(u.optString("name"), tags.joinToString("・"), u.optBoolean("is_active", true))
                }
                if (system > 0) Text("ほかに、Home Assistant が自動で作ったユーザーが${system}個あります（アドオンなど）。", style = MaterialTheme.typography.bodySmall)
            }
            usersMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
    }
}

/** 操作の記録：だれが・いつ・何を操作したか */
@Composable
fun OperationLogScreen(vm: MainViewModel) {
    var hours by remember { mutableStateOf(24) }
    var kind by remember { mutableStateOf("all") }
    var who by remember { mutableStateOf<String?>(null) }
    var rows by remember { mutableStateOf<List<MainViewModel.OpLog>?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }
    LaunchedEffect(hours, reload) {
        rows = null; err = null
        try { rows = vm.operationLog(hours) } catch (e: CancellationException) { throw e } catch (e: Exception) { err = e.message }
    }
    val chip = FilterChipDefaults.filterChipColors(
        selectedContainerColor = MaterialTheme.colorScheme.primary,
        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Home Assistant の記録から、人・自動化が操作したものと、ホームパネルのサーバーが行った操作を新しい順に表示します。", style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(24 to "24時間", 72 to "3日", 168 to "7日").forEach { (h, l) -> FilterChip(selected = hours == h, onClick = { hours = h }, label = { Text(l) }, colors = chip) }
            Text("　")
            listOf("all" to "すべて", "user" to "👤 人", "auto" to "⚙ 自動化", "server" to "🖥 サーバー").forEach { (k, l) ->
                FilterChip(selected = kind == k, onClick = { kind = k; who = null }, label = { Text(l) }, colors = chip)
            }
            OutlinedButton(onClick = { reload++ }) { Text("読み直す") }
        }
        err?.let { Text("読めませんでした：$it", color = MaterialTheme.colorScheme.error) }
        val list = rows
        if (list == null && err == null) Text("読んでいます…")
        if (list != null) {
            val people = list.filter { it.kind == "user" }.map { it.who }.distinct()
            if (kind == "user" && people.size > 1) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                people.forEach { p -> FilterChip(selected = who == p, onClick = { who = if (who == p) null else p }, label = { Text(p) }, colors = chip) }
            }
            val shown = list.filter { (kind == "all" || it.kind == kind) && (who == null || it.who == who) }
            if (shown.isEmpty()) Text("この期間の操作はありません", style = MaterialTheme.typography.bodyMedium)
            val df = DateTimeFormatter.ofPattern("M/d（E）", java.util.Locale.JAPANESE)
            val tf = DateTimeFormatter.ofPattern("H:mm")
            var lastDay = ""
            shown.take(400).forEach { r ->
                val z = Instant.ofEpochMilli(r.at).atZone(ZoneId.systemDefault())
                val day = z.format(df)
                if (day != lastDay) {
                    Text(day, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp))
                    lastDay = day
                }
                Row {
                    Text(z.format(tf), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(52.dp))
                    Text(
                        (when (r.kind) { "user" -> "👤 "; "server" -> "🖥 "; else -> "⚙ " }) + r.who,
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(150.dp),
                    )
                    Text(r.what, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                }
            }
            if (shown.size > 400) Text("ほかに ${shown.size - 400} 件あります（期間を短くしてください）", style = MaterialTheme.typography.bodySmall)
            Text("※ 人の名前は Home Assistant のユーザー（人に結びついたもの）です。サーバーの鍵で行った操作は、鍵を作ったユーザーの名前でも記録されます。",
                style = MaterialTheme.typography.labelSmall)
        }
    }
}
