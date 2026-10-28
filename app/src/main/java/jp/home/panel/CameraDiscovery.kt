@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package jp.home.panel

import android.content.Context
import android.net.wifi.WifiManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** 見つかったカメラ */
data class FoundCamera(
    val ip: String,
    val name: String,       // ONVIF で分かった名前・機種（分からなければ空）
    val maker: String,      // tapo / hik / dahua / reolink / other
    val onvif: String?,     // ONVIF の窓口（http://…/onvif/device_service）
    val rtsp: Boolean,      // RTSP のポート（554）が開いている
)

/** メーカーごとの RTSP の URL の決まり（高画質, 軽量） */
val CAMERA_MAKERS = listOf(
    Triple("tapo", "TP-Link Tapo", "/stream1" to "/stream2"),
    Triple("hik", "Hikvision", "/Streaming/Channels/101" to "/Streaming/Channels/102"),
    Triple("dahua", "Dahua・Amcrest", "/cam/realmonitor?channel=1&subtype=0" to "/cam/realmonitor?channel=1&subtype=1"),
    Triple("reolink", "Reolink", "/h264Preview_01_main" to "/h264Preview_01_sub"),
    Triple("other", "その他", "/" to ""),
)

object CameraFinder {
    private val http by lazy { OkHttpClient.Builder().connectTimeout(4, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS).build() }

    private fun guessMaker(text: String): String {
        val t = text.lowercase()
        return when {
            "tapo" in t || "tp-link" in t || "tplink" in t || "tp-ipc" in t -> "tapo"
            "hikvision" in t || "hik" in t -> "hik"
            "dahua" in t || "amcrest" in t || "imou" in t -> "dahua"
            "reolink" in t -> "reolink"
            else -> "other"
        }
    }

    /** この端末の IPv4 アドレス（家の Wi-Fi） */
    private fun localIpv4(): Inet4Address? = NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .firstOrNull { it.isSiteLocalAddress }

    /** ONVIF の WS-Discovery（239.255.255.250:3702 に問い合わせ、3秒待つ） */
    private fun wsDiscovery(ctx: Context): List<FoundCamera> {
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("homepanel-camera").apply { setReferenceCounted(false); acquire() }
        val out = LinkedHashMap<String, FoundCamera>()
        try {
            DatagramSocket().use { sock ->
                sock.soTimeout = 600
                val probe = """<?xml version="1.0" encoding="UTF-8"?>
<e:Envelope xmlns:e="http://www.w3.org/2003/05/soap-envelope" xmlns:w="http://schemas.xmlsoap.org/ws/2004/08/addressing" xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery" xmlns:dn="http://www.onvif.org/ver10/network/wsdl">
<e:Header><w:MessageID>uuid:${java.util.UUID.randomUUID()}</w:MessageID><w:To e:mustUnderstand="true">urn:schemas-xmlsoap-org:ws:2005:04:discovery</w:To><w:Action e:mustUnderstand="true">http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</w:Action></e:Header>
<e:Body><d:Probe><d:Types>dn:NetworkVideoTransmitter</d:Types></d:Probe></e:Body></e:Envelope>""".toByteArray()
                val group = InetAddress.getByName("239.255.255.250")
                repeat(2) { sock.send(DatagramPacket(probe, probe.size, group, 3702)) }
                val end = System.currentTimeMillis() + 3000
                val buf = ByteArray(16384)
                while (System.currentTimeMillis() < end) {
                    val p = DatagramPacket(buf, buf.size)
                    try { sock.receive(p) } catch (e: SocketTimeoutException) { continue }
                    val text = String(p.data, 0, p.length)
                    val ip = p.address.hostAddress ?: continue
                    val xaddr = Regex("XAddrs>([^<]+)<").find(text)?.groupValues?.get(1)?.trim()?.split(Regex("\\s+"))
                        ?.firstOrNull { it.contains(ip) } ?: Regex("XAddrs>([^<]+)<").find(text)?.groupValues?.get(1)?.trim()?.split(Regex("\\s+"))?.firstOrNull()
                    val scopes = Regex("Scopes>([^<]+)<").find(text)?.groupValues?.get(1).orEmpty()
                    fun scope(k: String) = Regex("onvif://www\\.onvif\\.org/$k/([^\\s]+)").find(scopes)?.groupValues?.get(1)
                        ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
                    val name = listOfNotNull(scope("name"), scope("hardware")).distinct().joinToString(" ")
                    out[ip] = FoundCamera(ip, name, guessMaker("$name $scopes $xaddr"), xaddr, false)
                }
            }
        } catch (e: Exception) {
            // マルチキャストが使えないネットワーク：ポートの確認だけで探す
        } finally {
            runCatching { lock.release() }
        }
        return out.values.toList()
    }

