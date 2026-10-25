package jp.home.panel

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** 見つけた ECHONET Lite の機器（1台の中の1つの機能） */
data class ElDevice(
    val ip: String,
    val eoj: Eoj,
    val name: String,                 // 表示名（変更できる）
    val props: Map<Int, ByteArray> = emptyMap(),
    val setMap: Set<Int> = emptySet(),
    val getMap: Set<Int> = emptySet(),
    val online: Boolean = true,
    val updatedAt: Long = 0L,
) {
    val cls: ElClass get() = elClassOf(eoj.classCode)
    /** アプリ内での ID（例：climate.el_192_168_1_50_013001） */
    val entityId: String get() = haDomain + ".el_" + ip.replace('.', '_') + "_" + eoj.hex().lowercase()
    val haDomain: String get() = when (cls.kind) {
        ElKind.AIRCON -> "climate"
        ElKind.LIGHT -> "light"
        ElKind.COVER -> "cover"
        ElKind.ONOFF -> "switch"
        else -> "sensor"
    }
    val key: String get() = ip + "/" + eoj.hex()
    fun on(): Boolean? = props[0x80]?.u8()?.let { it == 0x30 }
}

fun isEchonetEntity(entityId: String) = entityId.contains(".el_")

/**
 * ECHONET Lite で家の中の機器と直接やり取りする（Home Assistant を通さない）。
 * - 家の Wi-Fi の中だけで動く（マルチキャスト 224.0.23.0 / UDP 3610）
 * - 認証の仕組みが無い規格なので、操作できる機器の種類を限っている（電気錠などは操作しない）
 */
