package jp.home.panel

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

sealed interface Screen {
    data object Loading : Screen
    data object Login : Screen
    data class Blocked(val reason: String) : Screen
    data object Home : Screen
}

enum class Conn { CONNECTING, CONNECTED, RETRYING }

data class Pipeline(val id: String, val name: String, val hasWakeWord: Boolean, val agent: String)

/** 画面に出す機器の種類（並び順もこの順） */
val SUPPORTED_DOMAINS = listOf(
    "climate", "light", "fan", "cover", "switch", "input_boolean", "scene", "script",
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val store = TokenStore(app)
    private val prefs = UiPrefs(app)
    private val http = OkHttpClient.Builder()
        .dns(RestrictedDns { store.remoteUrl?.let { Uri.parse(it).host } })
        .connectTimeout(8, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()
    private val auth = AuthApi(http)

    // ---- 画面の状態 ----
    val screen = MutableStateFlow<Screen>(Screen.Loading)
    val conn = MutableStateFlow(Conn.CONNECTING)
    val route = MutableStateFlow<Route?>(null)
    val message = MutableStateFlow<String?>(null)

    val allEntities = MutableStateFlow<List<Entity>>(emptyList())
    val selection = MutableStateFlow(prefs.selected)
    val entities: StateFlow<List<Entity>> = combine(allEntities, selection) { all, sel ->
        if (sel == null) all else all.filter { it.entityId in sel }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val weatherEntities = MutableStateFlow<List<Entity>>(emptyList())
    val weatherEntityId = MutableStateFlow(prefs.weatherEntityId)
    val weather = MutableStateFlow<Entity?>(null)
    val forecast = MutableStateFlow<List<JSONObject>>(emptyList())

    val pipelines = MutableStateFlow<List<Pipeline>>(emptyList())
    val preferredPipeline = MutableStateFlow<String?>(null)
    val pipelineId = MutableStateFlow(prefs.pipelineId)

    val voice = MutableStateFlow<VoiceState>(VoiceState.Idle)
    val lastDone = MutableStateFlow<VoiceState.Done?>(null)
    val wakeEnabled = MutableStateFlow(prefs.wakeEnabled)
    val remoteUrl = MutableStateFlow(store.remoteUrl.orEmpty())

    val loginBusy = MutableStateFlow(false)
    val loginError = MutableStateFlow<String?>(null)
    val needOtp = MutableStateFlow(false)
    val savedUrl: String get() = store.baseUrl.orEmpty()

    // ---- 内部 ----
    private var client: HaClient? = null
    private var assistant: VoiceAssistant? = null
    private var connectJob: Job? = null
    private var voiceJob: Job? = null
    private var wakeJob: Job? = null
    private var manualBusy = false
    private var foreground = false
    private var forecastSub: Pair<HaClient, Int>? = null
    private var forecastFor: String? = null
    private val tokenMutex = Mutex()

    init {
        start()
    }

    fun onResume() {
        if (connectJob?.isActive != true && screen.value != Screen.Login) start()
    }

    fun setForeground(v: Boolean) {
        foreground = v
        refreshWakeLoop()
    }

    // ================= 接続 =================

    /** 接続ループ。切れたら自動で再接続し、宅内⇔外出先を自動で切り替える */
    fun start() {
        connectJob?.cancel()
        connectJob = viewModelScope.launch {
            val local = store.baseUrl
            if (local == null || store.refreshToken == null) {
                screen.value = Screen.Login
                return@launch
            }
            var backoff = 2_000L
            while (isActive) {
                val r = try {
                    NetworkGuard.chooseRoute(getApplication(), local, store.remoteUrl)
                } catch (e: BlockedException) {
                    screen.value = Screen.Blocked(e.message.orEmpty())
                    return@launch
                }
                route.value = r
                screen.value = Screen.Home
                conn.value = Conn.CONNECTING
                val c = HaClient(r.base, http)
                try {
                    c.connect(validAccessToken(r.base, local))
                    client = c
                    assistant = VoiceAssistant(r.base)
                    conn.value = Conn.CONNECTED
                    message.value = null
                    backoff = 2_000L

                    val collector = launch { c.states.collect { onStates(c, it) } }
                    launch { loadPipelines(c) }
                    // 外出先接続中は、家に戻ったらローカル接続に切り替える
                    val upgrader = if (r.remote) launch {
                        while (isActive) {
                            delay(60_000)
                            if (NetworkGuard.localAvailable(getApplication(), local)) {
                                c.close()
                                break
                            }
                        }
                    } else null
                    refreshWakeLoop()

                    c.awaitClosed()
                    collector.cancel()
                    upgrader?.cancel()
                } catch (e: RefreshRevokedException) {
                    c.close()
                    logoutInternal(e.message)
                    return@launch
                } catch (e: AuthInvalidException) {
                    store.accessToken = null // 次の試行でトークンを更新
                } catch (e: TimeoutCancellationException) {
                    c.close()
                    message.value = "サーバーの応答がありません"
                } catch (e: CancellationException) {
                    c.close()
                    throw e
                } catch (e: Exception) {
                    c.close()
                    message.value = "接続エラー: ${e.message ?: e.javaClass.simpleName}"
                }
                client = null
                forecastSub = null
                forecastFor = null
                refreshWakeLoop()
                conn.value = Conn.RETRYING
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(30_000L)
            }
        }
    }

    private suspend fun validAccessToken(base: String, loginBase: String): String = tokenMutex.withLock {
        val cur = store.accessToken
        if (cur != null && store.accessExpiresAt - 60_000 > System.currentTimeMillis()) return@withLock cur
        val rt = store.refreshToken ?: throw RefreshRevokedException()
        val (tok, exp) = auth.refresh(base, loginBase, rt)
        store.accessToken = tok
        store.accessExpiresAt = exp
        tok
    }

    private fun onStates(c: HaClient, m: Map<String, Entity>) {
        allEntities.value = m.values
            .filter { it.domain in SUPPORTED_DOMAINS }
            .sortedWith(compareBy({ SUPPORTED_DOMAINS.indexOf(it.domain) }, { it.name }))

        val ws = m.values.filter { it.domain == "weather" }.sortedBy { it.name }
        weatherEntities.value = ws
        val w = ws.firstOrNull { it.entityId == weatherEntityId.value } ?: ws.firstOrNull()
        weather.value = w
        if (w != null && w.entityId != forecastFor) {
            forecastFor = w.entityId
            viewModelScope.launch { subscribeForecast(c, w.entityId) }
        }
    }

    private suspend fun subscribeForecast(c: HaClient, entityId: String) {
        forecastSub?.let { (oldClient, id) ->
            oldClient.removeHandler(id)
            runCatching {
                oldClient.request(JSONObject().put("type", "unsubscribe_events").put("subscription", id))
            }
        }
        forecastSub = null
        forecast.value = emptyList()
        try {
            val id = c.subscribe(
                JSONObject()
                    .put("type", "weather/subscribe_forecast")
                    .put("forecast_type", "daily")
                    .put("entity_id", entityId)
            ) { ev ->
                val arr = ev.optJSONArray("forecast") ?: JSONArray()
                forecast.value = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
            }
            forecastSub = c to id
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 日別予報に対応していない天気連携もあるので、その場合は現在の天気だけ表示
        }
    }

    private suspend fun loadPipelines(c: HaClient) {
        try {
            val r = c.request(JSONObject().put("type", "assist_pipeline/pipeline/list")).optJSONObject("result")
                ?: return
            val arr = r.optJSONArray("pipelines") ?: JSONArray()
            pipelines.value = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map {
                Pipeline(
                    id = it.optString("id"),
                    name = it.optString("name"),
                    hasWakeWord = !it.isNull("wake_word_entity") && it.optString("wake_word_entity").isNotBlank(),
                    agent = it.optString("conversation_engine"),
                )
            }
            preferredPipeline.value = r.optString("preferred_pipeline").takeIf { it.isNotBlank() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 一覧を取れなくても既定のアシスタントで動く
        }
    }

    // ================= ログイン =================

    fun login(urlInput: String, user: String, pass: String, otp: String) {
        viewModelScope.launch {
            loginBusy.value = true
            loginError.value = null
            try {
                val base = normalizeUrl(urlInput)
                NetworkGuard.checkLocal(getApplication(), base)?.let {
                    loginError.value = it
                    return@launch
                }
                val t = auth.login(base, user.trim(), pass, otp.trim().ifBlank { null })
                store.save(base, t)
                needOtp.value = false
                start()
            } catch (e: MfaRequiredException) {
                needOtp.value = true
                loginError.value = e.message
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                loginError.value = e.message ?: "ログインに失敗しました"
            } finally {
                loginBusy.value = false
            }
        }
    }

    fun logout() = logoutInternal(null)

    private fun logoutInternal(reason: String?) {
        connectJob?.cancel()
        voiceJob?.cancel()
        wakeJob?.cancel()
        client?.close()
        client = null
        store.clearTokens()
        allEntities.value = emptyList()
        voice.value = VoiceState.Idle
        loginError.value = reason
        screen.value = Screen.Login
    }

    // ================= 設定 =================

    /** 外出先用URLを保存。空なら削除（＝家の中専用に戻す） */
    fun saveRemoteUrl(input: String): String? {
        val s = input.trim().trimEnd('/')
        if (s.isEmpty()) {
            store.remoteUrl = null
            remoteUrl.value = ""
            return null
        }
        val url = if (s.startsWith("http://") || s.startsWith("https://")) s else "https://$s"
        if (!url.startsWith("https://")) return "外出先用URLは https:// で始まる必要があります"
        if (Uri.parse(url).host.isNullOrBlank()) return "URLが正しくありません"
        store.remoteUrl = url
        remoteUrl.value = url
        if (screen.value is Screen.Blocked) start()
        return null
    }

    fun setWakeEnabled(v: Boolean) {
        prefs.wakeEnabled = v
        wakeEnabled.value = v
        refreshWakeLoop()
    }

    fun setPipeline(id: String?) {
        prefs.pipelineId = id
        pipelineId.value = id
        // 待ち受け中なら新しいアシスタントでやり直す
        wakeJob?.cancel()
        wakeJob = null
        refreshWakeLoop()
    }

    fun setWeatherEntity(id: String) {
        prefs.weatherEntityId = id
        weatherEntityId.value = id
        client?.let { onStates(it, it.states.value) }
    }

    fun toggleSelection(entityId: String) {
        val cur = selection.value ?: allEntities.value.map { it.entityId }.toSet()
        val next = if (entityId in cur) cur - entityId else cur + entityId
        prefs.selected = next
        selection.value = next
    }

    fun selectAll() {
        val all = allEntities.value.map { it.entityId }.toSet()
        prefs.selected = all
        selection.value = all
    }

    fun selectNone() {
        prefs.selected = emptySet()
        selection.value = emptySet()
    }

    // ================= 機器操作 =================

    fun call(e: Entity, service: String, data: JSONObject = JSONObject(), domain: String = e.domain) {
        val c = client ?: return
        viewModelScope.launch {
            try {
                c.callService(domain, service, e.entityId, data)
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                message.value = "「${e.name}」を操作できませんでした: ${ex.message}"
            }
        }
    }

    /** タイルをタップしたときの基本動作 */
    fun primaryAction(e: Entity) {
        when (e.domain) {
            "scene", "script" -> call(e, "turn_on")
            "cover" -> call(e, if (e.state == "open" || e.state == "opening") "close_cover" else "open_cover")
            "climate" -> call(e, if (e.state == "off") "turn_on" else "turn_off")
            else -> call(e, "toggle")
        }
    }

    // ================= 音声 =================

    private val effectivePipeline: String?
        get() = pipelineId.value?.takeIf { id -> pipelines.value.isEmpty() || pipelines.value.any { it.id == id } }

    private fun onVoice(s: VoiceState) {
        voice.value = s
        if (s is VoiceState.Done) lastDone.value = s
    }

    private fun micGranted() = ContextCompat.checkSelfPermission(
        getApplication(), Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    /**
     * 呼びかけ待ち受けを動かすか止めるかを決める。
     * 外出先接続中は通信量が多くなるので止める（ボタンでの音声操作は使える）。
     */
    private fun refreshWakeLoop() {
        val shouldRun = foreground && wakeEnabled.value && micGranted() && client != null &&
            route.value?.remote == false && !manualBusy
        if (shouldRun) {
            if (wakeJob?.isActive != true) wakeJob = viewModelScope.launch { wakeLoop() }
        } else {
            wakeJob?.cancel()
            wakeJob = null
            if (voice.value == VoiceState.WaitingWake) voice.value = VoiceState.Idle
        }
    }

    private suspend fun wakeLoop() {
        while (true) {
            val c = client
            val a = assistant
            if (c == null || a == null) {
                delay(2_000)
                continue
            }
            try {
                a.run(c, wakeWord = true, pipeline = effectivePipeline, onState = ::onVoice)
                delay(300)
            } catch (e: TimeoutCancellationException) {
                delay(300)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                voice.value = VoiceState.Failed(e.message ?: "呼びかけの待ち受けに失敗しました")
                delay(5_000)
            }
        }
    }

    /** マイクボタン：聞き取り中なら終了、それ以外なら聞き取り開始 */
    fun onMicPressed() {
        if (voice.value == VoiceState.Listening) {
            assistant?.stopListening()
            return
        }
        if (manualBusy) return
        val c = client ?: return
        val a = assistant ?: return
        manualBusy = true
        voiceJob = viewModelScope.launch {
            try {
                wakeJob?.cancelAndJoin()
                wakeJob = null
                a.run(c, wakeWord = false, pipeline = effectivePipeline, onState = ::onVoice)
            } catch (e: TimeoutCancellationException) {
                voice.value = VoiceState.Failed("時間切れになりました")
            } catch (e: CancellationException) {
                voice.value = VoiceState.Idle
                throw e
            } catch (e: Exception) {
                voice.value = VoiceState.Failed(e.message ?: "音声操作に失敗しました")
            } finally {
                manualBusy = false
                refreshWakeLoop()
            }
        }
    }

    /** 文字で命令・質問（回答は読み上げ） */
    fun sendText(text: String) {
        if (text.isBlank() || manualBusy) return
        val c = client ?: return
        val a = assistant ?: return
        manualBusy = true
        voiceJob = viewModelScope.launch {
            try {
                wakeJob?.cancelAndJoin()
                wakeJob = null
                a.runText(c, text.trim(), effectivePipeline, ::onVoice)
            } catch (e: TimeoutCancellationException) {
                voice.value = VoiceState.Failed("時間切れになりました")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                voice.value = VoiceState.Failed(e.message ?: "失敗しました")
            } finally {
                manualBusy = false
                refreshWakeLoop()
            }
        }
    }

    override fun onCleared() {
        client?.close()
    }

    companion object {
        /** "192.168.1.10" → "http://192.168.1.10:8123" のように補完 */
        fun normalizeUrl(input: String): String {
            var s = input.trim().trimEnd('/')
            require(s.isNotEmpty()) { "URLを入力してください" }
            if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://$s"
            val uri = Uri.parse(s)
            require(!uri.host.isNullOrBlank()) { "URLが正しくありません" }
            if (uri.port == -1 && uri.scheme == "http") s = "${uri.scheme}://${uri.host}:8123"
            return s
        }
    }
}
