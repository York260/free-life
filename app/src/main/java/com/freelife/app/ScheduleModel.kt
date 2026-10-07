package com.freelife.app

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** 行程圖上的一次出現。重複的提醒會展開成多次,所以不能直接用 Reminder。 */
data class Occ(val r: Reminder, val start: LocalDateTime, val end: LocalDateTime?) {
    val effectiveEnd: LocalDateTime get() = end ?: start
    val date: LocalDate get() = start.toLocalDate()
}

/** 擺在時間軸上的一塊:col / cols 表示和誰並排(時間重疊時分欄)。 */
data class Placed(val occ: Occ, val col: Int, val cols: Int)

object ScheduleModel {
    private fun local(ms: Long): LocalDateTime =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault())

    /** 有時間的提醒才會出現在行程圖上(沒有時間的小任務另外列)。 */
    fun isTimed(r: Reminder): Boolean = r.triggerAt != null || r.endAt != null

    /** 在 [from, toExclusive) 這段日期內的所有出現,重複項目會往後展開。 */
    fun occurrences(all: List<Reminder>, from: LocalDate, toExclusive: LocalDate): List<Occ> {
        val out = mutableListOf<Occ>()
        for (r in all) {
            if (!isTimed(r)) continue
            val s0 = local(r.start)
            val span: Duration? = r.endAt?.let { Duration.between(s0, local(it)) }
            if (r.repeat.isEmpty()) {
                val d = s0.toLocalDate()
                if (!d.isBefore(from) && d.isBefore(toExclusive)) out += Occ(r, s0, span?.let { s0.plus(it) })
                continue
            }
            var cur = s0
            var n = 0
            while (cur.toLocalDate().isBefore(toExclusive) && n < 800) {
                if (!cur.toLocalDate().isBefore(from)) out += Occ(r, cur, span?.let { cur.plus(it) })
                cur = Repeat.next(cur, r.repeat)
                n++
            }
        }
        return out.sortedBy { it.start }
    }

    fun forDay(all: List<Occ>, day: LocalDate): List<Occ> = all.filter { it.date == day }

    /** 時間軸上最短顯示 30 分鐘,免得單點提醒看不見。 */
    private fun visualEnd(o: Occ): LocalDateTime = o.end ?: o.start.plusMinutes(30)

    /** 時間重疊的分成並排的欄。 */
    fun layout(list: List<Occ>): List<Placed> {
        val sorted = list.sortedBy { it.start }
        val result = mutableListOf<Placed>()
        var cluster = mutableListOf<Occ>()
        var clusterEnd: LocalDateTime? = null

        fun flush() {
            if (cluster.isEmpty()) return
            val colEnds = mutableListOf<LocalDateTime>()
            val colOf = mutableListOf<Int>()
            for (o in cluster) {
                var c = colEnds.indexOfFirst { !it.isAfter(o.start) }
                if (c < 0) {
                    colEnds.add(visualEnd(o))
                    c = colEnds.size - 1
                } else {
                    colEnds[c] = visualEnd(o)
                }
                colOf.add(c)
            }
            cluster.forEachIndexed { i, o -> result += Placed(o, colOf[i], colEnds.size) }
            cluster = mutableListOf()
            clusterEnd = null
        }

        for (o in sorted) {
            val ce = clusterEnd
            if (ce != null && !o.start.isBefore(ce)) flush()
            cluster.add(o)
            val e = visualEnd(o)
            val cur = clusterEnd
            clusterEnd = if (cur == null || e.isAfter(cur)) e else cur
        }
        flush()
        return result
    }

    /** 這天(從 from 起到 until 止)至少 minMinutes 分鐘的空檔。 */
    fun freeGaps(
        occs: List<Occ>,
        from: LocalDateTime,
        until: LocalDateTime,
        minMinutes: Long,
    ): List<Pair<LocalDateTime, LocalDateTime>> {
        val gaps = mutableListOf<Pair<LocalDateTime, LocalDateTime>>()
        var cursor = from
        for (o in occs.sortedBy { it.start }) {
            val s = o.start
            val e = visualEnd(o)
            if (s.isAfter(cursor)) {
                val gapEnd = if (s.isBefore(until)) s else until
                if (Duration.between(cursor, gapEnd).toMinutes() >= minMinutes) gaps += Pair(cursor, gapEnd)
            }
            if (e.isAfter(cursor)) cursor = e
            if (!cursor.isBefore(until)) return gaps
        }
        if (cursor.isBefore(until) && Duration.between(cursor, until).toMinutes() >= minMinutes) {
            gaps += Pair(cursor, until)
        }
        return gaps
    }

    /** 同一天裡有沒有互相重疊的行程(排除單點)。 */
    fun hasClash(p: Placed): Boolean = p.cols > 1

    fun minutesOfDay(t: LocalTime): Int = t.hour * 60 + t.minute
}