    private fun portOpen(ip: String, port: Int, timeout: Int = 350): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(ip, port), timeout); true }
    } catch (e: Exception) {
        false
    }

    /** ONVIF と、家のネットワーク（/24）の RTSP のポート（554）を調べる */
    suspend fun find(ctx: Context, progress: (Float) -> Unit): List<FoundCamera> = withContext(Dispatchers.IO) {
        val onvif = wsDiscovery(ctx)
        progress(0.15f)
        val me = localIpv4() ?: return@withContext onvif
        val prefix = me.hostAddress!!.substringBeforeLast('.')
        val gate = Semaphore(48)
        var done = 0
        val open = coroutineScope {
            (1..254).map { n ->
                async {
                    gate.withPermit {
                        val ip = "$prefix.$n"
                        val ok = ip != me.hostAddress && portOpen(ip, 554)
                        synchronized(this@CameraFinder) { done++; progress(0.15f + 0.85f * done / 254) }
                        if (ok) ip else null
                    }
                }
            }.awaitAll().filterNotNull().toSet()
        }
        val byIp = onvif.associateBy { it.ip }.toMutableMap()
        open.forEach { ip -> byIp[ip] = byIp[ip]?.copy(rtsp = true) ?: FoundCamera(ip, "", "other", null, true) }
        byIp.values.sortedBy { it.ip.substringAfterLast('.').toIntOrNull() ?: 0 }
    }

    // ---- ONVIF で映像の URL を調べる（ユーザー名・パスワードが必要） ----

    private fun security(user: String, pass: String): String {
        if (user.isBlank()) return ""
        val nonce = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        val created = java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString()
        val digest = MessageDigest.getInstance("SHA-1").digest(nonce + created.toByteArray() + pass.toByteArray())
        fun b64(b: ByteArray) = android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP)
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        return """<s:Header><Security s:mustUnderstand="1" xmlns="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd"><UsernameToken><Username>${esc(user)}</Username><Password Type="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest">${b64(digest)}</Password><Nonce EncodingType="http://docs.oasis-open.org/wss/2004/01/oasis-200401-soap-message-security-1.0#Base64Binary">${b64(nonce)}</Nonce><Created xmlns="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd">$created</Created></UsernameToken></Security></s:Header>"""
    }

    private fun soap(url: String, user: String, pass: String, body: String): String {
        val xml = """<?xml version="1.0" encoding="UTF-8"?><s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope">${security(user, pass)}<s:Body>$body</s:Body></s:Envelope>"""
        val req = Request.Builder().url(url).post(xml.toRequestBody("application/soap+xml; charset=utf-8".toMediaType())).build()
        return http.newCall(req).execute().use { r ->
            val t = r.body?.string().orEmpty()
            if (r.code == 400 || r.code == 401 || t.contains("NotAuthorized")) throw HaException("ユーザー名かパスワードが違います（ONVIF）")
            if (!r.isSuccessful) throw HaException("ONVIF でエラーになりました (HTTP ${r.code})")
            t
        }
    }

    /** 高画質・軽量の順に RTSP の URL を返す */
    suspend fun onvifStreams(xaddr: String, user: String, pass: String): List<String> = withContext(Dispatchers.IO) {
        val caps = soap(xaddr, user, pass,
            """<GetCapabilities xmlns="http://www.onvif.org/ver10/device/wsdl"><Category>Media</Category></GetCapabilities>""")
        val media = Regex("Media>.*?XAddr>([^<]+)<", RegexOption.DOT_MATCHES_ALL).find(caps)?.groupValues?.get(1)?.trim() ?: xaddr
        val profiles = soap(media, user, pass, """<GetProfiles xmlns="http://www.onvif.org/ver10/media/wsdl"/>""")
        val tokens = Regex("Profiles[^>]*token=\"([^\"]+)\"").findAll(profiles).map { it.groupValues[1] }.distinct().take(2).toList()
        if (tokens.isEmpty()) throw HaException("映像の設定（プロファイル）が見つかりません")
        tokens.mapNotNull { tok ->
            val r = soap(media, user, pass,
                """<GetStreamUri xmlns="http://www.onvif.org/ver10/media/wsdl"><StreamSetup><Stream xmlns="http://www.onvif.org/ver10/schema">RTP-Unicast</Stream><Transport xmlns="http://www.onvif.org/ver10/schema"><Protocol>RTSP</Protocol></Transport></StreamSetup><ProfileToken>$tok</ProfileToken></GetStreamUri>""")
            Regex("Uri>([^<]+)<").find(r)?.groupValues?.get(1)?.trim()?.replace("&amp;", "&")
        }
    }
}