class EchonetClient(private val context: Context, private val log: (String) -> Unit) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var socket: MulticastSocket? = null
    private var lock: WifiManager.MulticastLock? = null
    private var recvJob: Job? = null
    private val tid = AtomicInteger(1)
    private val waiting = ConcurrentHashMap<Int, CompletableDeferred<ElFrame>>()
    /** 機器探しの返事を集める */
    @Volatile private var discoverySink: ((String, ElFrame) -> Unit)? = null

    // ---- 診断用 ----
    @Volatile private var localIp: String? = null
    @Volatile private var ifName: String? = null
    @Volatile private var mcastJoined = false
    private val rxFrom = ConcurrentHashMap<String, MutableSet<String>>()   // 返事をくれた IP → 送り主の種類
    private val makers = ConcurrentHashMap<String, String>()               // IP → メーカー名
    @Volatile private var rxCount = 0
    private val sendErrors = ConcurrentHashMap<String, String>()          // 送れなかった宛先 → 理由
    val diagnostics = MutableStateFlow<String?>(null)

    val devices = MutableStateFlow<Map<String, ElDevice>>(emptyMap())
    val status = MutableStateFlow<String?>(null)

    val running: Boolean get() = socket != null

    fun start() {
        if (socket != null) return
        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            lock = wifi.createMulticastLock("homepanel-echonet").apply { setReferenceCounted(false); acquire() }
            val s = try {
                // 機器は返事を 3610 番に送ってくることが多いので、できれば 3610 番で待つ
                MulticastSocket(null as java.net.SocketAddress?).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(EL_PORT))
                }
            } catch (e: Exception) {
                log("ECHONET：3610番を使えないため別の番号で待ちます（${e.message}）")
                MulticastSocket(0)
            }
            // Android では、どの通信経路（Wi-Fi など）で呼びかけを送るかを指定しないと届かないことがある
            val (nif, ip) = findLanInterface()
            localIp = ip
            ifName = nif?.name
            if (nif != null) {
                runCatching { s.networkInterface = nif }
                    .onFailure { log("ECHONET：送信に使う経路を設定できません（${it.message}）") }
            }
            mcastJoined = runCatching {
                if (nif != null) s.joinGroup(InetSocketAddress(InetAddress.getByName(EL_MULTICAST), EL_PORT), nif)
                else s.joinGroup(InetAddress.getByName(EL_MULTICAST))
            }.onFailure { log("ECHONET：マルチキャストに参加できません（${it.message}）") }.isSuccess
            s.soTimeout = 1000
            s.timeToLive = 1
            runCatching { s.broadcast = true }
            socket = s
            recvJob = scope.launch { receiveLoop(s) }
            log("ECHONET：通信を開始（${ifName ?: "経路不明"} / ${localIp ?: "IP不明"} / 待ち受け番号 ${s.localPort}）")
        } catch (e: Exception) {
            status.value = "ECHONET Lite の通信を開始できませんでした：${e.message}"
            stop()
        }
    }

    fun stop() {
        recvJob?.cancel()
        recvJob = null
        runCatching { socket?.close() }
        socket = null
        runCatching { lock?.release() }
        lock = null
    }

    fun close() {
        stop()
        scope.cancel()
    }

    private suspend fun receiveLoop(s: MulticastSocket) {
        val buf = ByteArray(1500)
        while (scope.isActive && !s.isClosed) {
            val p = DatagramPacket(buf, buf.size)
            try {
                s.receive(p)
            } catch (e: SocketTimeoutException) {
                continue
            } catch (e: Exception) {
                if (!s.isClosed) log("ECHONET：受信エラー ${e.message}")
                break
            }
            val ip = p.address?.hostAddress ?: continue
            // 家の中のアドレスからの電文だけ受け付ける
            if (!NetworkGuard.isLocal(p.address)) continue
            val f = ElFrame.decode(p.data, p.length) ?: continue
            if (ip == localIp) continue // 自分が送ったもの
            rxCount++
            rxFrom.getOrPut(ip) { java.util.Collections.synchronizedSet(HashSet()) }.add(elClassOf(f.seoj.classCode).name)
            if (f.seoj == EOJ_CONTROLLER) continue // 他のコントローラー（HEMS など）
            discoverySink?.invoke(ip, f)
            when (f.esv) {
                Esv.GET_RES, Esv.SET_RES, Esv.GET_SNA, Esv.SETC_SNA -> waiting.remove(f.tid)?.complete(f)
                Esv.INF, Esv.INFC -> {
                    applyProps(ip, f.seoj, f.props)
                    if (f.esv == Esv.INFC) send(ip, ElFrame(f.tid, EOJ_CONTROLLER, f.seoj, 0x7A, f.props.map { Prop(it.epc) }))
                }
            }
        }
    }

    private fun send(ip: String, frame: ElFrame) {
        val s = socket ?: return
        val data = frame.encode()
        runCatching { s.send(DatagramPacket(data, data.size, InetAddress.getByName(ip), EL_PORT)) }
            .onFailure {
                sendErrors[ip] = it.message ?: it.javaClass.simpleName
                log("ECHONET：送信できません（$ip）${it.message}")
            }
    }

    /** 1台に頼んで返事を待つ。返事が無ければ null */
    private suspend fun request(ip: String, deoj: Eoj, esv: Int, props: List<Prop>, timeoutMs: Long = 2500): ElFrame? {
        if (socket == null) return null
        val id = tid.getAndIncrement() and 0xFFFF
        val d = CompletableDeferred<ElFrame>()
        waiting[id] = d
        // 送信は画面の処理とは別の所で行う（画面の処理中に通信すると Android に止められる）
        withContext(Dispatchers.IO) { send(ip, ElFrame(id, EOJ_CONTROLLER, deoj, esv, props)) }
        val r = withTimeoutOrNull(timeoutMs) { d.await() }
        waiting.remove(id)
        return r
    }

    private fun applyProps(ip: String, eoj: Eoj, props: List<Prop>) {
        val key = ip + "/" + eoj.hex()
        val cur = devices.value[key] ?: return
        val merged = cur.props.toMutableMap()
        props.filter { it.edt.isNotEmpty() }.forEach { merged[it.epc] = it.edt }
        devices.value = devices.value + (key to cur.copy(props = merged, online = true, updatedAt = System.currentTimeMillis()))
    }

    // ---------------- 機器を探す ----------------

    /**
     * 家の中の機器を探す。マルチキャストで呼びかけ、返事をくれた機器の中身を調べる。
     * extraIps：手動で登録した IP（マルチキャストが届かないルーター向け）
     */
    suspend fun discover(extraIps: List<String>, names: Map<String, String>, sweep: Boolean = false): Int = withContext(Dispatchers.IO) {
        if (socket == null) start()
        if (socket == null) {
            diagnostics.value = "ECHONET Lite の通信を開始できませんでした。\n${status.value ?: ""}\n" +
                "タブレットが家の Wi-Fi につながっているか確認してください。"
            return@withContext 0
        }
        status.value = "機器を探しています…"
        diagnostics.value = null
        sendErrors.clear()
        val found = ConcurrentHashMap<String, MutableSet<Eoj>>()
        discoverySink = { ip, f ->
            if (f.seoj.classCode == 0x0EF0) {
                f.props.firstOrNull { it.epc == 0xD6 || it.epc == 0xD5 }?.let { p ->
                    found.getOrPut(ip) { java.util.Collections.synchronizedSet(HashSet()) }.addAll(parseInstanceList(p.edt))
                }
            }
        }
        rxCount = 0
        rxFrom.clear()
        val sent = mutableListOf<String>()
        try {
            val get = ElFrame(tid.getAndIncrement() and 0xFFFF, EOJ_CONTROLLER, EOJ_NODE_PROFILE, Esv.GET, listOf(Prop(0xD6)))
            val bcast = broadcastAddress()
            repeat(2) {
                // 1. マルチキャスト（ECHONET Lite の決まった呼びかけ方）
                send(EL_MULTICAST, get)
                // 2. ブロードキャスト（マルチキャストを通さないルーター向け）
                bcast?.let { send(it, get) }
                // 3. 手動で登録した IP
                extraIps.forEach { ip -> send(ip, get) }
                delay(1500)
            }
            sent += listOfNotNull("マルチキャスト", bcast?.let { "ブロードキャスト（$it）" })
            if (extraIps.isNotEmpty()) sent += "登録IP ${extraIps.size}件"
            // 4. 同じネットワークのすべてのアドレスに1台ずつ問い合わせ（ルーターの設定に左右されない）
            if (sweep) {
                val prefix = localIp?.substringBeforeLast('.')
                if (prefix != null) {
                    status.value = "すべてのアドレス（$prefix.1〜254）に問い合わせています…"
                    for (n in 1..254) {
                        val ip = "$prefix.$n"
                        if (ip != localIp) send(ip, get)
                        if (n % 16 == 0) delay(40)
                    }
                    sent += "全アドレス（$prefix.1〜254）"
                    delay(2500)
                }
            }
            delay(1000)
        } finally {
            discoverySink = null
        }
        // 返事をくれた機器のメーカーを調べる（ノードプロファイルの 0x8A）
        for (ip in found.keys) {
            if (makers.containsKey(ip)) continue
            request(ip, EOJ_NODE_PROFILE, Esv.GET, listOf(Prop(0x8A)), 1500)?.props?.firstOrNull { it.epc == 0x8A }?.edt?.let {
                makers[ip] = makerName(it)
            }
        }
        writeDiagnostics(sent, found)
        var count = 0
        val next = devices.value.toMutableMap()
        for ((ip, eojs) in found) {
            for (eoj in eojs.toList()) {
                if (eoj.classCode == 0x0EF0 || eoj.classCode == 0x05FF) continue
                val key = ip + "/" + eoj.hex()
                val base = next[key] ?: ElDevice(ip, eoj, names[key] ?: defaultName(eoj))
                val maps = request(ip, eoj, Esv.GET, listOf(Prop(0x9E), Prop(0x9F), Prop(0x81)))
                val setMap = maps?.props?.firstOrNull { it.epc == 0x9E }?.edt?.let(::parsePropertyMap) ?: base.setMap
                val getMap = maps?.props?.firstOrNull { it.epc == 0x9F }?.edt?.let(::parsePropertyMap) ?: base.getMap
                val loc = maps?.props?.firstOrNull { it.epc == 0x81 }?.edt?.u8()
                val name = names[key] ?: base.name.takeIf { next.containsKey(key) }
                    ?: (elLocationName(loc)?.let { "$it の${elClassOf(eoj.classCode).name}" } ?: defaultName(eoj))
                next[key] = base.copy(name = name, setMap = setMap, getMap = getMap, online = true)
                count++
            }
        }
        devices.value = next
        status.value = if (found.isEmpty()) "機器が見つかりませんでした（ルーターがマルチキャストを通さない場合は、IPアドレスで追加してください）"
        else "${count}個の機能を見つけました（${found.size}台）"
        log("ECHONET：機器探し 完了 ${found.keys}")
        refreshAll()
        count
    }

    /**
     * 1台だけをくわしく調べる（IP アドレスが分かっている機器が見つからないとき用）。
     * 結果を診断の欄に書く。
     */
    suspend fun probe(ip: String): String = withContext(Dispatchers.IO) {
        if (socket == null) start()
        val lines = mutableListOf("【$ip を調べた結果】")
        if (socket == null) {
            lines += "ECHONET Lite の通信を開始できませんでした（${status.value ?: ""}）"
            return@withContext lines.joinToString("\n").also { diagnostics.value = it }
        }
        lines += "タブレット：${localIp ?: "IP不明"}（${ifName ?: "経路不明"}）・待ち受け番号 ${socket?.localPort}"
        sendErrors.remove(ip)
        // 1. ノードプロファイル（機器の中身の一覧）
        val node = request(ip, EOJ_NODE_PROFILE, Esv.GET, listOf(Prop(0xD6)), 3000)
        sendErrors[ip]?.let { lines += "✕ 送信できません：$it" }
        val list = node?.props?.firstOrNull { it.epc == 0xD6 }?.edt?.let(::parseInstanceList).orEmpty()
        if (node != null) {
            lines += "✓ 返事あり。中の機能：" + list.joinToString("・") { elClassOf(it.classCode).name + "(" + it.hex() + ")" }.ifBlank { "なし" }
            request(ip, EOJ_NODE_PROFILE, Esv.GET, listOf(Prop(0x8A)), 2000)?.props?.firstOrNull { it.epc == 0x8A }?.edt?.let {
                makers[ip] = makerName(it)
                lines += "メーカー：${makers[ip]}"
            }
        } else {
            lines += "✕ 機器の一覧の問い合わせに返事がありません"
        }
        // 2. エアコンとして直接問い合わせ（一覧に答えない機器もあるため）
        val ac = list.firstOrNull { it.classCode == 0x0130 } ?: Eoj(0x01, 0x30, 0x01)
        val st = request(ip, ac, Esv.GET, listOf(Prop(0x80), Prop(0xB0), Prop(0xB3)), 3000)
        if (st != null) {
            val on = st.props.firstOrNull { it.epc == 0x80 }?.edt?.firstOrNull()?.toInt()?.and(0xFF)
            val temp = st.props.firstOrNull { it.epc == 0xB3 }?.edt?.firstOrNull()?.toInt()?.and(0xFF)
            lines += "✓ エアコン（${ac.hex()}）が返事：" + (if (on == 0x30) "運転中" else if (on == 0x31) "停止中" else "状態不明") +
                (temp?.takeIf { it in 1..50 }?.let { "・設定 ${it}℃" } ?: "")
        } else {
            lines += "✕ エアコンとしての問い合わせにも返事がありません"
        }
        if (node == null && st == null) {
            lines += "→ この IP からは ECHONET Lite の返事がありません。考えられる原因："
            lines += "　・機器の ECHONET Lite（HEMS 連携）がオフ。Panasonic のエアコンは、エオリアアプリなどで「HEMS 接続」「ECHONET Lite」を有効にする必要がある機種があります"
            lines += "　・別売りの無線 LAN アダプターや HEMS アダプターが必要な機種"
            lines += "　・IP が別の機器（ルーターで割り当てを確認）"
            lines += "　・ルーターの「端末どうしの通信を分ける機能」（プライバシーセパレーター／AP アイソレーション）"
        } else {
            lines += "→ 返事があったので、もう一度「機器を探す」を押すと一覧に追加されます"
        }
        lines.joinToString("\n").also { diagnostics.value = it }
    }

    private fun writeDiagnostics(sent: List<String>, found: Map<String, Set<Eoj>>) {
        val lines = mutableListOf<String>()
        lines += "タブレット：${localIp ?: "IPアドレス不明（Wi-Fi につながっていない可能性）"}（${ifName ?: "経路不明"}）"
        lines += "待ち受け番号：${socket?.localPort ?: "-"}" + if (socket?.localPort == EL_PORT) "（3610 正常）" else "（3610 を使えず。機器の返事が届かないことがあります）"
        lines += "マルチキャスト参加：" + if (mcastJoined) "OK" else "できませんでした"
        lines += "呼びかけ：" + sent.joinToString("・")
        lines += "受け取った ECHONET Lite の電文：${rxCount} 件"
        if (sendErrors.isNotEmpty()) {
            lines += "送信できなかった宛先：" + sendErrors.entries.take(5).joinToString("・") { "${it.key}（${it.value}）" } +
                if (sendErrors.size > 5) " ほか${sendErrors.size - 5}件" else ""
        }
        if (rxFrom.isEmpty()) {
            lines += "→ どの機器からも返事がありません。"
            lines += "　・機器側：ECHONET Lite（HEMS 連携）の設定がオフ、または機器が別の Wi-Fi（ゲスト用・別の周波数帯で分離）につながっている"
            lines += "　・ルーター側：IGMP スヌーピング、端末どうしの通信を分ける機能（プライバシーセパレーター／ネットワーク分離／AP アイソレーション）"
        } else {
            rxFrom.toSortedMap(compareBy { it.substringAfterLast('.').toIntOrNull() ?: 0 }).forEach { (ip, kinds) ->
                val n = found[ip]?.count { it.classCode != 0x0EF0 } ?: 0
                lines += "・$ip　${makers[ip] ?: "メーカー不明"}　${kinds.joinToString("・")}" + if (n > 0) "（機能 $n 個）" else ""
            }
        }
        diagnostics.value = lines.joinToString("\n")
    }

    /** 家の LAN につながっている経路（Wi-Fi / 有線）とそのIPアドレス */
    private fun findLanInterface(): Pair<java.net.NetworkInterface?, String?> = runCatching {
        val all = java.net.NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual && it.supportsMulticast() }
        val pick = all.sortedBy { if (it.name.startsWith("wlan")) 0 else if (it.name.startsWith("eth")) 1 else 2 }
            .firstOrNull { nif -> nif.inetAddresses.toList().any { it is java.net.Inet4Address && it.isSiteLocalAddress } }
        pick to pick?.inetAddresses?.toList()?.firstOrNull { it is java.net.Inet4Address && it.isSiteLocalAddress }?.hostAddress
    }.getOrDefault(null to null)

    /** 同じネットワークのブロードキャストアドレス（例：192.168.1.255） */
    private fun broadcastAddress(): String? = runCatching {
        val nif = socket?.networkInterface ?: return null
        nif.interfaceAddresses.firstOrNull { it.address is java.net.Inet4Address && it.broadcast != null }?.broadcast?.hostAddress
    }.getOrNull()

    private fun defaultName(eoj: Eoj): String =
        elClassOf(eoj.classCode).name + if (eoj.instance > 1) " ${eoj.instance}" else ""

    /** 保存しておいた機器の一覧を戻す（起動時） */
    fun restore(list: List<ElDevice>) {
        devices.value = list.associateBy { it.key }
    }

    fun rename(key: String, name: String) {
        val d = devices.value[key] ?: return
        devices.value = devices.value + (key to d.copy(name = name))
    }

    fun remove(key: String) {
        devices.value = devices.value - key
    }

    // ---------------- 状態の読み取り ----------------

    private fun wantedProps(d: ElDevice): List<Int> {
        val base = when (d.cls.kind) {
            ElKind.AIRCON -> listOf(0x80, 0xB0, 0xB3, 0xBB, 0xBA, 0xA0)
            ElKind.LIGHT -> listOf(0x80, 0xB0)
            ElKind.COVER -> listOf(0x80, 0xE0, 0xEA, 0xE1)
            ElKind.ONOFF -> listOf(0x80)
            ElKind.METER -> listOf(0x80, 0xE7, 0xE0, 0xE1, 0xD3, 0xE3)
            ElKind.SOLAR -> listOf(0x80, 0xE0, 0xE1)
            ElKind.BATTERY -> listOf(0x80, 0xE4, 0xDA, 0xD3)
            ElKind.READONLY -> listOf(0x80)
        }
        // 機器が「読める」と言っているものだけ（一覧が取れなかったときは全部試す）
        return if (d.getMap.isEmpty()) base else base.filter { it in d.getMap }
    }

    suspend fun refresh(key: String) {
        val d = devices.value[key] ?: return
        val want = wantedProps(d)
        if (want.isEmpty()) return
        val r = request(d.ip, d.eoj, Esv.GET, want.map { Prop(it) })
        if (r == null) {
            devices.value[key]?.let { devices.value = devices.value + (key to it.copy(online = false)) }
            return
        }
        applyProps(d.ip, d.eoj, r.props)
    }

    suspend fun refreshAll() = withContext(Dispatchers.IO) {
        devices.value.keys.toList().forEach { runCatching { refresh(it) } }
    }

    // ---------------- 操作 ----------------

    /** 設定して、結果を読み直す。うまくいかなければ理由を返す */
    private suspend fun set(d: ElDevice, props: List<Prop>): String? {
        val allowed = if (d.setMap.isEmpty()) props else props.filter { it.epc in d.setMap }
        if (allowed.isEmpty()) return "この機器はこの操作に対応していません"
        val r = request(d.ip, d.eoj, Esv.SETC, allowed) ?: return "機器から返事がありません（${d.ip}）"
        if (r.esv == Esv.SETC_SNA) return "機器が操作を受け付けませんでした"
        refresh(d.key)
        return null
    }

    /**
     * 画面の操作（Home Assistant と同じ書き方）を ECHONET Lite の設定に置き換える。
     * 戻り値：うまくいかなかったときの理由（成功なら null）
     */
    suspend fun callService(entityId: String, service: String, data: JSONObject): String? {
        val d = devices.value.values.firstOrNull { it.entityId == entityId } ?: return "機器が見つかりません"
        val on = Prop(0x80, bytesOf(0x30))
        val off = Prop(0x80, bytesOf(0x31))
        return when (d.cls.kind) {
            ElKind.AIRCON -> when (service) {
                "turn_on" -> set(d, listOf(on))
                "turn_off" -> set(d, listOf(off))
                "set_hvac_mode" -> {
                    val m = data.optString("hvac_mode")
                    if (m == "off") set(d, listOf(off))
                    else AC_MODE_FROM_HA[m]?.let { set(d, listOf(on, Prop(0xB0, bytesOf(it)))) } ?: "この運転モードには対応していません"
                }
                "set_temperature" -> {
                    val t = data.optDouble("temperature")
                    if (t.isNaN()) "温度が指定されていません" else set(d, listOf(Prop(0xB3, bytesOf(Math.round(t).toInt().coerceIn(0, 50)))))
                }
                "set_fan_mode" -> AC_FAN_FROM_HA[data.optString("fan_mode")]?.let { set(d, listOf(Prop(0xA0, bytesOf(it)))) }
                    ?: "この風量には対応していません"
                else -> "この操作には対応していません"
            }
            ElKind.LIGHT -> when (service) {
                "turn_on" -> {
                    val list = mutableListOf(on)
                    if (data.has("brightness_pct")) list.add(Prop(0xB0, bytesOf(data.optInt("brightness_pct").coerceIn(0, 100))))
                    set(d, list)
                }
                "turn_off" -> set(d, listOf(off))
                "toggle" -> set(d, listOf(if (d.on() == true) off else on))
                else -> "この操作には対応していません"
            }
            ElKind.COVER -> when (service) {
                "open_cover" -> set(d, listOf(Prop(0xE0, bytesOf(0x41))))
                "close_cover" -> set(d, listOf(Prop(0xE0, bytesOf(0x42))))
                "stop_cover" -> set(d, listOf(Prop(0xE0, bytesOf(0x43))))
                "set_cover_position" -> set(d, listOf(Prop(0xE1, bytesOf(data.optInt("position").coerceIn(0, 100)))))
                else -> "この操作には対応していません"
            }
            ElKind.ONOFF -> when (service) {
                "turn_on" -> set(d, listOf(on))
                "turn_off" -> set(d, listOf(off))
                "toggle" -> set(d, listOf(if (d.on() == true) off else on))
                else -> "この操作には対応していません"
            }
            else -> "この機器は表示だけです（安全のため操作しません）"
        }
    }

    // ---------------- 保存用 ----------------

    fun toJson(): String = JSONArray().apply {
        devices.value.values.forEach { d ->
            put(JSONObject().put("ip", d.ip).put("eoj", d.eoj.hex()).put("name", d.name)
                .put("set", JSONArray(d.setMap.toList())).put("get", JSONArray(d.getMap.toList())))
        }
    }.toString()

    companion object {
        fun fromJson(s: String?): List<ElDevice> = try {
            val a = JSONArray(s ?: "[]")
            (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull { j ->
                val eoj = Eoj.parse(j.optString("eoj")) ?: return@mapNotNull null
                fun ints(k: String) = j.optJSONArray(k)?.let { arr -> (0 until arr.length()).map { arr.optInt(it) }.toSet() } ?: emptySet()
                ElDevice(j.optString("ip"), eoj, j.optString("name"), setMap = ints("set"), getMap = ints("get"), online = false)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}

// ---------------- 画面用の変換（Home Assistant の機器と同じ形にする） ----------------

/** ECHONET の機器を、画面のタイルで使う Entity の形にする */
fun ElDevice.toEntity(): Entity {
    val a = JSONObject().put("friendly_name", name).put("echonet", true).put("ip", ip).put("eoj", eoj.hex())
        .put("device_class_name", cls.name)
    val on = on()
    var state = when {
        !online && props.isEmpty() -> "unavailable"
        on == null -> "unknown"
        on -> "on"
        else -> "off"
    }
    when (cls.kind) {
        ElKind.AIRCON -> {
            val mode = props[0xB0]?.u8()?.let { AC_MODE_TO_HA[it] } ?: "auto"
            if (on == true) state = mode
            val modes = JSONArray().put("off")
            listOf("cool", "heat", "dry", "fan_only", "auto").forEach { modes.put(it) }
            a.put("hvac_modes", modes)
                .put("min_temp", 16).put("max_temp", 32).put("target_temp_step", 1)
                .put("fan_modes", JSONArray(listOf("auto", "1", "2", "3", "4", "5", "6", "7", "8")))
            props[0xB3]?.u8()?.takeIf { it in 0..50 }?.let { a.put("temperature", it) }
            props[0xBB]?.s8()?.takeIf { it in -50..60 }?.let { a.put("current_temperature", it) }
            props[0xBA]?.u8()?.takeIf { it in 0..100 }?.let { a.put("current_humidity", it) }
            props[0xA0]?.u8()?.let { AC_FAN_TO_HA[it] }?.let { a.put("fan_mode", it) }
        }
        ElKind.LIGHT -> {
            val dimmable = setMap.isEmpty() || 0xB0 in setMap
            a.put("supported_color_modes", JSONArray().put(if (dimmable) "brightness" else "onoff"))
            if (on == true) props[0xB0]?.u8()?.takeIf { it in 0..100 }?.let { a.put("brightness", (it * 255 / 100)) }
        }
        ElKind.COVER -> {
            state = when (props[0xEA]?.u8()) {
                0x41 -> "open"; 0x42 -> "closed"; 0x43 -> "opening"; 0x44 -> "closing"
                else -> when (props[0xE0]?.u8()) { 0x41 -> "open"; 0x42 -> "closed"; else -> if (state == "unavailable") state else "open" }
            }
            a.put("supported_features", 1 or 2 or 8)
            props[0xE1]?.u8()?.takeIf { it in 0..100 }?.let { a.put("current_position", it) }
        }
        else -> {}
    }
    return Entity(entityId, state, a, lastChanged = "", lastUpdated = if (updatedAt > 0)
        java.time.Instant.ofEpochMilli(updatedAt).atOffset(java.time.ZoneOffset.UTC).toString() else "")
}

/** スマートメーター・太陽光・蓄電池の値（いまの電力など） */
data class ElLive(val buyW: Long?, val solarW: Long?, val batteryPct: Int?)

fun elLive(devices: Collection<ElDevice>): ElLive {
    val meter = devices.firstOrNull { it.cls.kind == ElKind.METER && it.props[0xE7] != null }
    val solar = devices.filter { it.cls.kind == ElKind.SOLAR }.mapNotNull { it.props[0xE0]?.u16()?.toLong() }
    val bat = devices.firstOrNull { it.cls.kind == ElKind.BATTERY }?.props?.get(0xE4)?.u8()
    return ElLive(
        buyW = meter?.props?.get(0xE7)?.s32()?.takeIf { it != 0x7FFFFFFEL && it != -0x7FFFFFFFL },
        solarW = solar.takeIf { it.isNotEmpty() }?.sum(),
        batteryPct = bat?.takeIf { it in 0..100 },
    )
}

/** メーカーコード（ノードプロファイル 0x8A、3バイト）→ 名前 */
fun makerName(edt: ByteArray): String {
    if (edt.size < 3) return "メーカー不明"
    val code = ((edt[0].toInt() and 0xFF) shl 16) or ((edt[1].toInt() and 0xFF) shl 8) or (edt[2].toInt() and 0xFF)
    return when (code) {
        0x00000B -> "Panasonic"
        0x000006 -> "三菱電機"
        0x000005 -> "シャープ"
        0x000001 -> "日立"
        0x000008 -> "ダイキン"
        0x000016 -> "東芝"
        else -> String.format("メーカーコード %06X", code)
    }
}
