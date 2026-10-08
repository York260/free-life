package com.freelife.app

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 重複提醒:daily 每天、weekdays 平日(週一到五)、weekly 每週、monthly 每月。
 * 重複的提醒本身是「系列」,永遠停在下一次出現的時間;響起時會產生一筆單次副本去響,
 * 系列則往後移到下一次。
 */
object Repeat {
    val OPTIONS = listOf(
        "" to "不重複",
        "daily" to "每天",
        "weekdays" to "平日",
        "weekly" to "每週",
        "monthly" to "每月",
    )

    fun label(code: String): String =
        if (code.isEmpty()) "" else (OPTIONS.firstOrNull { it.first == code }?.second ?: "")

    private fun toLocal(ms: Long): LocalDateTime =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault())

    private fun toMillis(dt: LocalDateTime): Long =
        dt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun isWeekend(d: LocalDateTime): Boolean =
        d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY

    fun alignWeekday(dt: LocalDateTime): LocalDateTime {
        var d = dt
        while (isWeekend(d)) d = d.plusDays(1)
        return d
    }

    /** 這個重複規則的下一次出現時間(行程圖用來展開未來的次數)。 */
    fun next(dt: LocalDateTime, code: String): LocalDateTime = nextLocal(dt, code)

    private fun nextLocal(dt: LocalDateTime, code: String): LocalDateTime = when (code) {
        "daily" -> dt.plusDays(1)
        "weekdays" -> alignWeekday(dt.plusDays(1))
        "weekly" -> dt.plusWeeks(1)
        "monthly" -> dt.plusMonths(1)
        else -> dt
    }

    /** 第一次出現的時間:dt 還沒過就用 dt,否則照規則往後推到未來。 */
    fun firstAfter(dt: LocalDateTime, code: String, now: LocalDateTime): LocalDateTime {
        var d = if (code == "weekdays") alignWeekday(dt) else dt
        var n = 0
        while (code.isNotEmpty() && !d.isAfter(now) && n < 1000) {
            d = nextLocal(d, code)
            n++
        }
        return d
    }

    /** 往後移一次;結束時間跟著平移,響鈴時間 = 開始時間 - 提前分鐘。 */
    fun shift(r: Reminder): Reminder {
        if (r.repeat.isEmpty()) return r
        val oldStart = r.start
        val newStart = toMillis(nextLocal(toLocal(oldStart), r.repeat))
        val delta = newStart - oldStart
        val ring = if (r.triggerAt == null) {
            null
        } else if (r.leadMin > 0) {
            newStart - r.leadMin * 60_000L
        } else {
            newStart
        }
        return r.copy(startAt = newStart, endAt = r.endAt?.let { it + delta }, triggerAt = ring)
    }

    /** 一直往後移,直到響鈴時間在 nowMs 之後(錯過的次數直接略過)。 */
    fun advance(r: Reminder, nowMs: Long): Reminder {
        var cur = r
        var n = 0
        while (cur.repeat.isNotEmpty() && (cur.triggerAt ?: Long.MAX_VALUE) <= nowMs && n < 1000) {
            cur = shift(cur)
            n++
        }
        // 假日跳過:落在國定假日/連假就再往後一次
        while (cur.repeat.isNotEmpty() && cur.skipHolidays && Holidays.isHoliday(toLocal(cur.start).toLocalDate()) && n < 1100) {
            cur = shift(cur)
            n++
        }
        return cur
    }
}
