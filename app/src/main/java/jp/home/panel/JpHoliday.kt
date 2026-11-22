package jp.home.panel

import java.time.DayOfWeek
import java.time.LocalDate

/** 日本の祝日（法律どおりに計算する。振替休日・国民の休日を含む。春分・秋分は 1980〜2099 年の近似式） */
object JpHoliday {
    private val cache = mutableMapOf<Int, Set<LocalDate>>()

    fun of(y: Int): Set<LocalDate> = cache.getOrPut(y) {
        fun nthMonday(m: Int, n: Int): LocalDate {
            var d = LocalDate.of(y, m, 1)
            while (d.dayOfWeek != DayOfWeek.MONDAY) d = d.plusDays(1)
            return d.plusWeeks((n - 1).toLong())
        }
        val spring = (20.8431 + 0.242194 * (y - 1980) - ((y - 1980) / 4)).toInt()
        val autumn = (23.2488 + 0.242194 * (y - 1980) - ((y - 1980) / 4)).toInt()
        val h = sortedSetOf(
            LocalDate.of(y, 1, 1), nthMonday(1, 2), LocalDate.of(y, 2, 11), LocalDate.of(y, 2, 23), LocalDate.of(y, 3, spring),
            LocalDate.of(y, 4, 29), LocalDate.of(y, 5, 3), LocalDate.of(y, 5, 4), LocalDate.of(y, 5, 5), nthMonday(7, 3),
            LocalDate.of(y, 8, 11), nthMonday(9, 3), LocalDate.of(y, 9, autumn), nthMonday(10, 2), LocalDate.of(y, 11, 3), LocalDate.of(y, 11, 23),
        )
        // 国民の休日：祝日にはさまれた平日
        for (d in h.toList()) {
            val mid = d.plusDays(1)
            if (mid !in h && mid.plusDays(1) in h && mid.dayOfWeek != DayOfWeek.SUNDAY) h.add(mid)
        }
        // 振替休日：日曜の祝日の、次の祝日でない日
        for (d in h.toList()) {
            if (d.dayOfWeek == DayOfWeek.SUNDAY) {
                var n = d.plusDays(1)
                while (n in h) n = n.plusDays(1)
                h.add(n)
            }
        }
        h
    }

    fun isHoliday(d: LocalDate) = d in of(d.year)

    /** 今年から years 年分の祝日（"2026-11-23" の形） */
    fun list(from: Int = LocalDate.now().year, years: Int = 3): List<String> = (from until from + years).flatMap { of(it) }.sorted().map { it.toString() }
}
