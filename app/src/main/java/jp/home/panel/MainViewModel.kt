package jp.home.panel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.util.concurrent.TimeUnit

sealed interface Screen {
    data object Loading : Screen
    data object Login : Screen
    data class Blocked(val reason: String) : Screen
    data object Home : Screen
}

enum class Conn { CONNECTING, CONNECTED, RETRYING }

/** 画面に出す機器の種類（並び順もこの順） */
val SUPPORTED_DOMAINS = listOf(
    "light", "switch", "fan", "cover", "climate", "input_boolean", "scene", "script",
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val store = TokenStore(app)
    private val http = OkHttpClient.Builder()
        .dns(LocalOnlyDns)
        .connectTimeout(5, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()
    private val auth = AuthApi(http)

    val screen = MutableStateFlow<Screen>(Screen.Loading)
    val conn = MutableStateFlow(Conn.CONNECTING)
    val message = MutableStateFlow<String?>(null)
    val entities = MutableStateFlow<List<Entity>>(emptyList())
    val voice = MutableStateFlow<VoiceState>(VoiceState.Idle)

    val loginBusy = MutableStateFlow(false)
    val loginError = MutableStateFlow<String?>(null)
    val needOtp = MutableStateFlow(false)
    val savedUrl: String get() = store.baseUrl.orEmpty()

    private var client: HaClient? = null
    private var assistant: VoiceAssistant? = null
    private var connectJob: Job? = null
    private var voiceJob: Job? = null
    private val tokenMutex = Mutex()

    init {
        start()
    }

    fun onResume() {
        if (connectJob?.isActive != true && screen.value != Screen.Login) start()
    }

    /** 接続ループ。切れたら自動で再接続し、家の外に出たら止まる */
    fun start() {
        connectJob?.cancel()
        connectJob = viewModelScope.launch {
            val base = store.baseUrl
            if (base == null || store.refreshToken == null) {
                screen.value = Screen.Login
                return@launch
            }
            var backoff = 2_000L
            while (isActive) {
                NetworkGuard.check(getApplication(), base)?.let {
                    screen.value = Screen.Blocked(it)
                    return@launch
                }
                screen.value = Screen.Home
                conn.value = Conn.CONNECTING
                val c = HaClient(base, http)
                try {
                    c.connect(validAccessToken(base))
                    client = c
                    assistant = VoiceAssistant(base)
                    conn.value = Conn.CONNECTED
                    message.value = null
                    backoff = 2_000L
                    val collector = launch {
                        c.states.collect { m ->
                            entities.value = m.values
                                .filter { it.domain in SUPPORTED_DOMAINS }
                                .sortedWith(compareBy({ SUPPORTED_DOMAINS.indexOf(it.domain) }, { it.name }))
                        }
                    }
                    c.awaitClosed()
                    collector.cancel()
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
                conn.value = Conn.RETRYING
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(30_000L)
            }
        }
    }

    private suspend fun validAccessToken(base: String): String = tokenMutex.withLock {
        val cur = store.accessToken
        if (cur != null && store.accessExpiresAt - 60_000 > System.currentTimeMillis()) return@withLock cur
        val rt = store.refreshToken ?: throw RefreshRevokedException()
        val (tok, exp) = auth.refresh(base, rt)
        store.accessToken = tok
        store.accessExpiresAt = exp
        tok
    }

    fun login(urlInput: String, user: String, pass: String, otp: String) {
        viewModelScope.launch {
            loginBusy.value = true
            loginError.value = null
            try {
                val base = normalizeUrl(urlInput)
                NetworkGuard.check(getApplication(), base)?.let {
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
        client?.close()
        client = null
        store.clearTokens()
        entities.value = emptyList()
        loginError.value = reason
        screen.value = Screen.Login
    }

    fun toggle(e: Entity) {
        val c = client ?: return
        val service = when (e.domain) {
            "light", "switch", "fan", "input_boolean" -> "toggle"
            "cover" -> if (e.state == "open" || e.state == "opening") "close_cover" else "open_cover"
            "scene", "script" -> "turn_on"
            "climate" -> if (e.state == "off") "turn_on" else "turn_off"
            else -> return
        }
        viewModelScope.launch {
            try {
                c.callService(e.domain, service, e.entityId)
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                message.value = "操作に失敗しました: ${ex.message}"
            }
        }
    }

    fun adjustClimate(e: Entity, delta: Double) {
        val c = client ?: return
        val target = e.attributes.optDouble("temperature")
        if (target.isNaN()) return
        viewModelScope.launch {
            try {
                c.callService("climate", "set_temperature", e.entityId, JSONObject().put("temperature", target + delta))
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                message.value = "温度を変更できませんでした: ${ex.message}"
            }
        }
    }

    /** マイクボタン：待機中なら開始、聞き取り中なら終了 */
    fun onMicPressed() {
        if (voice.value == VoiceState.Listening) {
            assistant?.stopListening()
            return
        }
        if (voiceJob?.isActive == true) return
        val c = client ?: return
        val a = assistant ?: return
        voiceJob = viewModelScope.launch {
            try {
                a.run(c) { voice.value = it }
            } catch (e: TimeoutCancellationException) {
                voice.value = VoiceState.Failed("時間切れになりました")
            } catch (e: CancellationException) {
                voice.value = VoiceState.Idle
                throw e
            } catch (e: Exception) {
                voice.value = VoiceState.Failed(e.message ?: "音声操作に失敗しました")
            }
        }
    }

    fun sendText(text: String) {
        val c = client ?: return
        if (text.isBlank() || voiceJob?.isActive == true) return
        voiceJob = viewModelScope.launch {
            voice.value = VoiceState.Processing(text)
            voice.value = try {
                VoiceState.Done(text, c.processText(text))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                VoiceState.Failed(e.message ?: "失敗しました")
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
