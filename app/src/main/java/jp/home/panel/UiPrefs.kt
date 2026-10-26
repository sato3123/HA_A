package jp.home.panel

import android.content.Context

/** 画面設定（秘密情報ではないので通常の SharedPreferences） */
class UiPrefs(context: Context) {
    private val p = context.getSharedPreferences("ui_prefs", Context.MODE_PRIVATE)

    /** 表示する機器。null = まだ選んでいない（すべて表示） */
    var selected: Set<String>?
        get() = if (p.getBoolean("configured", false)) p.getStringSet("selected", emptySet())!!.toSet() else null
        set(v) {
            p.edit()
                .putBoolean("configured", v != null)
                .putStringSet("selected", v ?: emptySet())
                .apply()
        }

    /** 呼びかけで反応する（常時待ち受け） */
    var wakeEnabled: Boolean
        get() = p.getBoolean("wake_enabled", false)
        set(v) = p.edit().putBoolean("wake_enabled", v).apply()

    /** 使う音声アシスタント（パイプライン）。null = Home Assistant の優先設定 */
    var pipelineId: String?
        get() = p.getString("pipeline_id", null)
        set(v) = p.edit().putString("pipeline_id", v).apply()

    /** 配色パターン */
    var themeId: String
        get() = p.getString("theme", "orange") ?: "orange"
        set(v) = p.edit().putString("theme", v).apply()

    /** 部屋ごとに分けて表示する */
    var groupByRoom: Boolean
        get() = p.getBoolean("group_by_room", true)
        set(v) = p.edit().putBoolean("group_by_room", v).apply()

    /** ホーム画面で絞り込んでいる部屋（null = すべて） */
    var roomFilter: String?
        get() = p.getString("room_filter", null)
        set(v) = p.edit().putString("room_filter", v).apply()

    /** アプリで追加した部屋（key -> 名前） */
    var customRooms: Map<String, String>
        get() = readMap("custom_rooms")
        set(v) = writeMap("custom_rooms", v)

    /** 機器ごとの部屋の割り当て（entity_id -> 部屋の key）。Home Assistant のエリアより優先 */
    var roomOverrides: Map<String, String>
        get() = readMap("room_overrides")
        set(v) = writeMap("room_overrides", v)

    private fun readMap(key: String): Map<String, String> = try {
        val j = org.json.JSONObject(p.getString(key, "{}") ?: "{}")
        j.keys().asSequence().associateWith { j.optString(it) }
    } catch (e: Exception) {
        emptyMap()
    }

    private fun writeMap(key: String, v: Map<String, String>) {
        val j = org.json.JSONObject()
        v.forEach { (k, x) -> j.put(k, x) }
        p.edit().putString(key, j.toString()).apply()
    }

    /** 自宅の場所（住所・地域）。場所に関わる質問のときに AI に添える */
    var homeLocation: String
        get() = p.getString("home_location", "") ?: ""
        set(v) = p.edit().putString("home_location", v).apply()

    /** ホーム画面に予定を出すカレンダー（entity_id。null = まだ選んでいない） */
    var calendars: Set<String>?
        get() = if (p.getBoolean("cal_configured", false)) p.getStringSet("calendars", emptySet())!!.toSet() else null
        set(v) = p.edit().putBoolean("cal_configured", v != null).putStringSet("calendars", v ?: emptySet()).apply()

    /** 表示のユーザー（JSON）と、いま選んでいるユーザー */
    var profilesJson: String?
        get() = p.getString("profiles", null)
        set(v) = p.edit().putString("profiles", v).apply()
    var activeProfile: String?
        get() = p.getString("active_profile", null)
        set(v) = p.edit().putString("active_profile", v).apply()

    // ---- ECHONET Lite（Home Assistant を通さない直接通信） ----
    var elEnabled: Boolean
        get() = p.getBoolean("el_enabled", false)
        set(v) = p.edit().putBoolean("el_enabled", v).apply()
    var elDevicesJson: String?
        get() = p.getString("el_devices", null)
        set(v) = p.edit().putString("el_devices", v).apply()
    var elManualIps: Set<String>
        get() = p.getStringSet("el_ips", emptySet())!!.toSet()
        set(v) = p.edit().putStringSet("el_ips", v).apply()

