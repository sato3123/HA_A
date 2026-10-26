package jp.home.panel

import androidx.compose.ui.graphics.asImageBitmap
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
import kotlinx.coroutines.flow.flowOn
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

private val JPEG_START = okio.ByteString.of(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
private val JPEG_END = okio.ByteString.of(0xFF.toByte(), 0xD9.toByte())

/** JPEG を読む（幅が maxWidth に収まるように縮める。メモリを抑えるため RGB565） */
fun decodeJpeg(bytes: ByteArray, maxWidth: Int): androidx.compose.ui.graphics.ImageBitmap? {
    val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
    if (o.outWidth <= 0) return null
    var sample = 1
    while (o.outWidth / (sample * 2) >= maxWidth) sample *= 2
    val opts = android.graphics.BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
    }
    return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.let {
        it.asImageBitmap()
    }
}

/** 画面に出す機器の種類（並び順もこの順） */
val SUPPORTED_DOMAINS = listOf(
    "climate", "light", "fan", "cover", "switch", "input_boolean", "scene", "script", "camera",
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
    val roomOrder = MutableStateFlow(prefs.roomOrder)
    /** 部屋の一覧（並べ替えた順。新しい部屋は後ろに付く） */
    val rooms: StateFlow<List<Room>> = combine(haAreas, customRooms, roomOrder) { a, c, order ->
        val list = a + c.entries.sortedBy { it.key }.map { Room(it.key, it.value, false) }
        list.sortedBy { r -> order.indexOf(r.key).let { if (it < 0) Int.MAX_VALUE else it } }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun moveRoom(key: String, delta: Int) {
        val keys = rooms.value.map { it.key }.toMutableList()
        val i = keys.indexOf(key)
        val j = (i + delta).coerceIn(0, keys.size - 1)
        if (i < 0 || i == j) return
        keys.add(j, keys.removeAt(i))
        prefs.roomOrder = keys
        roomOrder.value = keys
    }

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
        if (BuildConfig.DEBUG && prefs.demoMode) return
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
                    launch { delay(1_500); loadEnergy() }
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
        updateThermos(m)
        haEntityList = m.values
            .filter { it.domain in SUPPORTED_DOMAINS }
            .sortedWith(compareBy({ SUPPORTED_DOMAINS.indexOf(it.domain) }, { it.name }))
        mergeEntities()

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
            val devNames = HashMap<String, String>()
            for (i in 0 until devArr.length()) {
                val d = devArr.optJSONObject(i) ?: continue
                if (!d.isNull("area_id")) devArea[d.optString("id")] = d.optString("area_id")
                val dn = d.optString("name_by_user").takeIf { it.isNotBlank() && it != "null" } ?: d.optString("name")
                if (dn.isNotBlank() && dn != "null") devNames[d.optString("id")] = dn
            }
            deviceNames.value = devNames
            val entArr = c.request(JSONObject().put("type", "config/entity_registry/list")).optJSONArray("result") ?: JSONArray()
            val map = HashMap<String, String>()
            val entDev = HashMap<String, String>()
            for (i in 0 until entArr.length()) {
                val e = entArr.optJSONObject(i) ?: continue
                if (!e.isNull("device_id")) entDev[e.optString("entity_id")] = e.optString("device_id")
                val area = if (!e.isNull("area_id")) e.optString("area_id")
                else if (!e.isNull("device_id")) devArea[e.optString("device_id")] else null
                if (!area.isNullOrBlank()) map[e.optString("entity_id")] = area
            }
            entityAreas.value = map
            entityDevice.value = entDev
            allStates.value.let { updateThermos(it) }
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

    /** 配色は、いま選んでいる表示のユーザーごとに覚える */
    fun setTheme(id: String) {
        prefs.themeId = id
        themeId.value = id
        updateProfile { it.copy(themeId = id) }
    }

    // ================= 表示のユーザー（家族それぞれの表示） =================

    val profiles = MutableStateFlow(
        profilesFromJson(prefs.profilesJson).ifEmpty { listOf(defaultProfile(prefs.themeId)) }
    )
    val activeProfileId = MutableStateFlow(
        prefs.activeProfile?.takeIf { id -> profiles.value.any { it.id == id } } ?: profiles.value.first().id
    )
    val activeProfile: StateFlow<Profile> = combine(profiles, activeProfileId) { list, id ->
        list.firstOrNull { it.id == id } ?: list.first()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, profiles.value.first())

    private fun saveProfiles(list: List<Profile>) {
        profiles.value = list
        prefs.profilesJson = profilesToJson(list)
    }

    private fun updateProfile(change: (Profile) -> Profile) {
        val id = activeProfileId.value
        saveProfiles(profiles.value.map { if (it.id == id) change(it) else it })
    }

    fun switchProfile(id: String) {
        val p = profiles.value.firstOrNull { it.id == id } ?: return
        activeProfileId.value = id
        prefs.activeProfile = id
        prefs.themeId = p.themeId
        themeId.value = p.themeId
        onUserInteraction()
    }

    fun addProfile(name: String) {
        val n = name.trim().ifBlank { return }
        val p = Profile("p_" + System.currentTimeMillis(), n, defaultSections(), themeId.value)
        saveProfiles(profiles.value + p)
        switchProfile(p.id)
    }

    fun renameProfile(id: String, name: String) {
        val n = name.trim().ifBlank { return }
        saveProfiles(profiles.value.map { if (it.id == id) it.copy(name = n) else it })
    }

    fun deleteProfile(id: String) {
        if (profiles.value.size <= 1) return
        val rest = profiles.value.filter { it.id != id }
        saveProfiles(rest)
        if (activeProfileId.value == id) switchProfile(rest.first().id)
    }

    fun moveSection(section: HomeSection, delta: Int) = updateProfile { p ->
        val list = p.sections.toMutableList()
        val i = list.indexOfFirst { it.section == section }
        val j = (i + delta).coerceIn(0, list.size - 1)
        if (i >= 0 && i != j) {
            val x = list.removeAt(i)
            list.add(j, x)
        }
        p.copy(sections = list)
    }

    fun toggleSection(section: HomeSection) = updateProfile { p ->
        p.copy(sections = p.sections.map { if (it.section == section) it.copy(visible = !it.visible) else it })
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
        haEntityList = emptyList()
        mergeEntities()
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
        // 対応付けた機器は、家の中では ECHONET Lite で直接、外出先では Home Assistant 経由で操作する
        linkedEl(e.entityId)?.takeIf { useEchonet(it) }?.let { d ->
            viewModelScope.launch {
                val err = echonet.callService(d.entityId, service, data)
                if (err == null) return@launch
                // ECHONET Lite で失敗したら Home Assistant で試す
                val c = client
                if (c == null) { message.value = "「${e.name}」：$err"; return@launch }
                try {
                    c.callService(domain, service, e.entityId, data)
                    addLog("「${e.name}」は ECHONET Lite で操作できなかったため Home Assistant で操作しました（$err）")
                } catch (ex: CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    message.value = "「${e.name}」を操作できませんでした: ${ex.message}"
                }
            }
            return
        }
        // ECHONET Lite の機器は Home Assistant を通さず直接操作する
        if (isEchonetEntity(e.entityId)) {
            viewModelScope.launch {
                val err = echonet.callService(e.entityId, service, data)
                if (err != null) message.value = "「${e.name}」：$err"
            }
            return
        }
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

    // ================= カメラ =================

    // ---- 直接つなぐカメラ（RTSP） ----
    val ipCameras = MutableStateFlow(IpCamera.listFrom(prefs.ipCamerasJson))

    fun ipCamera(entityId: String): IpCamera? = ipCameras.value.firstOrNull { it.entityId == entityId }
    fun ipCameraPassword(id: String): String = store.secret("ipcam_$id").orEmpty()

    fun saveIpCamera(c: IpCamera, password: String) {
        val isNew = ipCameras.value.none { it.id == c.id }
        val next = if (isNew) ipCameras.value + c else ipCameras.value.map { if (it.id == c.id) c else it }
        prefs.ipCamerasJson = IpCamera.listJson(next)
        store.setSecret("ipcam_${c.id}", password)
        ipCameras.value = next
        // 表示する機器を選んでいる場合も、追加したカメラは表示する
        if (isNew) selection.value?.let { sel -> (sel + c.entityId).also { prefs.selected = it; selection.value = it } }
        mergeEntities()
    }

    fun deleteIpCamera(id: String) {
        val next = ipCameras.value.filter { it.id != id }
        prefs.ipCamerasJson = IpCamera.listJson(next)
        store.setSecret("ipcam_$id", null)
        ipCameras.value = next
        mergeEntities()
    }

    /** RTSP の再生アドレス（ユーザー名・パスワード入り）。light = 軽量（サブストリーム） */
    fun ipCameraUrl(c: IpCamera, light: Boolean): String =
        withCredentials(if (light && c.subUrl.isNotBlank()) c.subUrl else c.mainUrl, c.user, ipCameraPassword(c.id))

    // ---- 画質 ----
    val cameraModes = MutableStateFlow(prefs.cameraModes)

    fun cameraMode(entityId: String): String =
        cameraModes.value[entityId] ?: if (isDirectCamera(entityId)) "main" else "auto"

    fun setCameraMode(entityId: String, mode: String) {
        val next = cameraModes.value + (entityId to mode)
        prefs.cameraModes = next
        cameraModes.value = next
    }

    // ---- リアルタイム（WebRTC） ----

    /** Home Assistant のカメラが WebRTC に対応しているか */
    suspend fun cameraWebRtcSupported(entityId: String): Boolean {
        if (isDemo || isDirectCamera(entityId)) return false
        val c = client ?: return false
        return try {
            val a = c.request(JSONObject().put("type", "camera/capabilities").put("entity_id", entityId))
                .optJSONObject("result")?.optJSONArray("frontend_stream_types") ?: return false
            (0 until a.length()).any { a.optString(it) == "web_rtc" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 古い Home Assistant
            allStates.value[entityId]?.attributes?.optString("frontend_stream_type") == "web_rtc"
        }
    }

    suspend fun cameraIceServers(entityId: String): List<IceServerInfo> {
        val c = client ?: return emptyList()
        return try {
            val arr = c.request(JSONObject().put("type", "camera/webrtc/get_client_config").put("entity_id", entityId))
                .optJSONObject("result")?.optJSONObject("configuration")?.optJSONArray("iceServers") ?: return emptyList()
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { j ->
                val u = j.opt("urls")
                val urls = if (u is JSONArray) (0 until u.length()).map { u.optString(it) } else listOf(u.toString())
                IceServerInfo(urls, j.optString("username").ifBlank { null }, j.optString("credential").ifBlank { null })
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList() // 家の中なら STUN が無くてもつながる
        }
    }

    suspend fun cameraWebRtcOffer(entityId: String, sdp: String, onEvent: (JSONObject) -> Unit): Int {
        val c = client ?: throw HaException("接続していません")
        return c.subscribe(JSONObject().put("type", "camera/webrtc/offer").put("entity_id", entityId).put("offer", sdp), onEvent)
    }

    fun cameraWebRtcCandidate(entityId: String, session: String, candidate: JSONObject) {
        val c = client ?: return
        viewModelScope.launch {
            runCatching {
                c.request(JSONObject().put("type", "camera/webrtc/candidate").put("entity_id", entityId)
                    .put("session_id", session).put("candidate", candidate))
            }
        }
    }

    fun cameraWebRtcClose(sub: Int) {
        val c = client ?: return
        c.removeHandler(sub)
        viewModelScope.launch { runCatching { c.request(JSONObject().put("type", "unsubscribe_events").put("subscription", sub)) } }
    }

    /** 映像を開いているカメラ（null = 閉じている） */
    val cameraFor = MutableStateFlow<String?>(null)

    fun openCamera(entityId: String) {
        onUserInteraction()
        cameraFor.value = entityId
    }

    fun closeCamera() {
        cameraFor.value = null
    }

    /**
     * 高画質な動画（HLS）の再生用アドレス。Home Assistant がカメラの映像をそのまま配信する。
     * カメラが対応していない（静止画だけのカメラなど）ときは null。
     */
    suspend fun cameraHlsUrl(entityId: String): String? {
        if (isDemo) return null
        val e = allStates.value[entityId] ?: return null
        if ((e.attributes.optInt("supported_features") and 2) == 0) return null // 動画の配信に非対応
        val c = client ?: return null
        val base = route.value?.base ?: return null
        val r = c.request(JSONObject().put("type", "camera/stream").put("entity_id", entityId).put("format", "hls"))
        val path = r.optJSONObject("result")?.optString("url")?.takeIf { it.isNotBlank() } ?: return null
        // アドレスに使い捨ての鍵が入っているので、ログインの情報を付けなくても再生できる
        return if (path.startsWith("http")) path else base + path
    }

    /** 直接つなぐカメラの静止画（静止画の URL があるときだけ） */
    private suspend fun directSnapshot(c: IpCamera, maxWidth: Int): androidx.compose.ui.graphics.ImageBitmap? {
        if (c.snapshotUrl.isBlank()) return null
        return try {
            val client = photoHttp.newBuilder().authenticator(CameraAuthenticator(c.user, ipCameraPassword(c.id))).build()
            withContext(Dispatchers.IO) {
                client.newCall(Request.Builder().url(c.snapshotUrl).build()).execute().use { resp ->
                    if (!resp.isSuccessful) null else resp.body?.bytes()?.let { decodeJpeg(it, maxWidth) }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /** 動画の再生に使う通信（家のネットワークだけに制限した設定をそのまま使う） */
    val playerHttp: OkHttpClient get() = streamHttp

    /** 映像用（つなぎっぱなしにするので、読み込みの待ち時間を長めに） */
    private val streamHttp by lazy { http.newBuilder().readTimeout(20, TimeUnit.SECONDS).build() }

    private val isDemo get() = BuildConfig.DEBUG && prefs.demoMode

    private suspend fun cameraRequest(path: String): Request {
        val r = route.value ?: throw HaException("接続していません")
        val local = store.baseUrl ?: throw HaException("接続していません")
        val token = validAccessToken(r.base, local)
        return Request.Builder().url(r.base + path).header("Authorization", "Bearer $token").build()
    }

    /** カメラの静止画（Home Assistant の camera_proxy）。maxWidth に合わせて縮めて読む */
    suspend fun cameraSnapshot(entityId: String, maxWidth: Int): androidx.compose.ui.graphics.ImageBitmap? {
        if (isDemo && !isDirectCamera(entityId)) return Demo.cameraFrame(entityId)
        ipCamera(entityId)?.let { cam -> return directSnapshot(cam, maxWidth) }
        return try {
            val req = cameraRequest("/api/camera_proxy/$entityId")
            withContext(Dispatchers.IO) {
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext null
                    val bytes = resp.body?.bytes() ?: return@withContext null
                    decodeJpeg(bytes, maxWidth)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /**
     * カメラの動画（Home Assistant の camera_proxy_stream。MJPEG の連続した JPEG）。
     * どのカメラでも Home Assistant 側で MJPEG にして送ってくれる。
     */
    fun cameraStream(entityId: String): kotlinx.coroutines.flow.Flow<androidx.compose.ui.graphics.ImageBitmap> = kotlinx.coroutines.flow.flow {
        if (isDemo) {
            while (true) {
                emit(Demo.cameraFrame(entityId))
                delay(300)
            }
        }
        val call = streamHttp.newCall(cameraRequest("/api/camera_proxy_stream/$entityId"))
        // 画面を閉じたら、読み込み待ちの途中でもすぐに切る
        val handle = kotlinx.coroutines.currentCoroutineContext()[Job]?.invokeOnCompletion { call.cancel() }
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) throw HaException("映像を開けませんでした (HTTP ${resp.code})")
                val src = resp.body?.source() ?: throw HaException("映像がありません")
                while (true) {
                    val start = src.indexOf(JPEG_START)
                    if (start < 0) break
                    src.skip(start)
                    val end = src.indexOf(JPEG_END, 3)
                    if (end < 0) break
                    val bytes = src.readByteArray(end + 2)
                    decodeJpeg(bytes, 1920)?.let { emit(it) }
                }
            }
        } finally {
            handle?.dispose()
        }
        throw HaException("映像が途切れました")
    }.flowOn(Dispatchers.IO)

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

    // ================= 温度計（SwitchBot など） =================

    val entityDevice = MutableStateFlow<Map<String, String>>(emptyMap())
    val deviceNames = MutableStateFlow<Map<String, String>>(emptyMap())
    val thermos = MutableStateFlow<List<Thermo>>(emptyList())
    val thermoHidden = MutableStateFlow(prefs.thermoHidden)
    val thermoNames = MutableStateFlow(prefs.thermoNames)

    private fun updateThermos(m: Map<String, Entity>) {
        val names = thermoNames.value
        thermos.value = buildThermos(m.values, entityDevice.value, deviceNames.value)
            .map { t -> names[t.key]?.takeIf { it.isNotBlank() }?.let { t.copy(name = it, original = t.name) } ?: t }
            .sortedBy { it.name }
    }

    /** 温度計の名前を変える（空なら元の名前に戻す） */
    fun renameThermo(key: String, name: String) {
        val next = if (name.isBlank()) thermoNames.value - key else thermoNames.value + (key to name.trim())
        prefs.thermoNames = next
        thermoNames.value = next
        updateThermos(allStates.value)
    }

    // ---- 温度計のグラフ ----
    data class Point(val t: Long, val v: Double)

    val thermoFor = MutableStateFlow<String?>(null)
    val thermoHours = MutableStateFlow(24)
    val thermoTemp = MutableStateFlow<List<Point>?>(null)
    val thermoHum = MutableStateFlow<List<Point>?>(null)
    val thermoStatus = MutableStateFlow<String?>(null)

    fun showThermo(key: String) {
        onUserInteraction()
        thermoFor.value = key
        loadThermoHistory()
    }

    fun hideThermo() {
        thermoFor.value = null
    }

    fun setThermoHours(h: Int) {
        thermoHours.value = h
        loadThermoHistory()
    }

    private fun loadThermoHistory() {
        val key = thermoFor.value ?: return
        val t = thermos.value.firstOrNull { it.key == key } ?: return
        val hours = thermoHours.value
        thermoTemp.value = null
        thermoHum.value = null
        thermoStatus.value = "グラフを読み込んでいます…"
        if (BuildConfig.DEBUG && prefs.demoMode) {
            thermoTemp.value = Demo.series(hours, t.temperature ?: 22.0, 3.0)
            thermoHum.value = t.humidity?.let { Demo.series(hours, it, 8.0) }
            thermoStatus.value = null
            return
        }
        viewModelScope.launch {
            try {
                val ids = listOfNotNull(t.tempEntity, t.humEntity)
                val res = fetchHistory(ids, hours)
                if (thermoFor.value != key) return@launch
                thermoTemp.value = t.tempEntity?.let { res[it] }
                thermoHum.value = t.humEntity?.let { res[it] }
                thermoStatus.value = if (res.values.all { it.isEmpty() }) "この期間の記録がありません" else null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (thermoFor.value == key) thermoStatus.value = e.message ?: "記録を読めませんでした"
            }
        }
    }

    /** Home Assistant の履歴から、数値の変化を読み出す（通常のログインで読める） */
    private suspend fun fetchHistory(ids: List<String>, hours: Int): Map<String, List<Point>> {
        if (ids.isEmpty()) return emptyMap()
        val r = route.value ?: throw HaException("接続していません")
        val local = store.baseUrl ?: throw HaException("接続していません")
        val zone = ZoneId.systemDefault()
        val now = java.time.ZonedDateTime.now(zone)
        val start = java.net.URLEncoder.encode(now.minusHours(hours.toLong()).toOffsetDateTime().toString(), "UTF-8")
        val end = java.net.URLEncoder.encode(now.toOffsetDateTime().toString(), "UTF-8")
        val token = validAccessToken(r.base, local)
        val url = "${r.base}/api/history/period/$start?filter_entity_id=${ids.joinToString(",")}&end_time=$end&minimal_response&no_attributes"
        val body = withContext(Dispatchers.IO) {
            http.newCall(Request.Builder().url(url).header("Authorization", "Bearer $token").build()).execute().use { resp ->
                if (!resp.isSuccessful) throw HaException("記録を読めませんでした (HTTP ${resp.code})")
                resp.body?.string().orEmpty()
            }
        }
        val outer = JSONArray(body)
        val out = HashMap<String, List<Point>>()
        for (k in 0 until outer.length()) {
            val arr = outer.optJSONArray(k) ?: continue
            var id: String? = null
            val pts = ArrayList<Point>()
            for (i in 0 until arr.length()) {
                val j = arr.optJSONObject(i) ?: continue
                if (id == null) id = j.optString("entity_id").takeIf { it.isNotBlank() }
                val v = j.optString("state").toDoubleOrNull() ?: continue
                val ts = j.optString("last_changed").ifBlank { j.optString("last_updated") }
                val ms = runCatching { java.time.OffsetDateTime.parse(ts).toInstant().toEpochMilli() }.getOrNull() ?: continue
                pts.add(Point(ms, v))
            }
            // 1つめの記録にだけ entity_id が付く。付いていなければ順番で当てる
            val key = id ?: ids.getOrNull(k) ?: continue
            out[key] = pts
        }
        return out
    }

    fun toggleThermo(key: String) {
        val next = if (key in thermoHidden.value) thermoHidden.value - key else thermoHidden.value + key
        prefs.thermoHidden = next
        thermoHidden.value = next
    }

    // ================= ECHONET Lite（直接通信） =================

    private var haEntityList: List<Entity> = emptyList()
    private val echonet by lazy { EchonetClient(getApplication(), ::addLog) }
    val elEnabled = MutableStateFlow(prefs.elEnabled)
    val elDevices = MutableStateFlow<List<ElDevice>>(emptyList())
    val elStatus = MutableStateFlow<String?>(null)
    val elManualIps = MutableStateFlow(prefs.elManualIps)
    val elBusy = MutableStateFlow(false)
    val elLive = MutableStateFlow(ElLive(null, null, null))
    val elDiagnostics = MutableStateFlow<String?>(null)
    private var solarStarted = false

    // ---- 操作の経路（家の中は ECHONET Lite、外出先は Home Assistant） ----
    val elLinks = MutableStateFlow(prefs.elLinks)
    val ctlMode = MutableStateFlow(prefs.ctlMode)

    /** 家の中にいるか：Home Assistant に家の中の経路でつながっている、または ECHONET Lite の機器が最近応答している */
    val atHome: Boolean
        get() = route.value?.let { !it.remote }
            ?: elDevices.value.any { it.online && System.currentTimeMillis() - it.updatedAt < 120_000 }

    /** いま ECHONET Lite で操作するか */
    private fun useEchonet(d: ElDevice): Boolean {
        if (!elEnabled.value || !d.online) return false
        return when (ctlMode.value) {
            "ha" -> client == null // Home Assistant につながらないときだけ
            "echonet" -> true
            else -> atHome || client == null
        }
    }

    /** Home Assistant の機器に対応付けた ECHONET Lite の機器 */
    fun linkedEl(haId: String): ElDevice? =
        elLinks.value.entries.firstOrNull { it.value == haId }?.key?.let { elEntity(it) }

    fun setCtlMode(m: String) {
        prefs.ctlMode = m
        ctlMode.value = m
        mergeEntities()
    }

    fun linkEl(elId: String, haId: String?) {
        val next = elLinks.value.filterValues { it != haId }.toMutableMap()
        if (haId.isNullOrBlank()) next.remove(elId) else next[elId] = haId
        prefs.elLinks = next
        elLinks.value = next
        mergeEntities()
    }

    /** 対応付けの候補（同じ種類の Home Assistant の機器。名前が似ている順） */
    fun linkCandidates(d: ElDevice): List<Entity> {
        val list = allStates.value.values.filter { it.domain == d.haDomain && !isEchonetEntity(it.entityId) }
        return list.sortedByDescending { linkScore(d, it) }
    }

    /** 名前の似かた＋設置場所と部屋が同じなら加点 */
    private fun linkScore(d: ElDevice, e: Entity): Int {
        val loc = elLocationFull(d.props[0x81]?.u8())?.replace(Regex("\\d"), "")
        val room = roomNameOf(e.entityId)
        val roomHit = loc != null && room != null && (room.contains(loc) || loc.contains(room) || e.name.contains(loc))
        return nameScore(d.name, e.name) + if (roomHit) 3 else 0
    }

    private fun nameScore(a: String, b: String): Int {
        fun grams(s: String) = s.replace(Regex("[\\s・のエアコン照明]"), "").windowed(2).toSet()
        return (grams(a) intersect grams(b)).size
    }

    /** 自動で対応付ける（同じ種類の機器が1つだけ、または名前がはっきり似ているもの）。対応付けた数を返す */
    fun autoLinkEl(): Int {
        val next = elLinks.value.toMutableMap()
        var n = 0
        elDevices.value.filter { it.haDomain in SUPPORTED_DOMAINS && it.entityId !in next }.forEach { d ->
            val cands = linkCandidates(d).filter { it.entityId !in next.values }
            val pick = when {
                cands.size == 1 -> cands[0]
                cands.size > 1 -> {
                    val s0 = linkScore(d, cands[0])
                    val s1 = linkScore(d, cands[1])
                    if (s0 >= 1 && s0 > s1) cands[0] else null
                }
                else -> null
            }
            if (pick != null) { next[d.entityId] = pick.entityId; n++ }
        }
        prefs.elLinks = next
        elLinks.value = next
        mergeEntities()
        return n
    }

    /** Home Assistant の機器と ECHONET Lite の機器を1つの一覧にする（対応付けた機器は1つにまとめる） */
    private fun mergeEntities() {
        val elEnts = if (elEnabled.value) elDevices.value.filter { it.haDomain in SUPPORTED_DOMAINS } else emptyList()
        val links = elLinks.value
        val haIds = haEntityList.map { it.entityId }.toSet()
        val ha = haEntityList.map { h ->
            val d = links.entries.firstOrNull { it.value == h.entityId }?.key?.let { id -> elEnts.firstOrNull { it.entityId == id } }
            if (d != null && useEchonet(d)) {
                // ECHONET Lite で操作するときは、機器から直接読んだ状態を出す（名前と ID は Home Assistant のもの）
                val x = d.toEntity()
                val a = JSONObject(x.attributes.toString()).put("friendly_name", h.name).put("via", "echonet")
                Entity(h.entityId, x.state, a, lastChanged = h.lastChanged, lastUpdated = x.lastUpdated)
            } else h
        }
        // 対応付けていない（または対応する Home Assistant の機器が無い）ECHONET Lite の機器はそのまま
        val el = elEnts.filter { links[it.entityId]?.let { id -> id !in haIds } ?: true }.map { it.toEntity() }
        val cams = ipCameras.value.map { it.toEntity() }
        allEntities.value = (ha + el + cams)
            .sortedWith(compareBy({ SUPPORTED_DOMAINS.indexOf(it.domain) }, { it.name }))
    }

    fun elEntity(entityId: String): ElDevice? = elDevices.value.firstOrNull { it.entityId == entityId }

    private fun startEchonet() {
        if (!elEnabled.value) return
        try {
            echonet.restore(EchonetClient.fromJson(prefs.elDevicesJson))
        } catch (e: Exception) {
            addLog("ECHONET：保存した機器を読めませんでした ${e.message}")
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                echonet.start()
            } catch (e: Exception) {
                elStatus.value = "ECHONET Lite を開始できませんでした：${e.message}"
            }
        }
        viewModelScope.launch {
            echonet.devices.collect { map ->
                elDevices.value = map.values.sortedWith(compareBy({ it.cls.kind.ordinal }, { it.name }))
                elLive.value = elLive(map.values)
                mergeEntities()
            }
        }
        viewModelScope.launch { echonet.status.collect { elStatus.value = it } }
        viewModelScope.launch { echonet.diagnostics.collect { elDiagnostics.value = it } }
        // 家の中・外出先が変わったら、操作の経路を切り替える
        viewModelScope.launch { route.collect { mergeEntities() } }
        if (!solarStarted) {
            solarStarted = true
            startSolarHeat()
        }
        // 30秒ごとに状態を読み直す（機器からの通知があればすぐ反映）
        viewModelScope.launch {
            while (isActive && elEnabled.value) {
                if (foreground) echonet.refreshAll()
                delay(30_000)
            }
        }
    }

    private fun saveElDevices() {
        prefs.elDevicesJson = echonet.toJson()
    }

    fun setElEnabled(on: Boolean) {
        prefs.elEnabled = on
        elEnabled.value = on
        if (on) {
            startEchonet()
            if (elDevices.value.isEmpty()) discoverEchonet()
        } else {
            echonet.stop()
            mergeEntities()
        }
    }

    fun discoverEchonet(sweep: Boolean = false) {
        if (elBusy.value) return
        viewModelScope.launch {
            elBusy.value = true
            try {
                val names = elDevices.value.associate { it.key to it.name }
                echonet.discover(elManualIps.value.toList(), names, sweep)
                saveElDevices()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                elStatus.value = "機器を探せませんでした：${e.message}"
                elDiagnostics.value = "機器を探している途中でエラーになりました：${e.message ?: e.javaClass.simpleName}"
            } finally {
                elBusy.value = false
            }
        }
    }

    fun addElIp(ip: String): String? {
        val v = ip.trim()
        // 数字だけの IP アドレスに限る（名前の問い合わせを画面の処理中にしないため）
        if (!Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(v)) return "IPアドレス（例：192.168.1.50）を入力してください"
        val addr = runCatching { java.net.InetAddress.getByName(v) }.getOrNull() ?: return "IPアドレスが正しくありません"
        if (!NetworkGuard.isLocal(addr)) return "家の中のアドレスを入力してください"
        val next = elManualIps.value + v
        prefs.elManualIps = next
        elManualIps.value = next
        discoverEchonet()
        return null
    }

    /** 1台だけをくわしく調べる */
    fun probeEl(ip: String) {
        if (elBusy.value) return
        viewModelScope.launch {
            elBusy.value = true
            try {
                elDiagnostics.value = echonet.probe(ip)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                elDiagnostics.value = "【$ip を調べた結果】\n調べられませんでした：${e.message}"
            } finally {
                elBusy.value = false
            }
        }
    }

    fun removeElIp(ip: String) {
        val next = elManualIps.value - ip
        prefs.elManualIps = next
        elManualIps.value = next
    }

    fun renameEl(key: String, name: String) {
        if (name.isBlank()) return
        echonet.rename(key, name.trim())
        saveElDevices()
    }

    fun removeEl(key: String) {
        echonet.remove(key)
        saveElDevices()
    }

    // ================= エネルギーの収支 =================

    val energy = MutableStateFlow<EnergyReport?>(null)
    val energyStatus = MutableStateFlow<String?>(null)

    /** Home Assistant の「エネルギー」の設定と記録から、今日・昨日・今月の収支を計算する */
    fun loadEnergy() {
        val c = client ?: return
        viewModelScope.launch {
            try {
                val prefsJ = c.request(JSONObject().put("type", "energy/get_prefs")).optJSONObject("result") ?: JSONObject()
                val src = parseEnergyPrefs(prefsJ)
                if (src.isEmpty) {
                    energy.value = null
                    energyStatus.value = "Home Assistant の「設定 → ダッシュボード → エネルギー」が未設定です"
                    return@launch
                }
                val ids = JSONArray(src.all)
                val meta = c.request(JSONObject().put("type", "recorder/get_statistics_metadata").put("statistic_ids", ids))
                    .optJSONArray("result") ?: JSONArray()
                val units = (0 until meta.length()).mapNotNull { meta.optJSONObject(it) }
                    .associate { it.optString("statistic_id") to it.optString("statistics_unit_of_measurement") }
                val factor = { id: String -> unitFactor(units[id]) }

                val zone = ZoneId.systemDefault()
                val today = LocalDate.now().atStartOfDay(zone)
                suspend fun stats(start: java.time.ZonedDateTime, end: java.time.ZonedDateTime, period: String): JSONObject =
                    c.request(
                        JSONObject()
                            .put("type", "recorder/statistics_during_period")
                            .put("start_time", start.toOffsetDateTime().toString())
                            .put("end_time", end.toOffsetDateTime().toString())
                            .put("statistic_ids", ids)
                            .put("period", period)
                            .put("types", JSONArray().put("change"))
                    ).optJSONObject("result") ?: JSONObject()

                val now = java.time.ZonedDateTime.now(zone)
                val todayHours = stats(today, now.plusHours(1), "hour")
                val yesterday = stats(today.minusDays(1), today, "day")
                val month = stats(today.withDayOfMonth(1), now.plusHours(1), "day")

                val devices = src.devices.map { id -> id to sumChanges(todayHours, listOf(id), factor) }
                    .filter { it.second > 0.0 }.sortedByDescending { it.second }.take(5)
                energy.value = EnergyReport(
                    today = totalsOf(todayHours, src, factor),
                    yesterday = totalsOf(yesterday, src, factor),
                    month = totalsOf(month, src, factor),
                    hourlyUsed = hourlyUsed(todayHours, src, factor, today.toInstant().toEpochMilli(), 24),
                    topDevices = devices,
                    hasSolar = src.solar.isNotEmpty(),
                    hasBattery = src.batteryOut.isNotEmpty() || src.batteryIn.isNotEmpty(),
                    hasExport = src.gridOut.isNotEmpty(),
                )
                energyStatus.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                energyStatus.value = "エネルギーの記録を読めませんでした：${e.message}"
            }
        }
    }

    // ================= おひさま沸き上げ（エコキュート） =================
    //
    // ・毎日、決まった時刻（初期値 20 時）に翌日の天気予報を見て、晴れならエコキュートの「エネルギーシフト」に参加させ、
    //   夜の沸き上げを昼（設定した時刻）にずらす。晴れでなければ参加をやめ、ふつうに夜に沸かす。
    //   設定したあとに読み直して確かめ、うまくいかなければ 10 分ごとに自動でやり直す（手動でもやり直せる）。
    //   朝 7 時にも、前の晩の設定が残っているか確かめる。
    // ・昼の時間帯は売電の量をリアルタイムに見て（スマートメーターなら 15 秒ごと）、余っていれば沸き増しを始め、
    //   電気を買い始めたら自動の沸き上げに戻す。
    // ・お湯の残りが少ないときは止めない（自動に戻すだけで、沸き上げを止める操作はしない）。

    data class SolarHeatView(
        val device: ElDevice?,
        val remainL: Int?, val tankL: Int?, val heating: Boolean?, val manual: Boolean?,
        val shift: Boolean?, val shiftHour: Int?,
        val surplusW: Double?, val sensorName: String?,
        val canManual: Boolean, val canShift: Boolean,
        val status: String,
        val plan: SolarPlan?,
        val history: List<Pair<Long, Double>>,
    )

    /** 毎日の設定の結果 */
    data class SolarPlan(val date: String, val ok: Boolean, val message: String, val at: Long, val sunny: Boolean?)

    val shEnabled = MutableStateFlow(prefs.shEnabled)
    val shDevice = MutableStateFlow(prefs.shDevice)
    val shStart = MutableStateFlow(prefs.shStartHour)
    val shEnd = MutableStateFlow(prefs.shEndHour)
    val shThreshold = MutableStateFlow(prefs.shThresholdW)
    val shSensor = MutableStateFlow(prefs.shSensor)
    val shInvert = MutableStateFlow(prefs.shInvert)
    val shShift = MutableStateFlow(prefs.shShift)
    val shPlanHour = MutableStateFlow(prefs.shPlanHour)
    /** 余剰電力をどこから読むか：auto（スマートメーター→Home Assistant）/ meter / ha */
    val shSource = MutableStateFlow(prefs.shSource)
    val shLog = MutableStateFlow(prefs.shLog.split("\n").filter { it.isNotBlank() })
    val solarHeat = MutableStateFlow<SolarHeatView?>(null)

    private var shByUs = false            // いまの沸き増しはこのアプリが始めたもの
    private var shOverSec = 0             // 余剰が続いている秒数
    private var shBuySec = 0              // 沸き増し中に電気を買っている秒数
    private var shUnderSec = 0            // 沸き増し中に売電がほぼ無い秒数
    private var shStatus = "準備中"
    private var shRetryAt = 0L            // 毎日の設定に失敗したとき、次にやり直す時刻
    private var shMorningChecked = ""
    private var gridOutIds: List<String>? = null
    private val surplusSamples = ArrayDeque<Pair<Long, Double>>() // 電力量センサーから余剰電力を計算するための記録
    private val surplusHistory = ArrayDeque<Pair<Long, Double>>() // 画面のグラフ用（直近 1 時間）
    private var surplusFrom: String? = null
    private val solarWake = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)

    private fun loadPlan(): SolarPlan? = try {
        val j = JSONObject(prefs.shPlanJson ?: "")
        SolarPlan(j.optString("date"), j.optBoolean("ok"), j.optString("message"), j.optLong("at"),
            if (j.has("sunny")) j.optBoolean("sunny") else null)
    } catch (e: Exception) { null }

    private fun savePlan(p: SolarPlan) {
        prefs.shPlanJson = JSONObject().put("date", p.date).put("ok", p.ok).put("message", p.message).put("at", p.at)
            .apply { p.sunny?.let { put("sunny", it) } }.toString()
    }

    fun setSolarHeat(
        enabled: Boolean? = null, device: String? = null, start: Int? = null, end: Int? = null,
        threshold: Int? = null, sensor: String? = null, invert: Boolean? = null, shift: Boolean? = null,
        planHour: Int? = null, source: String? = null,
    ) {
        enabled?.let { prefs.shEnabled = it; shEnabled.value = it }
        device?.let { prefs.shDevice = it; shDevice.value = it }
        start?.let { prefs.shStartHour = it; shStart.value = it }
        end?.let { prefs.shEndHour = it; shEnd.value = it }
        threshold?.let { prefs.shThresholdW = it; shThreshold.value = it }
        sensor?.let { prefs.shSensor = it; shSensor.value = it; surplusSamples.clear() }
        invert?.let { prefs.shInvert = it; shInvert.value = it }
        shift?.let { prefs.shShift = it; shShift.value = it }
        planHour?.let { prefs.shPlanHour = it; shPlanHour.value = it }
        source?.let { prefs.shSource = it; shSource.value = it; surplusSamples.clear(); surplusHistory.clear() }
        if (enabled == false && shByUs) viewModelScope.launch { ecoAuto("おひさま沸き上げをオフにしたため") }
        solarWake.trySend(Unit)
        updateSolarView()
    }

    private fun shAddLog(msg: String) {
        val t = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("M/d H:mm"))
        val next = (listOf("$t　$msg") + shLog.value).take(60)
        shLog.value = next
        prefs.shLog = next.joinToString("\n")
        addLog("おひさま：$msg")
    }

    /** 使うエコキュート（選んでいなければ最初に見つかったもの） */
    private fun ecoDevice(): ElDevice? {
        val list = elDevices.value.filter { it.cls.kind == ElKind.WATER_HEATER }
        return list.firstOrNull { it.key == shDevice.value } ?: list.firstOrNull()
    }

    val waterHeaters: List<ElDevice> get() = elDevices.value.filter { it.cls.kind == ElKind.WATER_HEATER }

    /** スマートメーター（瞬時電力 0xE7 が読めるもの） */
    private fun meterDevice(): ElDevice? = elDevices.value.firstOrNull { it.cls.kind == ElKind.METER && (it.getMap.isEmpty() || 0xE7 in it.getMap) }

    val hasMeter: Boolean get() = meterDevice() != null

    private fun ElDevice.canSet(epc: Int) = setMap.isEmpty() || epc in setMap

    /** 余剰電力のセンサーの候補（電力 W/kW と、電力量 kWh） */
    fun surplusSensorCandidates(): List<Entity> = allStates.value.values.filter { e ->
        e.domain == "sensor" && e.attributes.optString("unit_of_measurement").lowercase() in setOf("w", "kw", "wh", "kwh")
    }.sortedBy { it.name }

    private fun surplusSensorId(): String? = shSensor.value.ifBlank { gridOutIds?.firstOrNull() }

    /** スマートメーターの瞬時電力から余剰電力（W）。買電がプラス・売電がマイナスなので向きを逆にする */
    private fun meterSurplusW(): Double? {
        val m = meterDevice() ?: return null
        if (!m.online || System.currentTimeMillis() - m.updatedAt > 120_000) return null
        val w = m.props[0xE7]?.s32()?.takeIf { it != 0x7FFFFFFEL && it != -0x7FFFFFFFL } ?: return null
        return -w.toDouble()
    }

    /** Home Assistant のセンサーから余剰電力（W） */
    private fun haSurplusW(): Double? {
        val id = surplusSensorId() ?: return null
        val e = allStates.value[id] ?: return null
        val v = e.state.toDoubleOrNull() ?: return null
        val sign = if (shInvert.value) -1.0 else 1.0
        return when (e.attributes.optString("unit_of_measurement").lowercase()) {
            "w" -> v * sign
            "kw" -> v * 1000 * sign
            "wh", "kwh" -> {
                // 売電の電力量（増えていく値）から、最近の平均の電力を計算する
                val kwh = if (e.attributes.optString("unit_of_measurement").lowercase() == "wh") v / 1000 else v
                val now = System.currentTimeMillis()
                if (surplusSamples.lastOrNull()?.second != kwh || surplusSamples.isEmpty()) surplusSamples.addLast(now to kwh)
                while (surplusSamples.size > 2 && now - surplusSamples.first().first > 40 * 60_000L) surplusSamples.removeFirst()
                val first = surplusSamples.first()
                val hours = (now - first.first) / 3_600_000.0
                // スマートメーターは30分ごとにしか増えないことがあるので、5分以上の記録で計算する
                if (hours < 5.0 / 60) null else ((kwh - first.second) / hours * 1000).coerceAtLeast(0.0)
            }
            else -> null
        }
    }

    /** いまの余剰電力（W）。プラス = 売電（余っている）、マイナス = 買電。分からなければ null */
    private fun readSurplusW(): Double? {
        val v = when (shSource.value) {
            "meter" -> meterSurplusW().also { surplusFrom = "スマートメーター（ECHONET Lite）" }
            "ha" -> haSurplusW().also { surplusFrom = surplusSensorId()?.let { allStates.value[it]?.name ?: it } }
            else -> meterSurplusW()?.also { surplusFrom = "スマートメーター（ECHONET Lite）" }
                ?: haSurplusW().also { surplusFrom = surplusSensorId()?.let { allStates.value[it]?.name ?: it } }
        }
        if (v != null) {
            val now = System.currentTimeMillis()
            surplusHistory.addLast(now to v)
            while (surplusHistory.isNotEmpty() && now - surplusHistory.first().first > 60 * 60_000L) surplusHistory.removeFirst()
        }
        return v
    }

    private fun forecastSunny(day: LocalDate): Boolean? {
        val f = forecast.value.firstOrNull { j ->
            runCatching { java.time.OffsetDateTime.parse(j.optString("datetime")).atZoneSameInstant(ZoneId.systemDefault()).toLocalDate() }
                .getOrNull() == day
        } ?: return null
        return f.optString("condition") in setOf("sunny", "partlycloudy", "clear-night")
    }

    private suspend fun ecoSet(d: ElDevice, props: List<Prop>, what: String): Boolean {
        val err = echonet.setWaterHeater(d.key, props)
        if (err != null) shAddLog("$what できませんでした（$err）") else shAddLog(what)
        return err == null
    }

    /** 自動の沸き上げに戻す（このアプリが始めた沸き増しを終える） */
    private suspend fun ecoAuto(reason: String) {
        val d = ecoDevice() ?: return
        if (d.canSet(0xB0)) ecoSet(d, listOf(Prop(0xB0, bytesOf(0x41))), "自動の沸き上げに戻しました（$reason）")
        shByUs = false
    }

    fun ecoManualStart() = viewModelScope.launch {
        val d = ecoDevice() ?: return@launch
        if (d.canSet(0xC0)) echonet.setWaterHeater(d.key, listOf(Prop(0xC0, bytesOf(0x41))))
        if (ecoSet(d, listOf(Prop(0xB0, bytesOf(0x42))), "手動で沸き増しを始めました")) shByUs = false
        updateSolarView()
    }

    fun ecoBackToAuto() = viewModelScope.launch {
        ecoAuto("手動の操作")
        updateSolarView()
    }

    val ecoDiag = MutableStateFlow<String?>(null)

    /** エコキュートが何に対応しているか調べる（画面のボタン） */
    fun diagnoseEco() = viewModelScope.launch {
        val d = ecoDevice() ?: run { ecoDiag.value = "エコキュートが見つかりません"; return@launch }
        ecoDiag.value = "調べています…"
        ecoDiag.value = echonet.diagnoseWaterHeater(d.key)
        updateSolarView()
    }

    /** 部屋の名前（Home Assistant の機器） */
    fun roomNameOf(entityId: String): String? {
        val k = roomKey(entityId)
        return rooms.value.firstOrNull { it.key == k }?.name
    }

    /** 毎日の設定を今すぐやり直す（画面のボタン） */
    fun retrySolarPlan() = viewModelScope.launch {
        shAddLog("手動で設定をやり直します")
        runSolarPlan(manual = true)
        updateSolarView()
    }

    /**
     * 毎日の設定：対象の日（夜の沸き上げのあとに来る日）の天気で、エネルギーシフトに参加するか決めて設定し、読み直して確かめる。
     * 夜中の 0〜4 時に実行したときは「今日」、それ以外は「明日」が対象。
     */
    private fun planTarget(): LocalDate = if (java.time.LocalTime.now().hour < 5) LocalDate.now() else LocalDate.now().plusDays(1)

    private suspend fun runSolarPlan(manual: Boolean = false): Boolean {
        val target = planTarget()
        val now = System.currentTimeMillis()
        fun result(ok: Boolean, msg: String, sunny: Boolean?): Boolean {
            // date は「どの日のための設定か」
            savePlan(SolarPlan(target.toString(), ok, msg, now, sunny))
            shRetryAt = if (ok) 0L else now + 10 * 60_000L
            shAddLog((if (ok) "✓ " else "✕ ") + msg)
            return ok
        }
        if (!shShift.value) return result(true, "夜の沸き上げを昼にずらす設定はオフです（いつもどおり夜に沸かします）", null)
        val d = ecoDevice() ?: return result(false, "エコキュートが見つかりません", null)
        if (!d.canSet(0xC7)) return result(false, "このエコキュートは昼間沸き上げ（エネルギーシフト）に対応していません", null)
        val dayName = if (target == LocalDate.now()) "今日" else "明日"
        val sunny = forecastSunny(target) ?: return result(false, "${dayName}の天気予報がまだありません（天気の連携を確認してください）", null)
        val props = mutableListOf<Prop>()
        if (sunny) {
            props += Prop(0xC7, bytesOf(0x41))
            if (d.canSet(0xC9)) props += Prop(0xC9, bytesOf(0x01))
            if (d.canSet(0xCA)) props += Prop(0xCA, bytesOf(shStart.value.coerceIn(9, 17)))
        } else {
            props += Prop(0xC7, bytesOf(0x42))
            if (d.canSet(0xCA)) props += Prop(0xCA, bytesOf(0x00))
        }
        // 1項目ずつ設定する。必須は「エネルギーシフト参加」だけ。回数・時刻は断られても、機種の決まった時刻で動く
        val res = echonet.setWaterHeaterEach(d.key, props)
        val mainErr = res[0xC7]
        if (mainErr != null) return result(false, "設定できませんでした（${waterHeaterEpcName(0xC7)}：$mainErr）" + if (!manual) "（10分後にもう一度試します）" else "", sunny)
        val sub = res.filter { it.key != 0xC7 && it.value != null }
        if (sub.isNotEmpty()) shAddLog("補足：" + sub.entries.joinToString("、") { "${waterHeaterEpcName(it.key)}は${it.value}" } + "（エコキュートの決まった時刻で沸かします）")
        // 読み直して、本当に設定されたか確かめる
        delay(1500)
        echonet.refresh(d.key)
        val back = ecoDevice()?.props?.get(0xC7)?.u8()
        val want = if (sunny) 0x41 else 0x42
        if (back != null && back != want) return result(false, "設定したはずの内容が反映されていません（エコキュートの応答：${"%02X".format(back)}）", sunny)
        return result(
            true,
            if (sunny) "${dayName}は晴れの予報：夜の沸き上げを減らして、${shStart.value}時から昼に沸かす設定にしました"
            else "${dayName}は晴れない予報：いつもどおり夜に沸かす設定にしました",
            sunny,
        )
    }

    private fun startSolarHeat() {
        viewModelScope.launch {
            while (isActive) {
                try {
                    solarTick()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    shStatus = "エラー：${e.message}"
                }
                updateSolarView()
                // 昼の時間帯はリアルタイムに（15 秒ごと）、それ以外は 1 分ごと
                val h = java.time.LocalTime.now().hour
                val fast = shEnabled.value && h >= shStart.value && h < shEnd.value
                withTimeoutOrNull(if (fast) 15_000L else 60_000L) { solarWake.receive() }
            }
        }
    }

    private suspend fun solarTick() {
        if (!elEnabled.value) { shStatus = "ECHONET Lite がオフです（⚙ 設定 →「ECHONET Lite」）"; return }
        val d0 = ecoDevice() ?: run { shStatus = "エコキュートが見つかりません（⚙ 設定 →「ECHONET Lite」で「機器を探す」）"; return }
        val now = java.time.LocalTime.now()
        val inWindow = now.hour >= shStart.value && now.hour < shEnd.value
        if (inWindow || System.currentTimeMillis() - d0.updatedAt > 55_000) echonet.refresh(d0.key)
        meterDevice()?.let { if (shEnabled.value && inWindow) echonet.refresh(it.key) }
        if (gridOutIds == null) {
            client?.let { c ->
                runCatching {
                    gridOutIds = parseEnergyPrefs(c.request(JSONObject().put("type", "energy/get_prefs")).optJSONObject("result") ?: JSONObject()).gridOut
                }
            }
        }
        val surplus = readSurplusW()
        if (!shEnabled.value) { shStatus = "おひさま沸き上げはオフです"; return }
        val d = ecoDevice() ?: return
        val today = LocalDate.now().toString()

        // 1. 毎日の設定（決まった時刻のあと1回。失敗したら10分ごとにやり直す。0〜4時の間も、まだならやる）
        val plan = loadPlan()
        val target = planTarget().toString()
        val due = (now.hour >= shPlanHour.value || now.hour < 5) &&
            (plan?.date != target || (!plan.ok && System.currentTimeMillis() >= shRetryAt))
        if (due) runSolarPlan()
        // 朝 7 時：前の晩の設定がそのまま残っているか確かめる
        if (now.hour == 7 && shMorningChecked != today && shShift.value && d.canSet(0xC7)) {
            shMorningChecked = today
            val last = loadPlan()?.takeIf { it.date == today }
            val c7 = d.props[0xC7]?.u8()
            if (last?.sunny != null && c7 != null) {
                val want = if (last.sunny) 0x41 else 0x42
                if (c7 == want) shAddLog("朝の確認：昨夜の設定どおりです")
                else {
                    shAddLog("朝の確認：昨夜の設定が変わっています。設定し直します")
                    runSolarPlan()
                }
            }
        }

        // 2. 昼：売電をリアルタイムに見て沸き増し
        val heating = d.props[0xB2]?.u8()?.let { it == 0x41 }
        val remain = d.props[0xE1]?.u16()
        val tank = d.props[0xE2]?.u16()
        val full = remain != null && tank != null && tank > 0 && remain * 100 / tank >= 95
        val th = shThreshold.value
        val step = 15 // この関数は昼は 15 秒ごとに呼ばれる
        if (!inWindow) {
            shOverSec = 0; shBuySec = 0; shUnderSec = 0
            if (shByUs) ecoAuto("昼の時間帯が終わったため")
            shStatus = "待機中（${shStart.value}時〜${shEnd.value}時に、余った電気で沸かします）"
            return
        }
        if (surplus == null) {
            shStatus = "余った電気の量が分かりません（⚙ 設定 →「おひさま沸き上げ」で読み取り先を選んでください）"
            return
        }
        val kw = { w: Double -> String.format("%.1f", w / 1000) }
        if (!shByUs) {
            shOverSec = if (surplus >= th) shOverSec + step else 0
            shStatus = "昼の時間帯：売電 ${kw(surplus.coerceAtLeast(0.0))}kW（${kw(th.toDouble())}kW 以上が2分続くと沸き増し）"
            if (shOverSec >= 120 && heating != true && !full && d.canSet(0xB0)) {
                if (d.canSet(0xC0)) echonet.setWaterHeater(d.key, listOf(Prop(0xC0, bytesOf(0x41))))
                if (ecoSet(d, listOf(Prop(0xB0, bytesOf(0x42))), "売電 ${kw(surplus)}kW：余った電気で沸き増しを始めました")) {
                    shByUs = true; shBuySec = 0; shUnderSec = 0
                }
            }
        } else {
            // 沸き増し中はエコキュートが電気を使うので、売電が減る。電気を買い始めたら（または売電がほぼ無い状態が続いたら）終える
            shBuySec = if (surplus < -300) shBuySec + step else 0
            shUnderSec = if (surplus < 100) shUnderSec + step else 0
            shStatus = "余った電気で沸き増し中（" + (if (surplus >= 0) "売電 ${kw(surplus)}kW" else "買電 ${kw(-surplus)}kW") + "）"
            when {
                full -> ecoAuto("お湯がいっぱいになったため")
                shBuySec >= 180 -> ecoAuto("電気を買い始めたため")
                surplusFrom?.contains("スマートメーター") != true && shUnderSec >= 900 -> ecoAuto("余った電気が無くなったため")
            }
        }
    }

    private fun updateSolarView() {
        val d = ecoDevice()
        solarHeat.value = SolarHeatView(
            device = d,
            remainL = d?.props?.get(0xE1)?.u16(), tankL = d?.props?.get(0xE2)?.u16(),
            heating = d?.props?.get(0xB2)?.u8()?.let { it == 0x41 },
            manual = d?.props?.get(0xB0)?.u8()?.let { it == 0x42 },
            shift = d?.props?.get(0xC7)?.u8()?.let { it == 0x41 },
            shiftHour = d?.props?.get(0xCA)?.u8()?.takeIf { it in 1..23 },
            surplusW = surplusHistory.lastOrNull()?.takeIf { System.currentTimeMillis() - it.first < 120_000 }?.second,
            sensorName = surplusFrom ?: surplusSensorId()?.let { allStates.value[it]?.name ?: it },
            canManual = d?.canSet(0xB0) == true, canShift = d?.canSet(0xC7) == true,
            status = shStatus,
            plan = loadPlan(),
            history = surplusHistory.toList(),
        )
    }

    // ================= 設定の書き出し・読み込み =================

    /** Activity にファイルの保存・選択の画面を開いてもらう（"export" / "import"） */
    val fileRequest = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val backupMessage = MutableStateFlow<String?>(null)

    fun requestFile(kind: String) {
        backupMessage.value = null
        fileRequest.tryEmit(kind)
    }

    fun exportSettings(): String = prefs.exportJson()

    /** 読み込めたら true（その後アプリを再起動して反映する） */
    fun importSettings(text: String): Boolean = try {
        prefs.importJson(text)
        backupMessage.value = "設定を読み込みました。アプリを再起動します…"
        true
    } catch (e: Exception) {
        backupMessage.value = "読み込めませんでした：${e.message}"
        false
    }

    fun onExported(ok: Boolean, err: String? = null) {
        backupMessage.value = if (ok) "設定を書き出しました" else "書き出せませんでした：$err"
    }

    // ================= 機器の動作の詳細 =================

    data class HistoryItem(val time: java.time.LocalDateTime, val state: String)

    val infoFor = MutableStateFlow<String?>(null)
    val history = MutableStateFlow<List<HistoryItem>?>(null)
    val historyStatus = MutableStateFlow<String?>(null)

    fun showInfo(entityId: String) {
        onUserInteraction()
        infoFor.value = entityId
        history.value = null
        if (isDirectCamera(entityId)) {
            historyStatus.value = "直接つないでいるカメラは、履歴を記録していません"
            return
        }
        if (isEchonetEntity(entityId)) {
            historyStatus.value = "ECHONET Lite で直接つないでいる機器は、履歴を記録していません"
            return
        }
        historyStatus.value = "履歴を読み込んでいます…"
        viewModelScope.launch { loadHistory(entityId) }
    }

    fun hideInfo() {
        infoFor.value = null
    }

    /** 直近24時間の状態の変化（Home Assistant の履歴。通常のログインで読める） */
    private suspend fun loadHistory(entityId: String) {
        val r = route.value ?: return
        val local = store.baseUrl ?: return
        try {
            val zone = ZoneId.systemDefault()
            val now = java.time.ZonedDateTime.now(zone)
            val start = java.net.URLEncoder.encode(now.minusHours(24).toOffsetDateTime().toString(), "UTF-8")
            val end = java.net.URLEncoder.encode(now.toOffsetDateTime().toString(), "UTF-8")
            val token = validAccessToken(r.base, local)
            val url = "${r.base}/api/history/period/$start?filter_entity_id=$entityId&end_time=$end&minimal_response&no_attributes"
            val body = withContext(Dispatchers.IO) {
                http.newCall(Request.Builder().url(url).header("Authorization", "Bearer $token").build()).execute().use { resp ->
                    if (!resp.isSuccessful) throw HaException("履歴を読めませんでした (HTTP ${resp.code})")
                    resp.body?.string().orEmpty()
                }
            }
            val outer = JSONArray(body)
            val arr = if (outer.length() > 0) outer.optJSONArray(0) ?: JSONArray() else JSONArray()
            val items = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.mapNotNull { j ->
                val t = j.optString("last_changed").ifBlank { j.optString("last_updated") }
                val time = runCatching { java.time.OffsetDateTime.parse(t).atZoneSameInstant(zone).toLocalDateTime() }.getOrNull()
                    ?: return@mapNotNull null
                HistoryItem(time, j.optString("state"))
            }
            // 同じ状態が続いている記録はまとめる
            val merged = items.fold(mutableListOf<HistoryItem>()) { acc, it ->
                if (acc.isEmpty() || acc.last().state != it.state) acc.add(it)
                acc
            }
            if (infoFor.value == entityId) {
                history.value = merged.reversed()
                historyStatus.value = if (merged.isEmpty()) "直近24時間の記録はありません" else null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (infoFor.value == entityId) historyStatus.value = e.message ?: "履歴を読めませんでした"
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

    private fun loadDemo() {
        val m = Demo.states()
        allStates.value = m
        haAreas.value = Demo.areas
        entityAreas.value = Demo.entityAreas
        haEntityList = m.values.filter { it.domain in SUPPORTED_DOMAINS }
        mergeEntities()
        automations.value = m.values.filter { it.domain == "automation" }
        updateThermos(m)
        weatherEntities.value = listOfNotNull(m["weather.home"])
        weather.value = m["weather.home"]
        forecast.value = Demo.forecast()
        calendarEntities.value = listOfNotNull(m["calendar.timetree_family"])
        events.value = Demo.events()
        energy.value = Demo.energy()
        userName.value = "デモ"
        isAdmin.value = false
        if (timers.value.isEmpty()) addTimer(5 * 60L, "パスタ")
        route.value = Route("http://demo.invalid", false)
        conn.value = Conn.CONNECTED
        screen.value = Screen.Home
    }

    /*
     * 起動処理。Kotlin は上から順に準備するので、すべての値の準備が終わった
     * このクラスの最後で動かす（途中に置くと、まだ準備できていない値を読んで止まる）
     */
    init {
        // 開発用のビルドの「デモ表示」：Home Assistant につながず、見本のデータで画面を出す（画面の確認用）
        if (BuildConfig.DEBUG && prefs.demoMode) loadDemo() else start()
        startEchonet()
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
                loadEnergy()
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

    override fun onCleared() {
        if (elEnabled.value) echonet.close()
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
