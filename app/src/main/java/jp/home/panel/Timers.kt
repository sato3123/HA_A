package jp.home.panel

import org.json.JSONArray
import org.json.JSONObject

/** キッチンタイマーなど。endAt が null なら一時停止中（残りは remainingMs） */
data class PanelTimer(
    val id: Long,
    val label: String,
    val totalMs: Long,
    val endAt: Long?,
    val remainingMs: Long,
) {
    val running: Boolean get() = endAt != null
    fun remaining(now: Long): Long = if (endAt != null) (endAt - now).coerceAtLeast(0) else remainingMs
}

fun timersToJson(list: List<PanelTimer>): String = JSONArray().apply {
    list.forEach {
        put(JSONObject().put("id", it.id).put("label", it.label).put("total", it.totalMs)
            .put("end", it.endAt ?: JSONObject.NULL).put("rem", it.remainingMs))
    }
}.toString()

fun timersFromJson(s: String?): List<PanelTimer> = try {
    val a = JSONArray(s ?: "[]")
    (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map {
        PanelTimer(it.optLong("id"), it.optString("label"), it.optLong("total"),
            if (it.isNull("end")) null else it.optLong("end"), it.optLong("rem"))
    }
} catch (e: Exception) {
    emptyList()
}

/** 残り時間の表示（例：1:05:09 / 4:32） */
fun formatRemaining(ms: Long): String {
    val s = (ms + 999) / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, sec) else String.format("%d:%02d", m, sec)
}

/** 読み上げ用（例：3分20秒） */
fun speakRemaining(ms: Long): String {
    val s = (ms + 999) / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return buildString {
        if (h > 0) append("${h}時間")
        if (m > 0) append("${m}分")
        if (sec > 0 && h == 0L) append("${sec}秒")
        if (isEmpty()) append("0秒")
    }
}

/** 声でのタイマー操作 */
sealed interface TimerCommand {
    data class Start(val seconds: Long, val label: String) : TimerCommand
    data object Cancel : TimerCommand
    data object Remaining : TimerCommand
}

private val KANJI_DIGIT = mapOf('〇' to 0, '零' to 0, '一' to 1, '二' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)

/** 「十五」「三十」「二十五」「百二十」などを数字に（99まで＋百） */
private fun kanjiToNumber(s: String): Int? {
    if (s.isEmpty()) return null
    var total = 0
    var cur = 0
    for (c in s) {
        when {
            KANJI_DIGIT.containsKey(c) -> cur = KANJI_DIGIT[c]!!
            c == '十' -> { total += (if (cur == 0) 1 else cur) * 10; cur = 0 }
            c == '百' -> { total += (if (cur == 0) 1 else cur) * 100; cur = 0 }
            else -> return null
        }
    }
    return total + cur
}

/** 全角数字・漢数字を半角数字にそろえる */
fun normalizeNumbers(text: String): String {
    val half = text.map { c -> if (c in '０'..'９') ('0' + (c - '０')) else c }.joinToString("")
    return Regex("[〇零一二三四五六七八九十百]+").replace(half) { m -> kanjiToNumber(m.value)?.toString() ?: m.value }
}

fun parseTimerCommand(raw: String): TimerCommand? {
    val text = normalizeNumbers(raw).replace(" ", "").replace("　", "")
    if (!text.contains("タイマー")) return null
    if (Regex("止め|とめ|キャンセル|消し|けし|解除|ストップ|終了|やめ").containsMatchIn(text)) return TimerCommand.Cancel
    if (Regex("残り|のこり|あと何|あとどれ|何分").containsMatchIn(text)) return TimerCommand.Remaining
    val half = Regex("(\\d+)分半").find(text)
    val m = Regex("(?:(\\d+)時間)?(?:(\\d+)分)?(?:(\\d+)秒)?").findAll(text).firstOrNull { it.value.isNotEmpty() } ?: return null
    val h = m.groupValues[1].toLongOrNull() ?: 0
    val min = m.groupValues[2].toLongOrNull() ?: 0
    val sec = m.groupValues[3].toLongOrNull() ?: 0
    var total = h * 3600 + min * 60 + sec
    if (half != null) total += 30
    if (total <= 0) return null
    // 「パスタの5分タイマー」→ ラベル「パスタ」
    val before = text.substring(0, m.range.first).removeSuffix("の").trim()
    val label = before.takeIf { it.isNotBlank() && it.length <= 12 && !it.contains("タイマー") } ?: ""
    return TimerCommand.Start(total, label)
}