/** 「カメラを探す」：見つかったカメラを選んで、メーカーの決まりか ONVIF で URL を入れ、登録の画面へ */
@Composable
fun FindCamerasDialog(onPick: (IpCamera, String) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var progress by remember { mutableStateOf(0f) }
    var found by remember { mutableStateOf<List<FoundCamera>?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var picked by remember { mutableStateOf<FoundCamera?>(null) }
    var run by remember { mutableStateOf(0) }
    LaunchedEffect(run) {
        found = null; err = null; progress = 0f
        try { found = CameraFinder.find(ctx) { progress = it } } catch (e: CancellationException) { throw e } catch (e: Exception) { err = e.message }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        dismissButton = { TextButton(onClick = onDismiss) { Text("やめる") } },
        confirmButton = { if (picked == null && found != null) TextButton(onClick = { run++ }) { Text("もう一度探す") } },
        title = { Text("🔍 カメラを探す") },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val p = picked
                if (p != null) PickedCamera(p, onBack = { picked = null }, onNext = onPick)
                else {
                if (found == null) {
                    Text("家のネットワークの中のカメラを探しています…（ONVIF の問い合わせと、RTSP のポート 554 の確認。30秒ほどかかります）",
                        style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                }
                err?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                found?.let { list ->
                    if (list.isEmpty()) Text("カメラが見つかりませんでした。カメラの電源と Wi-Fi、カメラのアプリで RTSP／ONVIF が有効になっているか確認してください。",
                        style = MaterialTheme.typography.bodyMedium)
                    else Text("見つかった機器：${list.size}台（タップして登録）", style = MaterialTheme.typography.titleSmall)
                    list.forEach { c ->
                        Card(onClick = { picked = c }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text("📷 " + c.ip + (c.name.takeIf { it.isNotBlank() }?.let { "　$it" } ?: ""), style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    listOfNotNull(
                                        if (c.onvif != null) "ONVIF 対応" else null,
                                        if (c.rtsp) "RTSP（554）" else null,
                                        CAMERA_MAKERS.firstOrNull { it.first == c.maker && c.maker != "other" }?.second,
                                    ).joinToString("・"),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
                }
            }
        },
    )
}

@Composable
private fun PickedCamera(c: FoundCamera, onBack: () -> Unit, onNext: (IpCamera, String) -> Unit) {
    val scope = rememberCoroutineScope()
    var maker by remember { mutableStateOf(c.maker) }
    var name by remember { mutableStateOf(c.name) }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var streams by remember { mutableStateOf<List<String>?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }
    Text("${c.ip} を登録します", style = MaterialTheme.typography.titleSmall)
    OutlinedTextField(name, { name = it }, label = { Text("名前（例：子ども部屋）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(user, { user = it.trim() }, label = { Text("カメラのユーザー名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(pass, { pass = it }, label = { Text("カメラのパスワード") }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
    Text("Tapo などは、カメラのアプリの「高度な設定 → カメラのアカウント」で作ったユーザー名とパスワードです。", style = MaterialTheme.typography.bodySmall)
    if (c.onvif != null) {
        OutlinedButton(onClick = {
            msg = "ONVIF で調べています…"
            scope.launch {
                try {
                    streams = CameraFinder.onvifStreams(c.onvif, user, pass)
                    msg = "✓ 映像の URL が分かりました"
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    msg = "✕ ${e.message}（下のメーカーから選んでも登録できます）"
                }
            }
        }) { Text("ONVIF で映像の URL を調べる") }
        msg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        streams?.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
    if (streams.isNullOrEmpty()) {
        Text("メーカー（URL の決まりを入れます）", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CAMERA_MAKERS.forEach { (k, l, _) -> FilterChip(selected = maker == k, onClick = { maker = k }, label = { Text(l) }) }
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onBack) { Text("← 一覧へ") }
        androidx.compose.material3.Button(onClick = {
            val s = streams
            val (main, sub) = if (!s.isNullOrEmpty()) s[0] to (s.getOrNull(1) ?: "") else {
                val (a, b) = CAMERA_MAKERS.first { it.first == maker }.third
                "rtsp://${c.ip}:554$a" to (if (b.isBlank()) "" else "rtsp://${c.ip}:554$b")
            }
            onNext(
                IpCamera(java.util.UUID.randomUUID().toString().take(8), name.ifBlank { "カメラ ${c.ip.substringAfterLast('.')}" }, main, sub,
                    "", user, tcp = maker == "tapo"),
                pass,
            )
        }) { Text("次へ（確認して保存）") }
    }
}