    /** 直接つなぐカメラ（JSON。パスワードは含まない） */
    var ipCamerasJson: String?
        get() = p.getString("ip_cameras", null)
        set(v) = p.edit().putString("ip_cameras", v).apply()

    /** カメラごとの画質（entity_id → auto / realtime / hls / mjpeg / main / sub） */
    var cameraModes: Map<String, String>
        get() = readMap("camera_modes")
        set(v) = writeMap("camera_modes", v)

    // ---- おひさま沸き上げ（エコキュート） ----
    var shEnabled: Boolean
        get() = p.getBoolean("sh_enabled", false)
        set(v) = p.edit().putBoolean("sh_enabled", v).apply()
    var shDevice: String
        get() = p.getString("sh_device", "") ?: ""
        set(v) = p.edit().putString("sh_device", v).apply()
    var shStartHour: Int
        get() = p.getInt("sh_start", 10)
        set(v) = p.edit().putInt("sh_start", v).apply()
    var shEndHour: Int
        get() = p.getInt("sh_end", 15)
        set(v) = p.edit().putInt("sh_end", v).apply()
    /** 沸き増しを始める余剰電力（W） */
    var shThresholdW: Int
        get() = p.getInt("sh_threshold", 1200)
        set(v) = p.edit().putInt("sh_threshold", v).apply()
    /** 余剰電力を読むセンサー（空 = Home Assistant のエネルギーの売電） */
    var shSensor: String
        get() = p.getString("sh_sensor", "") ?: ""
        set(v) = p.edit().putString("sh_sensor", v).apply()
    /** 電力センサーの向きを逆にする（買電がプラス・売電がマイナスのセンサー用） */
    var shInvert: Boolean
        get() = p.getBoolean("sh_invert", false)
        set(v) = p.edit().putBoolean("sh_invert", v).apply()
    /** 前の日の夜に天気予報を見て、晴れなら夜の沸き上げを昼にずらす */
    var shShift: Boolean
        get() = p.getBoolean("sh_shift", true)
        set(v) = p.edit().putBoolean("sh_shift", v).apply()
    /** おひさま沸き上げを動かす場所：local（このタブレット）/ server（ホームパネルのサーバー） */
    var shWhere: String
        get() = p.getString("sh_where", "local") ?: "local"
        set(v) = p.edit().putString("sh_where", v).apply()
    var shServer: String
        get() = p.getString("sh_server", "http://192.168.1.46") ?: "http://192.168.1.46"
        set(v) = p.edit().putString("sh_server", v).apply()

    /** 毎日の設定をする時刻（時） */
    var shPlanHour: Int
        get() = p.getInt("sh_plan_hour", 20)
        set(v) = p.edit().putInt("sh_plan_hour", v).apply()
    /** 余剰電力の読み取り先：auto / meter / ha */
    var shSource: String
        get() = p.getString("sh_source", "auto") ?: "auto"
        set(v) = p.edit().putString("sh_source", v).apply()
    /** 毎日の設定の結果（JSON） */
    var shPlanJson: String?
        get() = p.getString("sh_plan", null)
        set(v) = p.edit().putString("sh_plan", v).apply()
    var shLog: String
        get() = p.getString("sh_log", "") ?: ""
        set(v) = p.edit().putString("sh_log", v).apply()

    /** ECHONET Lite の機器と Home Assistant の機器の対応（ECHONET の entity_id → Home Assistant の entity_id） */
    var elLinks: Map<String, String>
        get() = readMap("el_links")
        set(v) = writeMap("el_links", v)
    /** 操作の経路：auto（家の中は ECHONET Lite、外出先は Home Assistant）/ ha / echonet */
    var ctlMode: String
        get() = p.getString("ctl_mode", "auto") ?: "auto"
        set(v) = p.edit().putString("ctl_mode", v).apply()

