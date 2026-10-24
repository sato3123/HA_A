package jp.home.panel

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Home Assistant のカレンダー（TimeTree などを Home Assistant に取り込んだもの）の予定。
 * TimeTree へのログイン情報は Home Assistant 側だけにあり、このアプリには保存しない。
 */
data class CalEvent(
    val calendar: String,
    val calendarName: String,
    val summary: String,
    val start: LocalDateTime,
    val end: LocalDateTime,
    val allDay: Boolean,
    val location: String,
)

private fun parseWhen(j: JSONObject?): Pair<LocalDateTime, Boolean>? {
    if (j == null) return null
    val dt = j.optString("dateTime")
    if (dt.isNotBlank()) {
        return runCatching {
            OffsetDateTime.parse(dt).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime() to false
        }.getOrElse { LocalDateTime.parse(dt.take(19)) to false }
    }
    j.optString("date").takeIf { it.isNotBlank() }?.let { return LocalDate.parse(it).atStartOfDay() to true }
    return null
}

/** GET /api/calendars/<entity_id>?start=&end= の結果を読む */
fun parseCalendarEvents(entityId: String, name: String, body: String): List<CalEvent> {
    val arr = runCatching { JSONArray(body) }.getOrNull() ?: return emptyList()
    return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.mapNotNull { e ->
        val (start, allDay) = parseWhen(e.optJSONObject("start")) ?: return@mapNotNull null
        val end = parseWhen(e.optJSONObject("end"))?.first ?: start
        CalEvent(entityId, name, e.optString("summary").ifBlank { "（予定）" }, start, end, allDay, e.optString("location"))
    }
}

private val HM = DateTimeFormatter.ofPattern("H:mm", Locale.JAPAN)
private val MD = DateTimeFormatter.ofPattern("M/d（E）", Locale.JAPAN)

/** その日にかかっている予定か（終日の予定は終わりの日を含まない） */
fun CalEvent.isOn(day: LocalDate): Boolean {
    val s = start.toLocalDate()
    val e = if (allDay) end.toLocalDate().minusDays(1) else end.minusSeconds(1).toLocalDate()
    return !day.isBefore(s) && !day.isAfter(maxOf(s, e))
}

fun CalEvent.timeText(): String = if (allDay) "終日" else start.format(HM)

fun dayLabel(day: LocalDate, today: LocalDate = LocalDate.now()): String = when (day) {
    today -> "今日"
    today.plusDays(1) -> "明日"
    else -> day.format(MD)
}

/** 音声の「今日の予定は？」「明日の予定」 */
fun parseCalendarQuestion(text: String): Int? {
    if (!text.contains("予定")) return null
    return when {
        Regex("明日|あした|あす").containsMatchIn(text) -> 1
        Regex("今日|きょう|本日").containsMatchIn(text) -> 0
        else -> null
    }
}

fun speakDay(events: List<CalEvent>, offset: Int): String {
    val day = LocalDate.now().plusDays(offset.toLong())
    val list = events.filter { it.isOn(day) }.sortedWith(compareBy({ !it.allDay }, { it.start }))
    val label = if (offset == 0) "今日" else "明日"
    if (list.isEmpty()) return "${label}の予定はありません"
    return "${label}の予定は${list.size}件です。" + list.joinToString("、") { e ->
        (if (e.allDay) "終日" else "${e.start.hour}時" + (if (e.start.minute > 0) "${e.start.minute}分" else "") + "から") + e.summary
    } + "です"
}
