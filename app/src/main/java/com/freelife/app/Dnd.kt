package com.freelife.app

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.time.Instant
import java.time.ZoneId

/**
 * 勿擾。
 * - 普通勿擾:擋下 Free Life 自己的提醒(響的時候改成一則靜音通知,可選「結束後再提醒」)。
 * - 完全勿擾:普通勿擾 + 同時打開手機的勿擾(需要使用者授權「勿擾權限」)。
 * 來源有兩種:手動開啟(以小時為單位),以及行程進行中自動勿擾(課程預設普通、其他行程預設完全,可調整)。
 */
object Dnd {
    const val NONE = 0
    const val NORMAL = 1
    const val FULL = 2

    fun levelOf(code: String): Int = when (code) {
        "normal" -> NORMAL
        "full" -> FULL
        else -> NONE
    }

    fun codeOf(level: Int): String = when (level) {
        NORMAL -> "normal"
        FULL -> "full"
        else -> "off"
    }

    fun label(level: Int): String = when (level) {
        NORMAL -> "普通勿擾"
        FULL -> "完全勿擾"
        else -> "不勿擾"
    }

    val CHOICES = listOf("off" to "不勿擾", "normal" to "普通", "full" to "完全")

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("dnd", Context.MODE_PRIVATE)
    private fun nm(ctx: Context) = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    // ── 手動勿擾 ──
    fun manualLevel(ctx: Context): Int = prefs(ctx).getInt("manual_level", NONE)
    fun manualUntil(ctx: Context): Long = prefs(ctx).getLong("manual_until", 0L)
    fun manualActive(ctx: Context, now: Long = System.currentTimeMillis()) = manualLevel(ctx) > NONE && manualUntil(ctx) > now

    /** 上次手動選的等級,下次預設帶出來。 */
    fun lastLevel(ctx: Context): Int = prefs(ctx).getInt("last_level", NORMAL)

    fun startManual(ctx: Context, level: Int, untilMs: Long) {
        prefs(ctx).edit().putInt("manual_level", level).putLong("manual_until", untilMs).putInt("last_level", level).apply()
        apply(ctx)
    }

    fun stopManual(ctx: Context) {
        prefs(ctx).edit().remove("manual_level").remove("manual_until").apply()
        apply(ctx)
    }

    // ── 行程自動勿擾 ──
    fun eventLevel(ctx: Context, r: Reminder): Int = when {
        r.dnd.isNotEmpty() -> levelOf(r.dnd)
        r.tag == TIMETABLE_TAG -> levelOf(AppSettings.dndClass(ctx))
        r.endAt != null -> levelOf(AppSettings.dndMeeting(ctx))
        else -> NONE
    }

    data class State(val level: Int, val until: Long, val reason: String, val manual: Boolean)

    /** 此刻的勿擾狀態(手動和進行中的行程取最嚴格的)。exclude = 不算這筆(正在響的那筆)。 */
    fun current(ctx: Context, now: Long = System.currentTimeMillis(), exclude: Long = -1L): State {
        var best = State(NONE, 0L, "", false)
        if (manualActive(ctx, now)) best = State(manualLevel(ctx), manualUntil(ctx), "手動勿擾", true)
        val all = ReminderStore.load(ctx).filter { !it.done && it.id != exclude && it.endAt != null }
        Conflicts.spans(all, now, now).forEach { (r, s, e) ->
            if (e == null || s > now || now >= e || e - s > MAX_MEETING_MS) return@forEach
            val lv = eventLevel(ctx, r)
            if (lv > best.level || (lv == best.level && lv > NONE && e > best.until && !best.manual)) {
                best = State(lv, e, r.title, false)
            }
        }
        return best
    }

    fun policyGranted(ctx: Context): Boolean = try {
        nm(ctx).isNotificationPolicyAccessGranted
    } catch (e: Exception) {
        false
    }

    fun policyIntent(): Intent =
        Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** 依目前狀態開/關手機勿擾,並排好下一次要切換的時間。 */
    fun apply(ctx: Context) {
        val app = ctx.applicationContext
        try {
            val st = current(app)
            val p = prefs(app)
            val n = nm(app)
            if (policyGranted(app)) {
                if (st.level == FULL) {
                    if (n.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL) {
                        n.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                        p.edit().putBoolean("phone_set", true).apply()
                    }
                } else if (p.getBoolean("phone_set", false)) {
                    // 只關掉我們自己打開的手機勿擾,不動使用者自己開的
                    if (n.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL) {
                        n.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
                    }
                    p.edit().putBoolean("phone_set", false).apply()
                }
            }
            if (!manualActive(app) && manualLevel(app) > NONE) {
                p.edit().remove("manual_level").remove("manual_until").apply()
            }
            scheduleNext(app)
        } catch (e: Exception) {
            CrashLog.save(app, e)
        }
        VoiceWidget.refresh(app)
    }

    private fun scheduleNext(ctx: Context) {
        val now = System.currentTimeMillis()
        val times = mutableListOf<Long>()
        if (manualActive(ctx, now)) times += manualUntil(ctx)
        val all = ReminderStore.load(ctx).filter { !it.done && it.endAt != null && eventLevel(ctx, it) > NONE }
        Conflicts.spans(all, now, now + 2 * 86_400_000L).forEach { (_, s, e) ->
            if (e == null || e - s > MAX_MEETING_MS) return@forEach
            if (s > now) times += s
            if (e > now) times += e
        }
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(
            ctx, 7301, Intent(ctx, DndReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        am.cancel(pi)
        val next = times.minOrNull() ?: return
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next + 1000L, pi)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next + 1000L, pi)
        }
    }

    // ── 開勿擾前:這段時間會響的提醒 ──
    fun blocked(ctx: Context, from: Long, until: Long): List<Reminder> =
        ReminderStore.load(ctx)
            .filter { !it.done && it.triggerAt != null && it.triggerAt in from..until }
            .sortedBy { it.triggerAt }

    /** 提醒延到勿擾結束後(行程時間不變)。 */
    fun deferRings(ctx: Context, list: List<Reminder>, until: Long) {
        list.forEach { AlarmActions.snoozeUntil(ctx, it.id, until) }
    }

    /** 整個行程往後挪,讓提醒剛好落在勿擾結束時。 */
    fun shiftEvents(ctx: Context, list: List<Reminder>, until: Long) {
        list.forEach { r ->
            val t = r.triggerAt ?: return@forEach
            val delta = until - t
            val moved = r.copy(
                triggerAt = until,
                startAt = r.start + delta,
                endAt = r.endAt?.let { it + delta },
            )
            AlarmScheduler.cancel(ctx, r.id)
            ReminderStore.upsert(ctx, moved)
            AlarmScheduler.schedule(ctx, moved)
        }
    }

    fun hm(ms: Long): String =
        Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalTime().toString().take(5)
}

/** 勿擾開始/結束的時間點到了:重新判斷、同步手機勿擾。 */
class DndReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Dnd.apply(context)
    }
}
