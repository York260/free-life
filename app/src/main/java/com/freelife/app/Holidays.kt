package com.freelife.app

import java.time.DayOfWeek
import java.time.LocalDate

/**
 * 台灣政府行政機關放假日(行政院人事行政總處辦公日曆表,115、116 年)。
 * 連假期間內的週末也列進來,方便標示「連假」。2026 年起不再補班。
 */
object Holidays {
    private val MAP: Map<LocalDate, String> by lazy {
        val m = HashMap<LocalDate, String>()
        fun range(a: String, b: String, name: String) {
            var d = LocalDate.parse(a)
            val end = LocalDate.parse(b)
            while (!d.isAfter(end)) {
                m[d] = name
                d = d.plusDays(1)
            }
        }
        // 115 年(2026)
        range("2026-01-01", "2026-01-01", "元旦")
        range("2026-02-14", "2026-02-22", "春節")
        range("2026-02-27", "2026-03-01", "和平紀念日")
        range("2026-04-03", "2026-04-06", "兒童節清明節")
        range("2026-05-01", "2026-05-03", "勞動節")
        range("2026-06-19", "2026-06-21", "端午節")
        range("2026-09-25", "2026-09-28", "中秋教師節")
        range("2026-10-09", "2026-10-11", "國慶日")
        range("2026-10-24", "2026-10-26", "光復節")
        range("2026-12-25", "2026-12-27", "行憲紀念日")
        // 116 年(2027)
        range("2027-01-01", "2027-01-03", "元旦")
        range("2027-02-04", "2027-02-10", "春節")
        range("2027-02-27", "2027-03-01", "和平紀念日")
        range("2027-04-03", "2027-04-06", "兒童節清明節")
        range("2027-04-30", "2027-05-02", "勞動節")
        range("2027-06-09", "2027-06-09", "端午節")
        range("2027-09-15", "2027-09-15", "中秋節")
        range("2027-09-28", "2027-09-28", "教師節")
        range("2027-10-09", "2027-10-11", "國慶日")
        range("2027-10-23", "2027-10-25", "光復節")
        range("2027-12-24", "2027-12-26", "行憲紀念日")
        range("2027-12-31", "2028-01-02", "元旦")
        m
    }

    /** 有資料的年份(超出範圍就不判斷假日)。 */
    val COVERED = 2026..2027

    fun name(d: LocalDate): String? = MAP[d]

    fun isHoliday(d: LocalDate): Boolean = MAP.containsKey(d)

    private fun off(d: LocalDate) = isHoliday(d) || d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY

    /** 這天屬於連續 3 天以上的假期(含週末)時,回傳 (開始, 結束)。 */
    fun longBreak(d: LocalDate): Pair<LocalDate, LocalDate>? {
        if (!isHoliday(d) && !(off(d) && (isHoliday(d.minusDays(1)) || isHoliday(d.plusDays(1)) ||
                isHoliday(d.minusDays(2)) || isHoliday(d.plusDays(2))))
        ) return null
        var a = d
        while (off(a.minusDays(1))) a = a.minusDays(1)
        var b = d
        while (off(b.plusDays(1))) b = b.plusDays(1)
        val days = java.time.temporal.ChronoUnit.DAYS.between(a, b) + 1
        if (days < 3) return null
        // 只是普通週末(期間沒有任何國定假日)不算
        var x = a
        var any = false
        while (!x.isAfter(b)) {
            if (isHoliday(x)) any = true
            x = x.plusDays(1)
        }
        return if (any) Pair(a, b) else null
    }

    /** 月曆格子用的短名字。 */
    fun shortName(d: LocalDate): String? {
        val n = name(d) ?: return if (longBreak(d) != null) "連假" else null
        return when (n) {
            "和平紀念日" -> "228"
            "兒童節清明節" -> "清明"
            "中秋教師節" -> "中秋"
            "行憲紀念日" -> "行憲"
            else -> n.take(3)
        }
    }

    /** 一段日期內的連假清單(給 AI 與畫面用)。 */
    fun breaksBetween(from: LocalDate, to: LocalDate): List<Triple<LocalDate, LocalDate, String>> {
        val out = mutableListOf<Triple<LocalDate, LocalDate, String>>()
        var d = from
        while (!d.isAfter(to)) {
            val br = longBreak(d)
            if (br != null) {
                var nm = "連假"
                var x = br.first
                while (!x.isAfter(br.second)) {
                    name(x)?.let { nm = it }
                    x = x.plusDays(1)
                }
                out += Triple(br.first, br.second, nm)
                d = br.second.plusDays(1)
            } else {
                d = d.plusDays(1)
            }
        }
        return out
    }
}
