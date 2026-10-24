package jp.home.panel

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
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
import java.time.LocalDate
import java.time.ZoneId
import android.media.AudioManager

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

    // ---- 配色 ----
    val themeId = MutableStateFlow(prefs.themeId)

    // ---- 部屋 ----
    val haAreas = MutableStateFlow<List<Room>>(emptyList())
    val entityAreas = MutableStateFlow<Map<String, String>>(emptyMap())
    val customRooms = MutableStateFlow(prefs.customRooms)
    val roomOverrides = MutableStateFlow(prefs.roomOverrides)
    val groupRooms = MutableStateFlow(prefs.groupByRoom)
    val roomFilter = MutableStateFlow(prefs.roomFilter)
    val rooms: StateFlow<List<Room>> = combine(haAreas, customRooms) { a, c ->
        a + c.entries.sortedBy { it.key }.map { Room(it.key, it.value, false) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // ---- アカウント ----
    val userName = MutableStateFlow<String?>(null)
    val isAdmin = MutableStateFlow<Boolean?>(null)
    val adminUser = MutableStateFlow(store.adminUser)
    val adminBusy = MutableStateFlow(false)
    val adminError = MutableStateFlow<String?>(null)

    // ---- 自動化 ----
    val allStates = MutableStateFlow<Map<String, Entity>>(emptyMap())
    val automations = MutableStateFlow<List<Entity>>(emptyList())
    /** 設定を読み込んだルール（値が null = アプリでは編集できない形） */
    val rules = MutableStateFlow<Map<String, Rule?>>(emptyMap())
    val rulesLoading = MutableStateFlow(false)
    val ruleMessage = MutableStateFlow<String?>(null)

    // ---- アップデート ----
    private val updater = Updater(app)
    val update = MutableStateFlow<UpdateState>(UpdateState.Idle)
    /** Activity に開いてもらう画面（インストール画面・許可設定画面） */
    val launchRequest = MutableSharedFlow<Intent>(extraBufferCapacity = 1)
    val versionName: String = BuildConfig.VERSION_NAME

    var helpShown: Boolean
        get() = prefs.helpShown
        set(v) {
            prefs.helpShown = v
        }

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
        // タイマーの終了とスクリーンセーバーの開始を監視（初期化が終わってから始める）
        viewModelScope.launch {
            delay(1_000)
            while (isActive) {
                checkTimers()
                val idle = System.currentTimeMillis() - lastInteraction
                if (ssEnabled.value && !screensaver.value && foreground && screen.value == Screen.Home &&
                    ringing.value.isEmpty() && !manualBusy && idle > ssMinutes.value * 60_000L &&
                    voice.value !is VoiceState.Listening
                ) {
                    screensaver.value = true
                }
                delay(500)
            }
        }
        viewModelScope.launch {
            delay(5_000)
            while (isActive) {
                loadEvents()
                delay(5 * 60 * 1000L)
            }
        }
        viewModelScope.launch {
            delay(3_000)
            while (isActive) {
                loadPhotos()
                delay(6 * 60 * 60 * 1000L)
            }
        }
        // 起動して少し後と、その後6時間ごとに新しいバージョンを確認
        viewModelScope.launch {
            delay(15_000)
            while (isActive) {
                checkUpdate(silent = true)
                delay(6 * 60 * 60 * 1000L)
            }
        }
    }

    // ================= アップデート =================

    fun checkUpdate(silent: Boolean = false) {
        val cur = update.value
        if (cur is UpdateState.Downloading || cur is UpdateState.Installing || cur == UpdateState.Checking) return
        viewModelScope.launch {
            if (!silent) update.value = UpdateState.Checking
            try {
                val info = updater.check()
                update.value = when {
                    info != null -> UpdateState.Available(info)
                    silent -> UpdateState.Idle
                    else -> UpdateState.UpToDate
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 自動確認の失敗は表示しない（GitHubに届かないときなど）
                update.value = if (silent) UpdateState.Idle
                else UpdateState.Failed(e.message ?: "更新を確認できませんでした", null)
            }
        }
    }

    /** 「更新する」ボタン：ダウンロードしてインストール画面を開く */
    fun installUpdate() {
        val info = when (val s = update.value) {
            is UpdateState.Available -> s.info
            is UpdateState.NeedPermission -> s.info
            is UpdateState.Failed -> s.info
            else -> null
        } ?: return
        viewModelScope.launch {
            if (!updater.canInstall()) {
                update.value = UpdateState.NeedPermission(info)
                launchRequest.emit(updater.permissionIntent())
                return@launch
            }
            try {
                update.value = UpdateState.Downloading(info, 0f)
                val file = updater.download(info) { p -> update.value = UpdateState.Downloading(info, p) }
                update.value = UpdateState.Installing(info)
                launchRequest.emit(updater.installIntent(file))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                update.value = UpdateState.Failed(e.message ?: "ダウンロードに失敗しました", info)
            }
        }
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
                    assistant = VoiceAssistant(r.base, ::addLog).also { va ->
                        va.onLevel = { lv -> micLive.value = if (lv < 0) null else lv }
                    }
                    conn.value = Conn.CONNECTED
                    message.value = null
                    backoff = 2_000L

                    val collector = launch { c.states.collect { onStates(c, it) } }
                    launch { loadPipelines(c) }
                    launch { loadRegistries(c) }
                    launch { loadUser(c) }
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
        allStates.value = m
        val cals = m.values.filter { it.domain == "calendar" }.sortedBy { it.name }
        if (cals.map { it.entityId } != calendarEntities.value.map { it.entityId }) {
            calendarEntities.value = cals
            loadEvents()
        }
        automations.value = m.values.filter { it.domain == "automation" }.sortedBy { it.name }
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

    // ================= 部屋 =================

    /** Home Assistant のエリアと、機器がどのエリアにあるかを読み込む（取れなければ「その他」にまとめる） */
    private suspend fun loadRegistries(c: HaClient) {
        try {
            val areaArr = c.request(JSONObject().put("type", "config/area_registry/list")).optJSONArray("result") ?: JSONArray()
            haAreas.value = (0 until areaArr.length()).mapNotNull { areaArr.optJSONObject(it) }
                .map { Room("area:" + it.optString("area_id"), it.optString("name"), true) }
                .sortedBy { it.name }
            val devArr = c.request(JSONObject().put("type", "config/device_registry/list")).optJSONArray("result") ?: JSONArray()
            val devArea = HashMap<String, String>()
            for (i in 0 until devArr.length()) {
                val d = devArr.optJSONObject(i) ?: continue
                if (!d.isNull("area_id")) devArea[d.optString("id")] = d.optString("area_id")
            }
            val entArr = c.request(JSONObject().put("type", "config/entity_registry/list")).optJSONArray("result") ?: JSONArray()
            val map = HashMap<String, String>()
            for (i in 0 until entArr.length()) {
                val e = entArr.optJSONObject(i) ?: continue
                val area = if (!e.isNull("area_id")) e.optString("area_id")
                else if (!e.isNull("device_id")) devArea[e.optString("device_id")] else null
                if (!area.isNullOrBlank()) map[e.optString("entity_id")] = area
            }
            entityAreas.value = map
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // エリアを読めなくても、アプリで追加した部屋は使える
        }
    }

    fun roomKey(entityId: String): String =
        roomKeyOf(entityId, roomOverrides.value, entityAreas.value, rooms.value)

    fun addRoom(name: String) {
        val n = name.trim()
        if (n.isEmpty()) return
        val next = customRooms.value + ("local:" + System.currentTimeMillis() to n)
        prefs.customRooms = next
        customRooms.value = next
    }

    fun renameRoom(key: String, name: String) {
        if (!key.startsWith("local:") || name.isBlank()) return
        val next = customRooms.value + (key to name.trim())
        prefs.customRooms = next
        customRooms.value = next
    }

    fun deleteRoom(key: String) {
        val nextRooms = customRooms.value - key
        prefs.customRooms = nextRooms
        customRooms.value = nextRooms
        val nextOv = roomOverrides.value.filterValues { it != key }
        prefs.roomOverrides = nextOv
        roomOverrides.value = nextOv
        if (roomFilter.value == key) setRoomFilter(null)
    }

    /** 機器を部屋に割り当てる。key = null なら Home Assistant のエリアに戻す */
    fun assignRoom(entityId: String, key: String?) {
        val next = if (key == null) roomOverrides.value - entityId else roomOverrides.value + (entityId to key)
        prefs.roomOverrides = next
        roomOverrides.value = next
    }

    fun setGroupRooms(v: Boolean) {
        prefs.groupByRoom = v
        groupRooms.value = v
    }

    fun setRoomFilter(key: String?) {
        prefs.roomFilter = key
        roomFilter.value = key
    }

    fun setTheme(id: String) {
        prefs.themeId = id
        themeId.value = id
    }

    // ================= アカウント（普段使い／管理者） =================

    private suspend fun loadUser(c: HaClient) {
        try {
            val r = c.request(JSONObject().put("type", "auth/current_user")).optJSONObject("result") ?: return
            userName.value = r.optString("name")
            isAdmin.value = r.optBoolean("is_admin")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 不明のまま
        }
    }

    /** 自動化を編集できるか（普段使いのユーザーが管理者、または管理者ログインを追加済み） */
    val canEditRules: Boolean get() = isAdmin.value == true || adminUser.value != null

    /** 自動化の編集用に、管理者ユーザーでも別にログインしておく */
    fun adminLogin(user: String, pass: String, otp: String) {
        val r = route.value ?: return
        val local = store.baseUrl ?: return
        viewModelScope.launch {
            adminBusy.value = true
            adminError.value = null
            try {
                val t = auth.login(r.base, user.trim(), pass, otp.trim().ifBlank { null }, clientBase = local)
                // 管理者かどうか確かめる（管理者でなければ 401 になる）
                val code = restStatus(r.base, t.access, "GET", "/api/config/automation/config/__homepanel_check__")
                if (code == 401) throw HaException("「${user.trim()}」は管理者ではありません。Home Assistant の管理者ユーザーでログインしてください")
                store.adminUser = user.trim()
                store.adminRefresh = t.refresh
                store.adminAccess = t.access
                store.adminExpiresAt = t.accessExpiresAt
                adminUser.value = user.trim()
                loadRules()
            } catch (e: MfaRequiredException) {
                adminError.value = "2段階認証のコードも入力してください"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                adminError.value = e.message ?: "ログインに失敗しました"
            } finally {
                adminBusy.value = false
            }
        }
    }

    fun adminLogout() {
        store.clearAdmin()
        adminUser.value = null
        rules.value = emptyMap()
    }

    private suspend fun editorToken(base: String): String {
        val local = store.baseUrl ?: throw RefreshRevokedException()
        if (store.adminRefresh == null) return validAccessToken(base, local)
        return tokenMutex.withLock {
            val cur = store.adminAccess
            if (cur != null && store.adminExpiresAt - 60_000 > System.currentTimeMillis()) return@withLock cur
            try {
                val (tok, exp) = auth.refresh(base, local, store.adminRefresh!!)
                store.adminAccess = tok
                store.adminExpiresAt = exp
                tok
            } catch (e: RefreshRevokedException) {
                store.clearAdmin()
                adminUser.value = null
                throw HaException("管理者ログインの期限が切れました。設定 → アカウント でもう一度ログインしてください")
            }
        }
    }

    private suspend fun restStatus(base: String, token: String, method: String, path: String): Int =
        withContext(Dispatchers.IO) {
            http.newCall(
                Request.Builder().url(base + path).header("Authorization", "Bearer $token").method(method, null).build()
            ).execute().use { it.code }
        }

    /** Home Assistant の設定API（管理者が必要）を呼ぶ */
    private suspend fun rest(method: String, path: String, body: JSONObject? = null): String {
        val r = route.value ?: throw HaException("接続していません")
        val token = editorToken(r.base)
        return withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url(r.base + path)
                .header("Authorization", "Bearer $token")
                .method(method, body?.toString()?.toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                when {
                    resp.code == 401 -> throw HaException("自動化の編集には管理者の権限が必要です（設定 → アカウント で管理者ログインを追加してください）")
                    !resp.isSuccessful -> throw HaException("Home Assistant でエラーになりました (HTTP ${resp.code}) $text".trim())
                }
                text
            }
        }
    }

    // ================= オートメーション・スケジュール =================

    /** アプリで作った自動化の設定を読み込む */
    fun loadRules() {
        if (!canEditRules) return
        viewModelScope.launch {
            rulesLoading.value = true
            try {
                val map = HashMap<String, Rule?>()
                for (a in automations.value) {
                    val id = a.attributes.optString("id")
                    if (!isAppRuleId(id)) continue
                    map[id] = try {
                        parseRule(id, JSONObject(rest("GET", "/api/config/automation/config/$id")))
                    } catch (e: HaException) {
                        if (e.message?.contains("権限") == true) throw e
                        null
                    }
                }
                rules.value = map
                ruleMessage.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ruleMessage.value = e.message
            } finally {
                rulesLoading.value = false
            }
        }
    }

    fun saveRule(rule: Rule, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            try {
                val name = rule.name.ifBlank {
                    ruleSummary(rule) { id -> allStates.value[id]?.name ?: id }.take(40)
                }
                val r = rule.copy(name = name)
                rest("POST", "/api/config/automation/config/${r.id}", r.toConfig())
                rules.value = rules.value + (r.id to r)
                ruleMessage.value = "「$name」を保存しました"
                onDone(null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onDone(e.message ?: "保存できませんでした")
            }
        }
    }

    fun deleteRule(id: String, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            try {
                rest("DELETE", "/api/config/automation/config/$id")
                rules.value = rules.value - id
                ruleMessage.value = "削除しました"
                onDone(null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onDone(e.message ?: "削除できませんでした")
            }
        }
    }

    /** オン/オフと今すぐ実行は、管理者でなくても使える */
    fun setAutomationEnabled(e: Entity, on: Boolean) = call(e, if (on) "turn_on" else "turn_off")
    fun runAutomation(e: Entity) = call(e, "trigger")

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
        store.clearAdmin()
        adminUser.value = null
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
                val heard = a.listen(c, wakeWord = true, pipeline = effectivePipeline, onState = ::onVoice)
                if (heard != null) handleHeard(c, a, heard)
                delay(300)
            } catch (e: TimeoutCancellationException) {
                delay(300)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                addLog("失敗：${e.message}")
                voice.value = VoiceState.Failed(e.message ?: "呼びかけの待ち受けに失敗しました")
                delay(5_000)
            }
        }
    }

    /** 聞き取った文字を処理：タイマーなどアプリの命令はここで行い、それ以外は AI に渡す */
    private suspend fun handleHeard(c: HaClient, a: VoiceAssistant, heard: String) {
        parseCalendarQuestion(heard)?.let { offset ->
            if (selectedCalendars().isNotEmpty()) {
                addLog("アプリの命令として処理：予定")
                loadEventsNow()
                a.speak(c, heard, speakDay(events.value, offset), effectivePipeline, ::onVoice)
                return
            }
        }
        val cmd = parseTimerCommand(heard)
        if (cmd == null) {
            val sent = withHomeLocation(heard)
            if (sent != heard) addLog("自宅の位置を添えて送信")
            a.respond(c, sent, effectivePipeline, ::onVoice, display = heard)
            return
        }
        addLog("アプリの命令として処理：$cmd")
        val reply = when (cmd) {
            is TimerCommand.Start -> {
                addTimer(cmd.seconds, cmd.label)
                (if (cmd.label.isBlank()) "" else "${cmd.label}の") + "${speakRemaining(cmd.seconds * 1000)}のタイマーをセットしました"
            }
            TimerCommand.Cancel -> {
                val n = timers.value.size + ringing.value.size
                stopAlarm()
                cancelAllTimers()
                if (n == 0) "動いているタイマーはありません" else "タイマーを止めました"
            }
            TimerCommand.Remaining -> {
                val now = System.currentTimeMillis()
                val list = timers.value
                if (list.isEmpty()) "動いているタイマーはありません"
                else list.joinToString("、") { t -> (t.label.ifBlank { "タイマー" }) + "は残り" + speakRemaining(t.remaining(now)) } + "です"
            }
        }
        a.speak(c, heard, reply, effectivePipeline, ::onVoice)
    }

    /**
     * マイクボタン：
     *  - 聞き取り中 → 聞き取りを終える
     *  - 読み上げ中 → 読み上げを止めて、すぐに次の聞き取りを始める
     *  - それ以外   → 聞き取りを始める
     */
    fun onMicPressed() {
        onUserInteraction()
        val v = voice.value
        if (v == VoiceState.Listening) {
            assistant?.stopListening()
            return
        }
        if (manualBusy) {
            // 処理中・読み上げ中でも、押したら「終わり次第すぐ次を聞く」。読み上げ中なら止める
            pendingListen = true
            if (assistant?.isSpeaking == true || v is VoiceState.Speaking) assistant?.stopSpeaking()
            addLog("次の聞き取りを予約しました")
            return
        }
        startManualListen()
    }

    private var pendingListen = false

    /** 呼びかけの待ち受けを止める。終わるのを最大2秒だけ待ち、止まらなくても先へ進む */
    private suspend fun stopWake() {
        val j = wakeJob
        wakeJob = null
        if (j == null) return
        assistant?.stopListening()
        j.cancel()
        if (withTimeoutOrNull(2_000) { j.join() } == null) addLog("待ち受けの終了を待たずに先へ進みます")
    }

    /** マイクの音の大きさ（画面のメーター用。録音していなければ null） */
    val micLive = MutableStateFlow<Int?>(null)

    private fun startManualListen() {
        val c = client ?: return
        val a = assistant ?: return
        manualBusy = true
        voiceJob = viewModelScope.launch {
            try {
                stopWake()
                a.stopSpeaking()
                val heard = a.listen(c, wakeWord = false, pipeline = effectivePipeline, onState = ::onVoice)
                if (heard != null) handleHeard(c, a, heard)
            } catch (e: TimeoutCancellationException) {
                voice.value = VoiceState.Failed("時間切れになりました")
            } catch (e: CancellationException) {
                voice.value = VoiceState.Idle
                throw e
            } catch (e: Exception) {
                addLog("失敗：${e.message}")
                voice.value = VoiceState.Failed(e.message ?: "音声操作に失敗しました")
            } finally {
                manualBusy = false
                if (pendingListen) {
                    pendingListen = false
                    startManualListen()
                } else {
                    refreshWakeLoop()
                }
            }
        }
    }

    /** 文字で命令・質問（回答は読み上げ） */
    fun sendText(text: String) {
        onUserInteraction()
        if (text.isBlank()) return
        if (manualBusy) {
            if (assistant?.isSpeaking == true) assistant?.stopSpeaking() else return
        }
        val c = client ?: return
        val a = assistant ?: return
        manualBusy = true
        voiceJob = viewModelScope.launch {
            try {
                stopWake()
                handleHeard(c, a, text.trim())
            } catch (e: TimeoutCancellationException) {
                voice.value = VoiceState.Failed("時間切れになりました")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                addLog("失敗：${e.message}")
                voice.value = VoiceState.Failed(e.message ?: "失敗しました")
            } finally {
                manualBusy = false
                refreshWakeLoop()
            }
        }
    }

    // ================= カレンダー（TimeTree など） =================

    val calendarEntities = MutableStateFlow<List<Entity>>(emptyList())
    val calendarSel = MutableStateFlow(prefs.calendars)
    val events = MutableStateFlow<List<CalEvent>>(emptyList())
    val eventsStatus = MutableStateFlow<String?>(null)
    private var eventsJob: Job? = null

    /** 予定を出すカレンダー。まだ選んでいなければ、名前に TimeTree を含むものすべて */
    fun selectedCalendars(): List<Entity> {
        val sel = calendarSel.value
        val all = calendarEntities.value
        return if (sel == null) all.filter { it.entityId.contains("timetree", true) || it.name.contains("timetree", true) }
        else all.filter { it.entityId in sel }
    }

    fun toggleCalendar(entityId: String) {
        val cur = calendarSel.value ?: selectedCalendars().map { it.entityId }.toSet()
        val next = if (entityId in cur) cur - entityId else cur + entityId
        prefs.calendars = next
        calendarSel.value = next
        loadEvents()
    }

    /** 今日から1週間分の予定を読み込む（通常のログインで読める。管理者は不要） */
    fun loadEvents() {
        eventsJob?.cancel()
        eventsJob = viewModelScope.launch { loadEventsNow() }
    }

    private suspend fun loadEventsNow() {
        val cals = selectedCalendars()
        if (cals.isEmpty()) {
            events.value = emptyList()
            eventsStatus.value = null
            return
        }
        val r = route.value ?: return
        val local = store.baseUrl ?: return
        try {
            val zone = ZoneId.systemDefault()
            val start = LocalDate.now().atStartOfDay(zone).toOffsetDateTime().toString()
            val end = LocalDate.now().plusDays(8).atStartOfDay(zone).toOffsetDateTime().toString()
            val token = validAccessToken(r.base, local)
            val list = withContext(Dispatchers.IO) {
                cals.flatMap { cal ->
                    val url = r.base + "/api/calendars/" + cal.entityId + "?start=" +
                        java.net.URLEncoder.encode(start, "UTF-8") + "&end=" + java.net.URLEncoder.encode(end, "UTF-8")
                    http.newCall(Request.Builder().url(url).header("Authorization", "Bearer $token").build()).execute().use { resp ->
                        if (!resp.isSuccessful) throw HaException("「${cal.name}」の予定を読めませんでした (HTTP ${resp.code})")
                        parseCalendarEvents(cal.entityId, cal.name, resp.body?.string().orEmpty())
                    }
                }
            }
            events.value = list.sortedWith(compareBy({ it.start.toLocalDate() }, { !it.allDay }, { it.start }))
            eventsStatus.value = "${cals.size}個のカレンダーから${list.size}件の予定を読み込みました"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            eventsStatus.value = e.message ?: "予定を読み込めませんでした"
        }
    }

    // ================= 自宅の位置 =================

    val homeLocation = MutableStateFlow(prefs.homeLocation)

    fun setHomeLocation(v: String) {
        prefs.homeLocation = v.trim()
        homeLocation.value = v.trim()
    }

    /** Home Assistant に登録されている自宅の位置（緯度・経度・名前）を取得して入れる */
    fun useHaLocation(onResult: (String?) -> Unit) {
        val c = client ?: return onResult("接続していません")
        viewModelScope.launch {
            try {
                val r = c.request(JSONObject().put("type", "get_config")).optJSONObject("result") ?: JSONObject()
                val lat = r.optDouble("latitude")
                val lon = r.optDouble("longitude")
                if (lat.isNaN() || lon.isNaN()) throw HaException("Home Assistant に自宅の位置が登録されていません")
                val name = r.optString("location_name").takeIf { it.isNotBlank() && it != "Home" }
                setHomeLocation(listOfNotNull(name, String.format("緯度%.5f・経度%.5f", lat, lon)).joinToString("、"))
                onResult(null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onResult(e.message ?: "取得できませんでした")
            }
        }
    }

    /**
     * 場所に関わる質問（「近くの」「営業時間」など）のときだけ、自宅の位置を添えて AI に送る。
     * 機器の命令には何も足さないので、家の中での素早い処理（ローカル優先）はそのまま。
     */
    private fun withHomeLocation(text: String): String {
        val loc = homeLocation.value
        if (loc.isBlank()) return text
        if (!LOCATION_WORDS.containsMatchIn(text)) return text
        return "$text（参考：自宅の場所は「$loc」です。「近く」「この辺」などはここを基準に答えてください）"
    }

    // ================= 音声の動作ログ・マイクテスト =================

    val voiceLog = MutableStateFlow<List<String>>(emptyList())
    private val logFmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.JAPAN)

    fun addLog(line: String) {
        val entry = logFmt.format(java.util.Date()) + " " + line
        viewModelScope.launch { voiceLog.value = (listOf(entry) + voiceLog.value).take(60) }
    }

    val micLevel = MutableStateFlow<Int?>(null)
    val micTestResult = MutableStateFlow<String?>(null)

    fun runMicTest() {
        val a = assistant ?: VoiceAssistant(route.value?.base ?: "", ::addLog)
        if (manualBusy) return
        manualBusy = true
        viewModelScope.launch {
            try {
                stopWake()
                micTestResult.value = "5秒間、話しかけてください…"
                val peak = a.micTest(5) { micLevel.value = it }
                micTestResult.value = when {
                    peak < 3 -> "音がほとんど入っていません（最大 $peak）。他のアプリがマイクを使っていないか、Fire の Alexa ハンズフリーがオフか確認してください"
                    peak < 15 -> "音が小さめです（最大 $peak）。もう少し近くで話すと認識しやすくなります"
                    else -> "マイクは正常です（最大 $peak）"
                }
            } catch (e: Exception) {
                micTestResult.value = e.message
            } finally {
                micLevel.value = null
                manualBusy = false
                refreshWakeLoop()
            }
        }
    }

    // ================= タイマー =================

    val timers = MutableStateFlow(timersFromJson(prefs.timersJson).filter { !it.running || it.endAt!! > System.currentTimeMillis() - 60_000 })
    /** 時間になって鳴っているタイマー */
    val ringing = MutableStateFlow<List<PanelTimer>>(emptyList())
    private var ringtone: android.media.Ringtone? = null

    private fun saveTimers() { prefs.timersJson = timersToJson(timers.value) }

    fun addTimer(seconds: Long, label: String) {
        onUserInteraction()
        val now = System.currentTimeMillis()
        val t = PanelTimer(now, label.trim(), seconds * 1000, now + seconds * 1000, seconds * 1000)
        timers.value = (timers.value + t).sortedBy { it.remaining(now) }
        saveTimers()
    }

    fun cancelTimer(id: Long) {
        timers.value = timers.value.filter { it.id != id }
        saveTimers()
    }

    fun cancelAllTimers() {
        timers.value = emptyList()
        saveTimers()
    }

    fun pauseResumeTimer(id: Long) {
        val now = System.currentTimeMillis()
        timers.value = timers.value.map {
            if (it.id != id) it
            else if (it.running) it.copy(endAt = null, remainingMs = it.remaining(now))
            else it.copy(endAt = now + it.remainingMs)
        }
        saveTimers()
    }

    private fun checkTimers() {
        val now = System.currentTimeMillis()
        val done = timers.value.filter { it.running && it.endAt!! <= now }
        if (done.isEmpty()) return
        timers.value = timers.value - done.toSet()
        saveTimers()
        ringing.value = ringing.value + done
        addLog("タイマー終了：" + done.joinToString { it.label.ifBlank { formatRemaining(it.totalMs) } })
        startAlarm()
        onUserInteraction() // スクリーンセーバーを消して知らせる
    }

    private fun startAlarm() {
        if (ringtone?.isPlaying == true) return
        runCatching {
            val ctx = getApplication<Application>()
            val uri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM)
                ?: android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
            ringtone = android.media.RingtoneManager.getRingtone(ctx, uri)?.apply {
                if (android.os.Build.VERSION.SDK_INT >= 28) isLooping = true
                play()
            }
        }
        // 着信音が無い端末でも鳴るように（最大2分）
        viewModelScope.launch {
            val until = System.currentTimeMillis() + 120_000
            while (ringing.value.isNotEmpty() && System.currentTimeMillis() < until) {
                if (ringtone?.isPlaying != true) runCatching {
                    val tg = android.media.ToneGenerator(AudioManager.STREAM_ALARM, 90)
                    tg.startTone(android.media.ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 800)
                    delay(1_000)
                    tg.release()
                } else delay(1_000)
            }
            if (ringing.value.isNotEmpty()) stopAlarm()
        }
    }

    fun stopAlarm() {
        runCatching { ringtone?.stop() }
        ringtone = null
        ringing.value = emptyList()
    }

    // ================= スクリーンセーバー =================

    val ssEnabled = MutableStateFlow(prefs.ssEnabled)
    val ssMinutes = MutableStateFlow(prefs.ssMinutes)
    val ssInterval = MutableStateFlow(prefs.ssInterval)
    val ssAlbum = MutableStateFlow(prefs.ssAlbum)
    val photos = MutableStateFlow<List<String>>(emptyList())
    val photoStatus = MutableStateFlow<String?>(null)
    val screensaver = MutableStateFlow(false)
    @Volatile private var lastInteraction = System.currentTimeMillis()
    private val photoHttp = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

    fun onUserInteraction() {
        lastInteraction = System.currentTimeMillis()
        if (screensaver.value) screensaver.value = false
    }

    fun setScreensaver(enabled: Boolean? = null, minutes: Int? = null, interval: Int? = null) {
        enabled?.let { prefs.ssEnabled = it; ssEnabled.value = it }
        minutes?.let { prefs.ssMinutes = it; ssMinutes.value = it }
        interval?.let { prefs.ssInterval = it; ssInterval.value = it }
    }

    fun showScreensaverNow() {
        viewModelScope.launch {
            delay(400)
            addLog("スクリーンセーバーを表示（写真 ${photos.value.size} 枚）")
            screensaver.value = true
        }
    }

    fun setAlbum(url: String) {
        prefs.ssAlbum = url.trim()
        ssAlbum.value = url.trim()
        loadPhotos()
    }

    /** Google フォトの共有アルバムのページから、写真の URL を取り出す */
    fun loadPhotos() {
        val url = ssAlbum.value
        if (url.isBlank()) {
            photos.value = emptyList()
            photoStatus.value = null
            return
        }
        viewModelScope.launch {
            photoStatus.value = "アルバムを読み込んでいます…"
            try {
                val list = withContext(Dispatchers.IO) { fetchAlbum(photoHttp, url) }
                photos.value = list.shuffled()
                photoStatus.value = if (list.isEmpty()) "写真が見つかりませんでした。共有リンク（photos.app.goo.gl/…）か確認してください"
                else "${list.size}枚の写真を読み込みました"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                photoStatus.value = "アルバムを読み込めませんでした：${e.message}"
            }
        }
    }

    override fun onCleared() {
        client?.close()
    }

    companion object {
        private val LOCATION_WORDS = Regex(
            "近く|近所|周辺|周り|この辺|このあたり|付近|最寄|徒歩|車で|ここから|家から|うちから|距離|何分|行き方|道順|" +
                "営業時間|何時まで|何時から|休み|定休|店|スーパー|コンビニ|ドラッグ|薬局|病院|クリニック|歯医者|" +
                "レストラン|ランチ|カフェ|ラーメン|居酒屋|駅|バス|電車|ガソリン|銀行|郵便局|役所|公園|イベント|祭り|映画館"
        )

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
