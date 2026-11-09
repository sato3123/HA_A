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
    /**
     * 家からの距離。personId（人・スマートフォンの位置）が家から km 以内に近づいたとき（approach）、
     * または km より遠くへ離れたとき。Home Assistant の distance() を使うので、スマートフォンの
     * Home Assistant アプリで位置を送っている人が対象。
     */
    data class Distance(val personId: String, val km: Double, val approach: Boolean) : Trig
    /**
     * 温度計などの数値（温度・湿度）。above なら value を上回ったとき、そうでなければ下回ったとき。
     * Home Assistant の numeric_state を使う（境目をまたいだときに1回動く）。forMin 分続いたら動かす（0 ならすぐ）
     */
    data class Numeric(val entityId: String, val above: Boolean, val value: Double, val forMin: Int = 0) : Trig
}

/** 条件（きっかけの時点で、すべて満たすときだけ動かす） */
sealed interface Cond {
    /** 温度計などの数値が value を上回っている（above）／下回っている */
    data class Numeric(val entityId: String, val above: Boolean, val value: Double) : Cond
    /** 機器がその状態になっている（例：在宅・オフ） */
    data class State(val entityId: String, val state: String) : Cond
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
    val conds: List<Cond> = emptyList(),
)

const val RULE_MARK = "ホームパネルで作成"

/** ECHONET Lite の機器の操作を、ホームパネルのサーバーに頼むイベント */
const val EL_EVENT = "homepanel_el"

