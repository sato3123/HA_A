@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package jp.home.panel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import okhttp3.Authenticator
import okhttp3.Credentials
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Home Assistant を通さずに直接つなぐネットワークカメラ（RTSP）。
 * パスワードは暗号化した保存場所（TokenStore）に入れ、設定の書き出しには含めない。
 */
data class IpCamera(
    val id: String,
    val name: String,
    val mainUrl: String,      // 高画質（メインストリーム）
    val subUrl: String = "",  // 軽量（サブストリーム）。無ければメインを使う
    val snapshotUrl: String = "", // 静止画（ホーム画面のタイル用。無くてもよい）
    val user: String = "",
    val tcp: Boolean = false, // 映像が乱れるときは TCP で受け取る
) {
    val entityId get() = "camera.ip_$id"

    fun toEntity() = Entity(
        entityId, "streaming",
        JSONObject().put("friendly_name", name).put("direct", true),
    )

    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("main", mainUrl).put("sub", subUrl)
        .put("snap", snapshotUrl).put("user", user).put("tcp", tcp)

    companion object {
        fun fromJson(j: JSONObject) = IpCamera(
            j.optString("id"), j.optString("name"), j.optString("main"), j.optString("sub"),
            j.optString("snap"), j.optString("user"), j.optBoolean("tcp"),
        )

        fun listFrom(text: String?): List<IpCamera> = try {
            val a = JSONArray(text ?: "[]")
            (0 until a.length()).map { fromJson(a.getJSONObject(it)) }.filter { it.id.isNotBlank() }
        } catch (e: Exception) {
            emptyList()
        }

        fun listJson(list: List<IpCamera>) = JSONArray(list.map { it.toJson() }).toString()
    }
}

fun isDirectCamera(entityId: String) = entityId.startsWith("camera.ip_")

/** URL にユーザー名とパスワードを入れる（URL にすでに入っていればそのまま） */
fun withCredentials(url: String, user: String, password: String): String {
    if (user.isBlank()) return url
    val i = url.indexOf("://")
    if (i < 0) return url
    val rest = url.substring(i + 3)
    if (rest.substringBefore('/').contains('@')) return url
    fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    return url.substring(0, i + 3) + enc(user) + ":" + enc(password) + "@" + rest
}

/** 静止画を読むときのログイン（Basic と Digest の両方に対応。多くのカメラは Digest） */
class CameraAuthenticator(private val user: String, private val password: String) : Authenticator {
    private var nc = 0

    override fun authenticate(route: Route?, response: Response): Request? {
        if (user.isBlank() || response.priorResponse != null) return null // 2回目の失敗はあきらめる
        val header = response.headers("WWW-Authenticate")
        header.firstOrNull { it.startsWith("Digest", ignoreCase = true) }?.let { h ->
            return response.request.newBuilder().header("Authorization", digest(h, response.request)).build()
        }
        if (header.any { it.startsWith("Basic", ignoreCase = true) } || header.isEmpty()) {
            return response.request.newBuilder().header("Authorization", Credentials.basic(user, password)).build()
        }
        return null
    }

    private fun digest(h: String, req: Request): String {
        val p = Regex("""(\w+)=(?:"([^"]*)"|([^,\s]*))""").findAll(h.substringAfter(' '))
            .associate { it.groupValues[1].lowercase() to (it.groupValues[2].ifEmpty { it.groupValues[3] }) }
        val realm = p["realm"].orEmpty()
        val nonce = p["nonce"].orEmpty()
        val qop = p["qop"]?.split(',')?.map { it.trim() }?.firstOrNull { it == "auth" }
        val uri = req.url.encodedPath + (req.url.encodedQuery?.let { "?$it" } ?: "")
        fun md5(s: String) = MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
        val ha1 = md5("$user:$realm:$password")
        val ha2 = md5("${req.method}:$uri")
        val sb = StringBuilder("Digest username=\"$user\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$uri\"")
        if (qop != null) {
            nc++
            val ncs = "%08x".format(nc)
            val cnonce = java.util.UUID.randomUUID().toString().replace("-", "").take(16)
            sb.append(", qop=$qop, nc=$ncs, cnonce=\"$cnonce\", response=\"${md5("$ha1:$nonce:$ncs:$cnonce:$qop:$ha2")}\"")
        } else {
            sb.append(", response=\"${md5("$ha1:$nonce:$ha2")}\"")
        }
        p["opaque"]?.let { sb.append(", opaque=\"$it\"") }
        p["algorithm"]?.let { sb.append(", algorithm=$it") }
        return sb.toString()
    }
}

// ================= 設定画面 =================

