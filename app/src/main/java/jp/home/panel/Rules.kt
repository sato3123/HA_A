package jp.home.panel

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/*
 * アプリで作るオートメーション／スケジュール。
 * 実体は Home Assistant の「自動化」で、タブレットが止まっていても Home Assistant が実行する。
 * id が "hp_sched_" で始まるものをスケジュール、"hp_auto_" で始まるものをオートメーションとして扱う。
 */

val WEEKDAYS = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")
val WEEKDAY_LABEL = mapOf("mon" to "月", "tue" to "火", "wed" to "水", "thu" to "木", "fri" to "金", "sat" to "土", "sun" to "日")

sealed interface Trig {
    /** 毎日（または指定曜日）の時刻。"07:30" */
    data class Time(val at: String) : Trig
    /** 機器の状態が変わったとき */
    data class State(val entityId: String, val to: String) : Trig
    /** 日の出・日の入り。offsetMin がマイナスなら前、プラスなら後 */
    data class Sun(val event: String, val offsetMin: Int) : Trig
}

/** 実行すること。service は "light.turn_on" の形 */
data class Act(val entityId: String, val service: String, val data: Map<String, Any> = emptyMap())

data class Rule(
    val id: String,
    val name: String,
    val schedule: Boolean,
    val trigger: Trig,
    val weekdays: Set<String>,
    val after: String?,
    val before: String?,
    val actions: List<Act>,
)

const val RULE_MARK = "ホームパネルで作成"

fun newRule(schedule: Boolean) = Rule(
    id = (if (schedule) "hp_sched_" else "hp_auto_") + System.currentTimeMillis(),
    name = "",
    schedule = schedule,
    trigger = Trig.Time("07:00"),
    weekdays = emptySet(),
    after = null,
    before = null,
    actions = emptyList(),
)

fun isAppRuleId(id: String) = id.startsWith("hp_")
fun isScheduleId(id: String) = id.startsWith("hp_sched_")

private fun offsetString(min: Int): String =
    (if (min < 0) "-" else "") + String.format("%02d:%02d:00", abs(min) / 60, abs(min) % 60)

private fun parseOffset(v: Any?): Int = when (v) {
    null -> 0
    is Number -> v.toInt() / 60
    is String -> {
        val neg = v.startsWith("-")
        val parts = v.trimStart('-', '+').split(":").mapNotNull { it.toIntOrNull() }
        val m = when (parts.size) {
            3 -> parts[0] * 60 + parts[1]
            2 -> parts[0] * 60 + parts[1]
            1 -> parts[0] / 60
            else -> 0
        }
        if (neg) -m else m
    }
    is JSONObject -> v.optInt("hours") * 60 + v.optInt("minutes")
    else -> 0
}

private fun hhmm(s: String) = s.split(":").let { p ->
    if (p.size >= 2) String.format("%02d:%02d", p[0].toIntOrNull() ?: 0, p[1].toIntOrNull() ?: 0) else s
}

/** Home Assistant の自動化の設定（JSON）に変換 */
fun Rule.toConfig(): JSONObject {
    val trig = when (val t = trigger) {
        is Trig.Time -> JSONObject().put("trigger", "time").put("at", hhmm(t.at) + ":00")
        is Trig.State -> JSONObject().put("trigger", "state").put("entity_id", t.entityId).put("to", t.to)
        is Trig.Sun -> JSONObject().put("trigger", "sun").put("event", t.event).also {
            if (t.offsetMin != 0) it.put("offset", offsetString(t.offsetMin))
        }
    }
    val conds = JSONArray()
    if (weekdays.isNotEmpty() || after != null || before != null) {
        val c = JSONObject().put("condition", "time")
        if (weekdays.isNotEmpty()) c.put("weekday", JSONArray(WEEKDAYS.filter { it in weekdays }))
        after?.let { c.put("after", hhmm(it) + ":00") }
        before?.let { c.put("before", hhmm(it) + ":00") }
        conds.put(c)
    }
    val acts = JSONArray()
    actions.forEach { a ->
        val data = JSONObject()
        a.data.forEach { (k, v) -> data.put(k, v) }
        acts.put(
            JSONObject()
                .put("action", a.service)
                .put("target", JSONObject().put("entity_id", a.entityId))
                .put("data", data)
        )
    }
    return JSONObject()
        .put("id", id)
        .put("alias", name)
        .put("description", RULE_MARK)
        .put("mode", "single")
        .put("triggers", JSONArray().put(trig))
        .put("conditions", conds)
        .put("actions", acts)
}

