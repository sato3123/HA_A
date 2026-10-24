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

    /** 使い方を一度表示したか */
    var helpShown: Boolean
        get() = p.getBoolean("help_shown", false)
        set(v) = p.edit().putBoolean("help_shown", v).apply()

    /** 天気に使う weather エンティティ。null = 最初に見つかったもの */
    var weatherEntityId: String?
        get() = p.getString("weather_entity", null)
        set(v) = p.edit().putString("weather_entity", v).apply()
}