    /** 部屋の並び順（部屋の key の並び） */
    var roomOrder: List<String>
        get() = (p.getString("room_order", "") ?: "").split("\n").filter { it.isNotBlank() }
        set(v) = p.edit().putString("room_order", v.joinToString("\n")).apply()

    /** 温度計の名前（温度計の key → 名前） */
    var thermoNames: Map<String, String>
        get() = readMap("thermo_names")
        set(v) = writeMap("thermo_names", v)

    /** ホーム画面に出さない温度計 */
    var thermoHidden: Set<String>
        get() = p.getStringSet("thermo_hidden", emptySet())!!.toSet()
        set(v) = p.edit().putStringSet("thermo_hidden", v).apply()

    /** 画面確認用のデモ表示（開発用のビルドだけで使う） */
    val demoMode: Boolean get() = p.getBoolean("demo_mode", false)

    /** タイマー（JSON） */
    var timersJson: String?
        get() = p.getString("timers", null)
        set(v) = p.edit().putString("timers", v).apply()

    // ---- スクリーンセーバー ----
    var ssEnabled: Boolean
        get() = p.getBoolean("ss_enabled", false)
        set(v) = p.edit().putBoolean("ss_enabled", v).apply()
    var ssMinutes: Int
        get() = p.getInt("ss_minutes", 5)
        set(v) = p.edit().putInt("ss_minutes", v).apply()
    var ssInterval: Int
        get() = p.getInt("ss_interval", 20)
        set(v) = p.edit().putInt("ss_interval", v).apply()
    var ssAlbum: String
        get() = p.getString("ss_album", "") ?: ""
        set(v) = p.edit().putString("ss_album", v).apply()

    /**
     * 設定の書き出し（JSON）。この SharedPreferences には接続情報（ログイン・外出先用URL）は入っていない
     * （それらは暗号化した別の保存場所にある）。
     */
    fun exportJson(): String {
        val values = org.json.JSONObject()
        p.all.forEach { (k, v) ->
            val o = org.json.JSONObject()
            when (v) {
                is Boolean -> o.put("t", "b").put("v", v)
                is Int -> o.put("t", "i").put("v", v)
                is Long -> o.put("t", "l").put("v", v)
                is Float -> o.put("t", "f").put("v", v.toDouble())
                is String -> o.put("t", "s").put("v", v)
                is Set<*> -> o.put("t", "set").put("v", org.json.JSONArray(v.map { it.toString() }))
                else -> return@forEach
            }
            values.put(k, o)
        }
        return org.json.JSONObject()
            .put("app", "homepanel")
            .put("format", 1)
            .put("exportedAt", java.time.OffsetDateTime.now().toString())
            .put("settings", values)
            .toString(2)
    }

    /** 設定の読み込み。今の設定はすべて置き換える */
    fun importJson(text: String) {
        val root = org.json.JSONObject(text)
        require(root.optString("app") == "homepanel") { "ホームパネルの設定ファイルではありません" }
        val values = root.getJSONObject("settings")
        val e = p.edit().clear()
        values.keys().forEach { k ->
            val o = values.getJSONObject(k)
            when (o.optString("t")) {
                "b" -> e.putBoolean(k, o.getBoolean("v"))
                "i" -> e.putInt(k, o.getInt("v"))
                "l" -> e.putLong(k, o.getLong("v"))
                "f" -> e.putFloat(k, o.getDouble("v").toFloat())
                "s" -> e.putString(k, o.getString("v"))
                "set" -> {
                    val a = o.getJSONArray("v")
                    e.putStringSet(k, (0 until a.length()).map { a.getString(it) }.toSet())
                }
            }
        }
        e.commit()
    }

    /** 使い方を一度表示したか */
    var helpShown: Boolean
        get() = p.getBoolean("help_shown", false)
        set(v) = p.edit().putBoolean("help_shown", v).apply()

    /** 天気に使う weather エンティティ。null = 最初に見つかったもの */
    var weatherEntityId: String?
        get() = p.getString("weather_entity", null)
        set(v) = p.edit().putString("weather_entity", v).apply()
}