private fun JSONObject.listOf(vararg keys: String): JSONArray? {
    for (k in keys) {
        when (val v = opt(k)) {
            is JSONArray -> return v
            is JSONObject -> return JSONArray().put(v)
        }
    }
    return null
}

private fun singleString(v: Any?): String? = when (v) {
    is String -> v
    is JSONArray -> if (v.length() == 1) v.optString(0) else null
    else -> null
}

/** Home Assistant の自動化の設定を読み込む。アプリで扱えない形なら null */
fun parseRule(id: String, j: JSONObject): Rule? {
    val triggers = j.listOf("triggers", "trigger") ?: return null
    if (triggers.length() != 1) return null
    val t = triggers.optJSONObject(0) ?: return null
    val trig: Trig = when (t.optString("trigger").ifBlank { t.optString("platform") }) {
        "time" -> Trig.Time(hhmm(singleString(t.opt("at")) ?: return null))
        "state" -> Trig.State(singleString(t.opt("entity_id")) ?: return null, t.opt("to")?.toString() ?: return null)
        "sun" -> Trig.Sun(t.optString("event", "sunset"), parseOffset(t.opt("offset")))
        else -> return null
    }

    var weekdays = emptySet<String>()
    var after: String? = null
    var before: String? = null
    val conds = j.listOf("conditions", "condition") ?: JSONArray()
    if (conds.length() > 1) return null
    if (conds.length() == 1) {
        val c = conds.optJSONObject(0) ?: return null
        if (c.optString("condition") != "time") return null
        weekdays = when (val w = c.opt("weekday")) {
            is String -> setOf(w)
            is JSONArray -> (0 until w.length()).map { w.optString(it) }.toSet()
            else -> emptySet()
        }
        after = c.optString("after").takeIf { it.isNotBlank() }?.let(::hhmm)
        before = c.optString("before").takeIf { it.isNotBlank() }?.let(::hhmm)
    }

    val actsJ = j.listOf("actions", "action") ?: return null
    val acts = mutableListOf<Act>()
    for (i in 0 until actsJ.length()) {
        val a = actsJ.optJSONObject(i) ?: return null
        val svc = a.optString("action").ifBlank { a.optString("service") }
        if (!svc.contains(".")) return null
        val data = a.optJSONObject("data") ?: a.optJSONObject("service_data") ?: JSONObject()
        val entity = singleString(a.optJSONObject("target")?.opt("entity_id"))
            ?: singleString(data.opt("entity_id"))
            ?: singleString(a.opt("entity_id"))
            ?: return null
        val map = data.keys().asSequence().filter { it != "entity_id" }.associateWith { data.get(it) }
        acts.add(Act(entity, svc, map))
    }
    return Rule(
        id = id,
        name = j.optString("alias"),
        schedule = isScheduleId(id),
        trigger = trig,
        weekdays = weekdays,
        after = after,
        before = before,
        actions = acts,
    )
}

// ---------- 操作の種類 ----------

enum class Param { BRIGHTNESS, TEMPERATURE, HVAC_MODE, POSITION }

data class OpDef(val service: String, val label: String, val param: Param? = null)

fun opsFor(domain: String): List<OpDef> = when (domain) {
    "light" -> listOf(OpDef("turn_on", "つける"), OpDef("turn_on", "明るさを指定してつける", Param.BRIGHTNESS), OpDef("turn_off", "消す"), OpDef("toggle", "切り替える"))
    "switch", "input_boolean", "fan" -> listOf(OpDef("turn_on", "オンにする"), OpDef("turn_off", "オフにする"), OpDef("toggle", "切り替える"))
    "climate" -> listOf(OpDef("set_hvac_mode", "運転モードを変える", Param.HVAC_MODE), OpDef("set_temperature", "温度を設定する", Param.TEMPERATURE), OpDef("turn_off", "停止する"))
    "cover" -> listOf(OpDef("open_cover", "開ける"), OpDef("close_cover", "閉める"), OpDef("set_cover_position", "開き具合を指定する", Param.POSITION))
    "scene", "script" -> listOf(OpDef("turn_on", "実行する"))
    else -> listOf(OpDef("turn_on", "オンにする"), OpDef("turn_off", "オフにする"))
}

