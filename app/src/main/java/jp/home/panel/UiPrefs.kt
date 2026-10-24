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

    /** 使い方を一度表示したか */
    var helpShown: Boolean
        get() = p.getBoolean("help_shown", false)
        set(v) = p.edit().putBoolean("help_shown", v).apply()

    /** 天気に使う weather エンティティ。null = 最初に見つかったもの */
    var weatherEntityId: String?
        get() = p.getString("weather_entity", null)
        set(v) = p.edit().putString("weather_entity", v).apply()
}
