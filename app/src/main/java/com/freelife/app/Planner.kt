package com.freelife.app

import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** 把「找時間做某事」塞進空檔:每天先放一段,不夠再放第二段,避免同一天塞滿。 */
object Planner {
    private val DAY_START = LocalTime.of(8, 0)
    private val DAY_END = LocalTime.of(22, 0)

    fun plan(all: List<Reminder>, req: PlanRequest, now: LocalDateTime): List<Reminder> {
        val zone = ZoneId.systemDefault()
        val chunk = req.chunk.toLong()
        var remaining = req.minutes.toLong()
        val days = generateSequence(req.from) { it.plusDays(1) }.takeWhile { !it.isAfter(req.to) }.toList()
        if (days.isEmpty()) return emptyList()
        val occs = ScheduleModel.occurrences(all, req.from, req.to.plusDays(1)).toMutableList()
        val out = mutableListOf<Reminder>()

        for (round in 0 until 3) {
            for (d in days) {
                if (remaining <= 0) break
                var from = LocalDateTime.of(d, DAY_START)
                if (d == now.toLocalDate() && now.isAfter(from)) from = roundUp(now)
                val until = LocalDateTime.of(d, DAY_END)
                if (!from.isBefore(until)) continue
                val len = minOf(chunk, remaining)
                val gap = ScheduleModel.freeGaps(ScheduleModel.forDay(occs, d), from, until, len.toInt())
                    .firstOrNull() ?: continue
                // 每段前後留 10 分鐘緩衝(空檔夠大時)
                val start = if (Duration.between(gap.first, gap.second).toMinutes() >= len + 20) gap.first.plusMinutes(10) else gap.first
                val end = start.plusMinutes(len)
                val sMs = start.atZone(zone).toInstant().toEpochMilli()
                val eMs = end.atZone(zone).toInstant().toEpochMilli()
                val r = Reminder(
                    id = Assistant.newId(),
                    title = req.title,
                    triggerAt = if (req.ring) sMs else null,
                    startAt = sMs,
                    endAt = eMs,
                    timed = !req.ring,
                )
                out += r
                occs += Occ(r, start, end)
                remaining -= len
            }
            if (remaining <= 0) break
        }
        return out
    }

    private fun roundUp(t: LocalDateTime): LocalDateTime {
        val base = t.withSecond(0).withNano(0)
        val m = base.minute
        return if (m == 0 || m == 30) base else if (m < 30) base.withMinute(30) else base.withMinute(0).plusHours(1)
    }

    private val PLAN_RE = Regex(
        "(今天|明天|這週|这周|本週|這禮拜|下週|下周|週末)?.{0,4}?(?:要|想|得|找時間|排時間|安排)(.{1,12}?)" +
            "(半|\\d+(?:\\.5)?|[一二兩三四五六七八九十]+)\\s*(?:個)?(半)?\\s*(?:小時|鐘頭)",
    )

    /** 沒接 AI 時的簡易版:「這週要讀書三小時」「明天找時間整理報告 2 小時」。 */
    fun parse(text: String, now: LocalDateTime): PlanRequest? {
        val t = ReminderParser.normalize(text)
        if (!t.contains("小時") && !t.contains("鐘頭")) return null
        if (!Regex("要|想|得|找時間|排時間|安排").containsMatchIn(t)) return null
        val m = PLAN_RE.find(t) ?: return null
        val numText = m.groupValues[3]
        val hours: Double = when {
            numText == "半" -> 0.5
            numText.contains(".") -> numText.toDoubleOrNull() ?: return null
            else -> (ReminderParser.cnToInt(numText) ?: return null).toDouble()
        } + if (m.groupValues[4] == "半") 0.5 else 0.0
        val title = m.groupValues[2].trim().trimStart('去', '來').ifEmpty { return null }
        val today = now.toLocalDate()
        val (from, to) = when (m.groupValues[1]) {
            "今天" -> today to today
            "明天" -> today.plusDays(1) to today.plusDays(1)
            "下週", "下周" -> today.with(TemporalAdjusters.next(DayOfWeek.MONDAY)).let { it to it.plusDays(6) }
            "週末" -> today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY)).let { it to it.plusDays(1) }
            else -> today to today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
        }
        val minutes = (hours * 60).toInt().coerceIn(15, 40 * 60)
        return PlanRequest(title, minutes, from, to, if (minutes <= 60) minutes else 60, true)
    }
}