val Param.key: String
    get() = when (this) {
        Param.BRIGHTNESS -> "brightness_pct"
        Param.TEMPERATURE -> "temperature"
        Param.HVAC_MODE -> "hvac_mode"
        Param.POSITION -> "position"
    }

fun Param.default(): Any = when (this) {
    Param.BRIGHTNESS -> 100
    Param.TEMPERATURE -> 25.0
    Param.HVAC_MODE -> "cool"
    Param.POSITION -> 50
}

/** 今の Act がどの操作か */
fun opOf(a: Act): OpDef? {
    val domain = a.service.substringBefore('.')
    val svc = a.service.substringAfter('.')
    val ops = opsFor(domain).filter { it.service == svc }
    return ops.firstOrNull { it.param != null && a.data.containsKey(it.param.key) } ?: ops.firstOrNull { it.param == null } ?: ops.firstOrNull()
}

fun OpDef.toAct(entityId: String, domain: String): Act =
    Act(entityId, "$domain.$service", param?.let { mapOf(it.key to it.default()) } ?: emptyMap())

/** 状態のきっかけで選べる状態 */
fun stateOptions(domain: String): List<Pair<String, String>> = when (domain) {
    "binary_sensor" -> listOf("on" to "反応した", "off" to "反応しなくなった")
    "person", "device_tracker" -> listOf("home" to "帰宅した", "not_home" to "外出した")
    "cover" -> listOf("open" to "開いた", "closed" to "閉まった")
    "lock" -> listOf("locked" to "施錠した", "unlocked" to "解錠した")
    "climate" -> listOf("off" to "停止した", "cool" to "冷房になった", "heat" to "暖房になった", "dry" to "除湿になった")
    else -> listOf("on" to "オンになった", "off" to "オフになった")
}

/** きっかけに使える機器の種類 */
val TRIGGER_DOMAINS = listOf("binary_sensor", "person", "device_tracker", "light", "switch", "fan", "cover", "climate", "input_boolean", "lock")

fun actLabel(a: Act, name: String): String {
    val op = opOf(a)
    return when (op?.param) {
        Param.BRIGHTNESS -> "$name を明るさ ${a.data["brightness_pct"]}% でつける"
        Param.TEMPERATURE -> "$name を ${a.data["temperature"]}℃ に設定する"
        Param.HVAC_MODE -> "$name を${hvacLabel(a.data["hvac_mode"].toString())}にする"
        Param.POSITION -> "$name を ${a.data["position"]}% 開ける"
        null -> "$name を${op?.label ?: a.service}"
    }
}

fun weekdayText(days: Set<String>): String = when {
    days.isEmpty() || days.size == 7 -> "毎日"
    days == setOf("mon", "tue", "wed", "thu", "fri") -> "平日"
    days == setOf("sat", "sun") -> "土日"
    else -> WEEKDAYS.filter { it in days }.joinToString("・") { WEEKDAY_LABEL[it]!! }
}

fun trigLabel(t: Trig, nameOf: (String) -> String): String = when (t) {
    is Trig.Time -> "${t.at.trimStart('0').let { if (it.startsWith(":")) "0$it" else it }} に"
    is Trig.State -> "${nameOf(t.entityId)} が${stateOptions(t.entityId.substringBefore('.')).firstOrNull { it.first == t.to }?.second ?: "「${t.to}」になった"}とき"
    is Trig.Sun -> (if (t.event == "sunrise") "日の出" else "日の入り") + when {
        t.offsetMin < 0 -> "の${-t.offsetMin}分前に"
        t.offsetMin > 0 -> "の${t.offsetMin}分後に"
        else -> "に"
    }
}

fun ruleSummary(r: Rule, nameOf: (String) -> String): String {
    val time = listOfNotNull(
        weekdayText(r.weekdays),
        if (r.after != null || r.before != null) "${r.after ?: "0:00"}〜${r.before ?: "24:00"} の間" else null,
    ).joinToString("・")
    val acts = r.actions.joinToString("、") { actLabel(it, nameOf(it.entityId)) }
    return "$time ${trigLabel(r.trigger, nameOf)} → ${acts.ifBlank { "（操作なし）" }}"
}