@Composable
fun DirectCameraSettings(vm: MainViewModel) {
    val list by vm.ipCameras.collectAsState()
    var editing by remember { mutableStateOf<IpCamera?>(null) }
    var editingPass by remember { mutableStateOf<String?>(null) }
    var finding by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Home Assistant を通さずに、家の Wi-Fi の中のネットワークカメラ（RTSP 対応）に直接つなぎます。" +
                "遅れが少なく、Home Assistant が止まっていても見られます。追加したカメラはホーム画面の機器と同じように並びます。",
            style = MaterialTheme.typography.bodySmall,
        )
        list.forEach { c ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("📷 " + c.name, style = MaterialTheme.typography.bodyLarge)
                        Text(hostOf(c.mainUrl), style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { vm.openCamera(c.entityId) }) { Text("▶ 見る") }
                    TextButton(onClick = { editing = c }) { Text("✎ 編集") }
                }
            }
        }
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { finding = true }) { Text("🔍 カメラを探す") }
            androidx.compose.material3.OutlinedButton(onClick = { editingPass = null; editing = IpCamera(java.util.UUID.randomUUID().toString().take(8), "", "") }) { Text("＋ 手で追加") }
        }
        Text(
            "RTSP の URL の例（カメラの説明書や設定アプリで確認できます）：\n" +
                "・TP-Link Tapo：rtsp://192.168.1.x:554/stream1（軽量は stream2）\n" +
                "・Hikvision：rtsp://192.168.1.x:554/Streaming/Channels/101（軽量は 102）\n" +
                "・Dahua／Amcrest：rtsp://192.168.1.x:554/cam/realmonitor?channel=1&subtype=0（軽量は subtype=1）\n" +
                "・Reolink：rtsp://192.168.1.x:554/h264Preview_01_main（軽量は _sub）\n" +
                "Tapo などは、カメラのアプリで「カメラのアカウント」を作ってから、そのユーザー名とパスワードを入れてください。",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    editing?.let { c -> EditCameraDialog(vm, c, editingPass, onDismiss = { editing = null; editingPass = null }) }
    if (finding) FindCamerasDialog(onPick = { c, pw -> finding = false; editingPass = pw; editing = c }, onDismiss = { finding = false })
}

private fun hostOf(url: String) = url.substringAfter("://").substringAfter('@').substringBefore('/')

@Composable
private fun EditCameraDialog(vm: MainViewModel, c: IpCamera, initialPass: String? = null, onDismiss: () -> Unit) {
    val isNew = vm.ipCameras.value.none { it.id == c.id }
    var name by remember { mutableStateOf(c.name) }
    var main by remember { mutableStateOf(c.mainUrl) }
    var sub by remember { mutableStateOf(c.subUrl) }
    var snap by remember { mutableStateOf(c.snapshotUrl) }
    var user by remember { mutableStateOf(c.user) }
    var pass by remember { mutableStateOf(initialPass ?: if (isNew) "" else vm.ipCameraPassword(c.id)) }
    var tcp by remember { mutableStateOf(c.tcp) }
    var confirmDelete by remember { mutableStateOf(false) }
    val ok = name.isNotBlank() && main.startsWith("rtsp", ignoreCase = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "カメラを追加" else "カメラの設定") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("名前（例：玄関）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(main, { main = it.trim() }, label = { Text("高画質の RTSP URL") }, singleLine = true,
                    placeholder = { Text("rtsp://192.168.1.50:554/stream1") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(sub, { sub = it.trim() }, label = { Text("軽量の RTSP URL（なくても可）") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(user, { user = it.trim() }, label = { Text("ユーザー名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(pass, { pass = it }, label = { Text("パスワード") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(snap, { snap = it.trim() }, label = { Text("静止画の URL（なくても可）") }, singleLine = true,
                    placeholder = { Text("http://192.168.1.50/snapshot.jpg") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
                Text("静止画の URL を入れると、ホーム画面のタイルに10秒ごとの静止画が出ます。", style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("TCP で受け取る")
                        Text("映像が乱れる・止まるときにオン（少し遅れが増えます）", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = tcp, onCheckedChange = { tcp = it })
                }
                Text("パスワードはこの端末の中に暗号化して保存し、設定の書き出しには含めません。", style = MaterialTheme.typography.bodySmall)
                if (!isNew) {
                    if (confirmDelete) {
                        Button(onClick = { vm.deleteIpCamera(c.id); onDismiss() }) { Text("本当に削除する") }
                    } else {
                        TextButton(onClick = { confirmDelete = true }) { Text("このカメラを削除") }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = ok, onClick = {
                vm.saveIpCamera(IpCamera(c.id, name.trim(), main, sub, snap, user, tcp), pass)
                onDismiss()
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("やめる") } },
    )
}
