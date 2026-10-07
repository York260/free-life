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
        return all.filter { o ->
            if (o.done || o.id == r.id) return@filter false
            val oe = o.endAt
            val os = o.start
            if (oe != null) {
                if (e != null) s < oe && os < e else s >= os && s < oe
            } else {
                // 別人是單點提醒:只有 r 有結束時間時,才看它有沒有落在 r 裡面
                e != null && o.triggerAt != null && os >= s && os < e
            }
        }.sortedBy { it.start }
    }

    /** 此刻正在進行、而且不是 r 本身的排程(視為開會中);超過 8 小時的不算。 */
    fun inProgress(all: List<Reminder>, r: Reminder, nowMs: Long): Reminder? =
        all.filter { o ->
            val oe = o.endAt
            !o.done && o.id != r.id && oe != null &&
                o.start <= nowMs && nowMs < oe && (oe - o.start) <= MAX_MEETING_MS
        }.minByOrNull { it.endAt ?: Long.MAX_VALUE }

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