/** Home Assistant の機器が利用不可のとき、直接（ECHONET Lite）操作に切り替える分かれ道の名前 */
const val EL_FALLBACK = "ホームパネル：利用不可なら直接操作"

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
fun Rule.toConfig(nameOf: ((String) -> String)? = null, elFor: ((String) -> String?)? = null): JSONObject {
    val trig = when (val t = trigger) {
        is Trig.Time -> JSONObject().put("trigger", "time").put("at", hhmm(t.at) + ":00")
        is Trig.State -> JSONObject().put("trigger", "state").put("entity_id", t.entityId).put("to", t.to)
        is Trig.Sun -> JSONObject().put("trigger", "sun").put("event", t.event).also {
            if (t.offsetMin != 0) it.put("offset", offsetString(t.offsetMin))
        }
        is Trig.Distance -> JSONObject().put("trigger", "template").put(
            "value_template",
            "{{ distance('${t.personId}') is number and distance('${t.personId}') ${if (t.approach) "<" else ">"} ${t.km} }}",
        )
        is Trig.Numeric -> JSONObject().put("trigger", "numeric_state").put("entity_id", t.entityId)
            .put(if (t.above) "above" else "below", t.value).also {
                if (t.forMin > 0) it.put("for", String.format("%02d:%02d:00", t.forMin / 60, t.forMin % 60))
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
    this.conds.forEach { c ->
        when (c) {
            is Cond.Numeric -> conds.put(JSONObject().put("condition", "numeric_state").put("entity_id", c.entityId).put(if (c.above) "above" else "below", c.value))
            is Cond.State -> conds.put(JSONObject().put("condition", "state").put("entity_id", c.entityId).put("state", c.state))
        }
    }
    val acts = JSONArray()
    actions.forEach { a ->
        val data = JSONObject()
        a.data.forEach { (k, v) -> data.put(k, v) }
        // 送風・除湿は温度を送らない（受け付けないエアコンがあるため）
        if (a.service == "climate.set_temperature" && a.data["hvac_mode"] in setOf("fan_only", "dry")) {
            data.remove("temperature")
        }
        if (isEchonetEntity(a.entityId)) {
            // ECHONET Lite の機器は Home Assistant に無いので、イベントにしてホームパネルのサーバーに操作してもらう
            acts.put(
                JSONObject().put("event", EL_EVENT).put("event_data", JSONObject()
                    .put("entity_id", a.entityId).put("service", a.service.substringAfter('.')).put("data", data).put("name", nameOf?.invoke(a.entityId) ?: a.entityId))
            )
            return@forEach
        }
        // 風量は Home Assistant では別の操作（climate.set_fan_mode）なので、運転の操作のあとに続けて送る
        val seq = JSONArray()
        val fan = if (a.service.startsWith("climate.") && a.service != "climate.set_fan_mode" && data.has("fan_mode")) data.remove("fan_mode") else null
        val mode = data.optString("hvac_mode")
        if (a.service == "climate.set_temperature" && data.has("temperature") && mode.isNotBlank() && mode != "off") {
            // 運転モードと温度は別々に送る。温度の操作に運転モードを付けても、運転を入れない連携があるため
            // （HEMS Echonet Lite など。目標温度だけ変わって、エアコンがつかない）
            seq.put(JSONObject().put("action", "climate.set_hvac_mode").put("target", JSONObject().put("entity_id", a.entityId)).put("data", JSONObject().put("hvac_mode", mode)))
            seq.put(JSONObject().put("action", "climate.set_temperature").put("target", JSONObject().put("entity_id", a.entityId))
                .put("data", JSONObject(data.toString()).apply { remove("hvac_mode") }))
        } else {
            val main = if (a.service == "climate.set_temperature" && !data.has("temperature") && data.has("hvac_mode")) "climate.set_hvac_mode" else a.service
            seq.put(JSONObject().put("action", main).put("target", JSONObject().put("entity_id", a.entityId)).put("data", data))
        }
        fan?.let {
            seq.put(JSONObject().put("action", "climate.set_fan_mode").put("target", JSONObject().put("entity_id", a.entityId)).put("data", JSONObject().put("fan_mode", it)))
        }
        // 直接つないでいる（ECHONET Lite）機器と結びつけてある Home Assistant の機器は、Home Assistant 側で
        // 「利用不可」になっていたら、ホームパネルのサーバーに直接操作してもらう（Home Assistant の連携が切れていても動くように）
        val el = elFor?.invoke(a.entityId)
        if (el != null && isEchonetEntity(el)) {
            val elData = JSONObject(data.toString()).apply { fan?.let { put("fan_mode", it) } }
            acts.put(
                JSONObject().put("alias", EL_FALLBACK).put("choose", JSONArray().put(
                    JSONObject().put("conditions", JSONArray().put(
                        JSONObject().put("condition", "not").put("conditions", JSONArray().put(
                            JSONObject().put("condition", "state").put("entity_id", a.entityId).put("state", JSONArray().put("unavailable").put("unknown"))
                        ))
                    )).put("sequence", seq)
                )).put("default", JSONArray().put(
                    JSONObject().put("event", EL_EVENT).put("event_data", JSONObject()
                        .put("entity_id", el).put("service", a.service.substringAfter('.')).put("data", elData).put("name", nameOf?.invoke(a.entityId) ?: a.entityId))
                ))
            )
        } else {
            for (i in 0 until seq.length()) acts.put(seq.get(i))
        }
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
        "numeric_state" -> {
            val e = singleString(t.opt("entity_id")) ?: return null
            val hasA = t.has("above"); val hasB = t.has("below")
            if (hasA == hasB) return null // 両方ある（範囲）・どちらも無い形は扱わない
            val v = (if (hasA) t.opt("above") else t.opt("below"))?.toString()?.toDoubleOrNull() ?: return null
            Trig.Numeric(e, hasA, v, parseOffset(t.opt("for")).coerceAtLeast(0))
        }
        "template" -> {
            val m = Regex("""distance\('([^']+)'\)\s*([<>])\s*([0-9.]+)""").find(t.optString("value_template")) ?: return null
            Trig.Distance(m.groupValues[1], m.groupValues[3].toDoubleOrNull() ?: return null, m.groupValues[2] == "<")
        }
        else -> return null
    }

    var weekdays = emptySet<String>()
    var after: String? = null
    var before: String? = null
    val conds = j.listOf("conditions", "condition") ?: JSONArray()
    val extra = mutableListOf<Cond>()
    var timeSeen = false
    for (i in 0 until conds.length()) {
        val c = conds.optJSONObject(i) ?: return null
        when (c.optString("condition")) {
            "time" -> {
                if (timeSeen) return null
                timeSeen = true
                weekdays = when (val w = c.opt("weekday")) {
                    is String -> setOf(w)
                    is JSONArray -> (0 until w.length()).map { w.optString(it) }.toSet()
                    else -> emptySet()
                }
                after = c.optString("after").takeIf { it.isNotBlank() }?.let(::hhmm)
                before = c.optString("before").takeIf { it.isNotBlank() }?.let(::hhmm)
            }
            "numeric_state" -> {
                val e = singleString(c.opt("entity_id")) ?: return null
                val hasA = c.has("above"); val hasB = c.has("below")
                if (hasA == hasB) return null
                val v = (if (hasA) c.opt("above") else c.opt("below"))?.toString()?.toDoubleOrNull() ?: return null
                extra.add(Cond.Numeric(e, hasA, v))
            }
            "state" -> {
                val e = singleString(c.opt("entity_id")) ?: return null
                extra.add(Cond.State(e, singleString(c.opt("state")) ?: return null))
            }
            else -> return null
        }
    }

    val actsRaw = j.listOf("actions", "action") ?: return null
    val actsJ = JSONArray()
    for (i in 0 until actsRaw.length()) {
        val a = actsRaw.optJSONObject(i) ?: return null
        if (a.has("choose") && a.optString("alias") == EL_FALLBACK) {
            val seq = a.optJSONArray("choose")?.optJSONObject(0)?.optJSONArray("sequence") ?: return null
            for (k in 0 until seq.length()) actsJ.put(seq.get(k))
        } else {
            actsJ.put(a)
        }
    }
    val acts = mutableListOf<Act>()
    for (i in 0 until actsJ.length()) {
        val a = actsJ.optJSONObject(i) ?: return null
        if (a.optString("event") == EL_EVENT) {
            val ed = a.optJSONObject("event_data") ?: return null
            val eid = ed.optString("entity_id").takeIf { isEchonetEntity(it) } ?: return null
            val d = ed.optJSONObject("data") ?: JSONObject()
            acts.add(Act(eid, eid.substringBefore('.') + "." + ed.optString("service"), d.keys().asSequence().associateWith { d.get(it) }))
            continue
        }
        val svc = a.optString("action").ifBlank { a.optString("service") }
        if (!svc.contains(".")) return null
        val data = a.optJSONObject("data") ?: a.optJSONObject("service_data") ?: JSONObject()
        val entity = singleString(a.optJSONObject("target")?.opt("entity_id"))
            ?: singleString(data.opt("entity_id"))
            ?: singleString(a.opt("entity_id"))
            ?: return null
        val map = data.keys().asSequence().filter { it != "entity_id" }.associateWith { data.get(it) }
        val last = acts.lastOrNull()
        // 運転モード → 温度 の2つの操作は、1つの「運転モードと温度」にまとめて読む
        if (svc == "climate.set_temperature" && last != null && last.entityId == entity && last.service == "climate.set_hvac_mode" &&
            !last.data.containsKey("temperature") && map["temperature"] != null && !map.containsKey("hvac_mode")) {
            acts[acts.size - 1] = last.copy(service = "climate.set_temperature", data = last.data + map)
            continue
        }
        if (svc == "climate.set_fan_mode" && last != null && last.entityId == entity && last.service in setOf("climate.set_temperature", "climate.set_hvac_mode") &&
            !last.data.containsKey("fan_mode") && map["fan_mode"] != null) {
            acts[acts.size - 1] = last.copy(data = last.data + ("fan_mode" to map["fan_mode"]!!))
            continue
        }
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
        conds = extra,
    )
}

// ---------- 操作の種類 ----------

enum class Param { BRIGHTNESS, TEMPERATURE, HVAC_MODE, POSITION, MODE_TEMP, FAN }

data class OpDef(val service: String, val label: String, val param: Param? = null)

fun opsFor(domain: String): List<OpDef> = when (domain) {
    "light" -> listOf(OpDef("turn_on", "つける"), OpDef("turn_on", "明るさを指定してつける", Param.BRIGHTNESS), OpDef("turn_off", "消す"), OpDef("toggle", "切り替える"))
    "switch", "input_boolean", "fan" -> listOf(OpDef("turn_on", "オンにする"), OpDef("turn_off", "オフにする"), OpDef("toggle", "切り替える"))
    // 「運転する」は、モードと温度をいっしょに送る（climate.set_temperature は hvac_mode も受け付ける）
    "climate" -> listOf(OpDef("set_temperature", "運転する（モードと温度）", Param.MODE_TEMP), OpDef("set_hvac_mode", "運転モードだけ変える", Param.HVAC_MODE),
        OpDef("set_temperature", "温度だけ変える", Param.TEMPERATURE), OpDef("set_fan_mode", "風量だけ変える", Param.FAN), OpDef("turn_off", "停止する"))
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
        Param.MODE_TEMP -> "hvac_mode"
        Param.FAN -> "fan_mode"
    }

fun Param.default(): Any = when (this) {
    Param.BRIGHTNESS -> 100
    Param.TEMPERATURE -> 25.0
    Param.HVAC_MODE -> "cool"
    Param.POSITION -> 50
    Param.MODE_TEMP -> "cool"
    Param.FAN -> "auto"
}

/** 今の Act がどの操作か */
fun opOf(a: Act): OpDef? {
    val domain = a.service.substringBefore('.')
    val svc = a.service.substringAfter('.')
    val ops = opsFor(domain).filter { it.service == svc }
    // 温度とモードの両方があれば「運転する」、温度だけなら「温度だけ変える」
    if (svc == "set_temperature") return ops.firstOrNull { it.param == if (a.data.containsKey("hvac_mode")) Param.MODE_TEMP else Param.TEMPERATURE }
    return ops.firstOrNull { it.param != null && a.data.containsKey(it.param.key) } ?: ops.firstOrNull { it.param == null } ?: ops.firstOrNull()
}

fun OpDef.toAct(entityId: String, domain: String): Act =
    Act(entityId, "$domain.$service", when (param) {
        null -> emptyMap()
        Param.MODE_TEMP -> mapOf("hvac_mode" to "cool", "temperature" to 25.0)  // 初めは冷房 25℃
        else -> mapOf(param.key to param.default())
    })

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
        Param.MODE_TEMP -> "$name を${hvacLabel(a.data["hvac_mode"].toString())}" + (a.data["temperature"]?.takeIf { a.data["hvac_mode"] !in setOf("fan_only", "dry") }?.let { " ${it}℃" } ?: "") +
            (a.data["fan_mode"]?.let { "・風量 ${fanModeLabel(it.toString())}" } ?: "") + "で運転する"
        Param.FAN -> "$name の風量を${fanModeLabel(a.data["fan_mode"].toString())}にする"
        Param.HVAC_MODE -> "$name を${hvacLabel(a.data["hvac_mode"].toString())}にする" + (a.data["fan_mode"]?.let { "（風量 ${fanModeLabel(it.toString())}）" } ?: "")
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
    is Trig.Distance -> "${nameOf(t.personId)} が家から${kmText(t.km)}" + if (t.approach) "以内に近づいたとき" else "より遠くへ離れたとき"
    is Trig.Numeric -> "${nameOf(t.entityId)} が${numText(t.value)}${numUnit(t.entityId, nameOf)}" +
        (if (t.above) "を超えたとき" else "を下回ったとき") + (if (t.forMin > 0) "（${t.forMin}分続いたら）" else "")
}

fun condLabel(c: Cond, nameOf: (String) -> String): String = when (c) {
    is Cond.Numeric -> "${nameOf(c.entityId)} が${numText(c.value)}${numUnit(c.entityId, nameOf)}" + if (c.above) "より高い" else "より低い"
    is Cond.State -> "${nameOf(c.entityId)} が" + (condStateOptions(c.entityId.substringBefore('.')).firstOrNull { it.first == c.state }?.second ?: "「${c.state}」")
}

/** 条件で選べる状態（「〜している」の形） */
fun condStateOptions(domain: String): List<Pair<String, String>> = when (domain) {
    "binary_sensor" -> listOf("on" to "反応している", "off" to "反応していない")
    "person", "device_tracker" -> listOf("home" to "家にいる", "not_home" to "外出している")
    "cover" -> listOf("open" to "開いている", "closed" to "閉まっている")
    "lock" -> listOf("locked" to "施錠している", "unlocked" to "解錠している")
    "climate" -> listOf("off" to "停止している", "cool" to "冷房している", "heat" to "暖房している", "dry" to "除湿している")
    else -> listOf("on" to "オンになっている", "off" to "オフになっている")
}

fun numText(v: Double) = if (v % 1.0 == 0.0) v.toInt().toString() else v.toString()

/** 単位（温度なら ℃、湿度なら %）。名前から推し量る */
private fun numUnit(id: String, nameOf: (String) -> String): String = if (nameOf(id).contains("湿度") || id.contains("humid")) "%" else "℃"

fun kmText(km: Double): String = if (km < 1) "${(km * 1000).toInt()}m" else (if (km % 1.0 == 0.0) km.toInt().toString() else km.toString()) + "km"

fun ruleSummary(r: Rule, nameOf: (String) -> String): String {
    val time = listOfNotNull(
        weekdayText(r.weekdays),
        if (r.after != null || r.before != null) "${r.after ?: "0:00"}〜${r.before ?: "24:00"} の間" else null,
    ).joinToString("・")
    val acts = r.actions.joinToString("、") { actLabel(it, nameOf(it.entityId)) }
    val cond = if (r.conds.isEmpty()) "" else "（" + r.conds.joinToString("、かつ") { condLabel(it, nameOf) } + "なら）"
    return "$time ${trigLabel(r.trigger, nameOf)}$cond → ${acts.ifBlank { "（操作なし）" }}"
}
