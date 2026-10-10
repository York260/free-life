package com.freelife.app

import android.app.NotificationManager
import android.content.Context

/** 超過這個長度的排程(整天出差、值夜班)不當成「會議」,否則起床鬧鐘會被誤判成衝突。 */
const val MAX_MEETING_MS = 8 * 60 * 60 * 1000L

/** 時間衝突的判斷:排程之間重疊、提醒落在別人的排程裡、手機的勿擾狀態。 */
object Conflicts {

    /**
     * 找出和 r 衝突的其他排程(尚未完成)。
     * - r 有結束時間:和別的排程時段重疊,或別的單點提醒落在 r 裡面。
     * - r 只有開始時間且會響鈴:開始時間落在別的排程裡。
     * - r 沒有響鈴也沒有結束時間(只是記下來):不算衝突。
     */
    fun overlapping(all: List<Reminder>, r: Reminder): List<Reminder> {
        val s = r.start
        val e = r.endAt
        if (e == null && r.triggerAt == null) return emptyList()
        // 重複的排程(例如每週的課)要展開成那幾天實際的時段來比
        return spans(all.filter { !it.done && it.id != r.id }, s, e ?: s).filter { (o, os, oe) ->
            if (oe != null) {
                if (e != null) s < oe && os < e else s >= os && s < oe
            } else {
                e != null && o.triggerAt != null && os >= s && os < e
            }
        }.map { it.first }.distinctBy { it.id }.sortedBy { it.start }
    }

    /** 此刻正在進行、而且不是 r 本身的排程(視為開會中,課表的課也算);超過 8 小時的不算。 */
    fun inProgress(all: List<Reminder>, r: Reminder, nowMs: Long): Reminder? =
        spans(all.filter { !it.done && it.id != r.id }, nowMs, nowMs)
            .filter { (_, os, oe) -> oe != null && os <= nowMs && nowMs < oe && (oe - os) <= MAX_MEETING_MS }
            .minByOrNull { it.third ?: Long.MAX_VALUE }?.first

    /** 提醒響的時候正在上課:回傳那堂課和它當天的上下課時間。 */
    fun classAt(all: List<Reminder>, r: Reminder): Triple<Reminder, Long, Long>? {
        if (r.tag == TIMETABLE_TAG || r.ringless) return null
        val t = r.triggerAt ?: return null
        return spans(all.filter { it.tag == TIMETABLE_TAG && !it.done && it.id != r.id }, t, t)
            .firstOrNull { (_, os, oe) -> oe != null && t >= os && t < oe }
            ?.let { Triple(it.first, it.second, it.third!!) }
    }

    /** 把 from..to 前後一天內的行程展開成 (行程, 開始, 結束)。 */
    private fun spans(list: List<Reminder>, fromMs: Long, toMs: Long): List<Triple<Reminder, Long, Long?>> {
        val zone = java.time.ZoneId.systemDefault()
        val d0 = java.time.Instant.ofEpochMilli(fromMs).atZone(zone).toLocalDate().minusDays(1)
        val d1 = java.time.Instant.ofEpochMilli(toMs).atZone(zone).toLocalDate().plusDays(2)
        val single = list.filter { it.repeat.isEmpty() }.map { Triple(it, it.start, it.endAt) }
        val repeated = ScheduleModel.occurrences(list.filter { it.repeat.isNotEmpty() }, d0, d1).map { o ->
            Triple(o.r, o.start.atZone(zone).toInstant().toEpochMilli(), o.end?.atZone(zone)?.toInstant()?.toEpochMilli())
        }
        return single + repeated
    }

    private fun filter(ctx: Context): Int {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return nm.currentInterruptionFilter
    }

    /** 勿擾「完全靜音」會連鬧鐘一起擋掉。 */
    fun totalSilence(ctx: Context): Boolean =
        filter(ctx) == NotificationManager.INTERRUPTION_FILTER_NONE

    /** 手機現在是否開著任何一種勿擾。 */
    fun dndOn(ctx: Context): Boolean {
        val f = filter(ctx)
        return f != NotificationManager.INTERRUPTION_FILTER_ALL &&
            f != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
    }

    /** 「與「開會」(10/8 (三) 14:00–15:00)時間重疊」這種一行說明。 */
    fun describe(list: List<Reminder>): String {
        val first = list.first()
        val e = first.endAt
        val span = if (e != null) formatRange(first.start, e) else formatTrigger(first.start)
        val more = if (list.size > 1) " 等 ${list.size} 項" else ""
        return "與「${first.title}」($span)$more"
    }
}
