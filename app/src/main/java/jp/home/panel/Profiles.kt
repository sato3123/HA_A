package jp.home.panel

import org.json.JSONArray
import org.json.JSONObject

/** ホーム画面に並べる項目 */
enum class HomeSection(val key: String, val label: String) {
    CLOCK("clock", "時計・天気（マイクボタン）"),
    CALENDAR("calendar", "予定（TimeTree）"),
    TIMERS("timers", "タイマーの残り時間"),
    AUTOMATIONS("automations", "オートメーション・シーンのすぐ実行ボタン"),
    DEVICES("devices", "機器"),
    ;

    companion object {
        fun of(key: String) = entries.firstOrNull { it.key == key }
    }
}

data class SectionPref(val section: HomeSection, val visible: Boolean)

/**
 * 表示のユーザー（家族それぞれの表示のしかた）。ログインのユーザーとは別で、この端末の中だけの設定。
 * 名前以外の個人情報は持たない。
 */
data class Profile(
    val id: String,
    val name: String,
    val sections: List<SectionPref>,
    val themeId: String,
)

fun defaultSections() = listOf(
    SectionPref(HomeSection.CLOCK, true),
    SectionPref(HomeSection.CALENDAR, true),
    SectionPref(HomeSection.TIMERS, true),
    SectionPref(HomeSection.AUTOMATIONS, false),
    SectionPref(HomeSection.DEVICES, true),
)

fun defaultProfile(themeId: String) = Profile("p_default", "みんな", defaultSections(), themeId)

/** 保存されていない項目（あとから増えた項目など）を末尾に補う */
private fun completeSections(list: List<SectionPref>): List<SectionPref> {
    val missing = defaultSections().filter { d -> list.none { it.section == d.section } }
    return list + missing
}

fun profilesToJson(list: List<Profile>): String = JSONArray().apply {
    list.forEach { p ->
        put(
            JSONObject()
                .put("id", p.id)
                .put("name", p.name)
                .put("theme", p.themeId)
                .put("sections", JSONArray().apply {
                    p.sections.forEach { put(JSONObject().put("key", it.section.key).put("visible", it.visible)) }
                })
        )
    }
}.toString()

fun profilesFromJson(s: String?): List<Profile> = try {
    val a = JSONArray(s ?: "[]")
    (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { j ->
        val secs = j.optJSONArray("sections") ?: JSONArray()
        Profile(
            id = j.optString("id"),
            name = j.optString("name"),
            themeId = j.optString("theme", "orange"),
            sections = completeSections((0 until secs.length()).mapNotNull { secs.optJSONObject(it) }.mapNotNull { o ->
                HomeSection.of(o.optString("key"))?.let { SectionPref(it, o.optBoolean("visible", true)) }
            }),
        )
    }
} catch (e: Exception) {
    emptyList()
}
