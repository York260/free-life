package com.freelife.app

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

private val HM_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

private fun ms(t: LocalDateTime): Long = t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
private fun local(m: Long): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(m), ZoneId.systemDefault())

/** 在 App 外(通知按鈕)也能用的資料操作:完成、改到明天、刪除、新增。 */
object ReminderOps {
    fun add(ctx: Context, r: Reminder) {
        ReminderStore.upsert(ctx, r)
        AlarmScheduler.schedule(ctx, r)
    }

    fun done(ctx: Context, r: Reminder) {
        ReminderStore.upsert(ctx, r.copy(done = true))
        AlarmScheduler.cancel(ctx, r.id)
        AlarmNotifier.cancel(ctx, r.id)
    }

    fun delete(ctx: Context, r: Reminder) {
        AlarmScheduler.cancel(ctx, r.id)
        AlarmNotifier.cancel(ctx, r.id)
        ReminderStore.delete(ctx, r.id)
    }

    /** 同一個時間挪到明天(過時的就從今天的同一時刻算到明天)。 */
    fun shiftedToTomorrow(r: Reminder, nowMs: Long = System.currentTimeMillis()): Reminder {
        var startDt = local(r.start).plusDays(1)
        val today = local(nowMs).toLocalDate()
        while (!startDt.toLocalDate().isAfter(today)) startDt = startDt.plusDays(1)
        val newStart = ms(startDt)
        val delta = newStart - r.start
        val ring = r.triggerAt?.let { if (r.leadMin > 0) newStart - r.leadMin * 60_000L else newStart }
        return r.copy(startAt = newStart, endAt = r.endAt?.let { it + delta }, triggerAt = ring, done = false)
    }

    fun moveToTomorrow(ctx: Context, r: Reminder) {
        val moved = shiftedToTomorrow(r)
        AlarmScheduler.cancel(ctx, r.id)
        AlarmNotifier.cancel(ctx, r.id)
        ReminderStore.upsert(ctx, moved)
        AlarmScheduler.schedule(ctx, moved)
    }

    /** 過時還沒完成的(最近三天、不含重複與只記錄)。 */
    fun overdue(all: List<Reminder>, nowMs: Long): List<Reminder> =
        all.filter {
            !it.done && it.repeat.isEmpty() && it.hasTime && !it.ringless &&
                (it.endAt ?: it.start) < nowMs && it.start > nowMs - 3 * 86_400_000L
        }.sortedBy { it.start }
}

/** 睡前預告:明天第一件、建議起床與就寢時間、今晚的最後一件。 */
data class NightPlan(
    val day: LocalDate,
    val first: Occ?,
    val wake: LocalDateTime?,
    val bed: LocalDateTime?,
    val warn: String?,
) {
    companion object {
        fun compute(ctx: Context, all: List<Reminder>, now: LocalDateTime): NightPlan {
            // 過了午夜還沒睡,「明天」指的是今天
            val day = if (now.hour < 4) now.toLocalDate() else now.toLocalDate().plusDays(1)
            val first = ScheduleModel.occurrences(all, day, day.plusDays(1))
                .filter { !it.r.done && !it.r.title.startsWith("起床") }
                .minByOrNull { it.start }
            if (first == null) return NightPlan(day, null, null, null, null)
            val wake = first.start.minusMinutes(AppSettings.prepMinutes(ctx).toLong())
            val bed = wake.minusMinutes(AppSettings.sleepMinutes(ctx).toLong() + 15)
            val last = ScheduleModel.occurrences(all, day.minusDays(1), day)
                .filter { !it.r.done && it.effectiveEnd.isAfter(now) }
                .maxByOrNull { it.effectiveEnd }
            val warn = when {
                last != null && last.effectiveEnd.isAfter(bed) ->
                    "今晚「${last.r.title}」到 ${last.effectiveEnd.format(HM_FMT)},睡眠會不夠,忙完盡快休息"
                bed.isBefore(now) -> "已經過了建議的就寢時間,盡快休息"
                else -> null
            }
            return NightPlan(day, first, wake, bed, warn)
        }
    }

    fun message(ctx: Context): String {
        val f = first ?: return "明天沒有排行程,早點休息。"
        val sb = StringBuilder()
        sb.append("明天第一件是 ${f.start.format(HM_FMT)} ${f.r.title}。")
        if (wake != null && bed != null) {
            sb.append("建議 ${wake.format(HM_FMT)} 起床、${bed.format(HM_FMT)} 前就寢。")
        }
        if (warn != null) sb.append(warn).append("。")
        return sb.toString()
    }

    /** 一鍵設定的起床鬧鐘;已經設過同一時間就回傳 null。 */
    fun wakeReminder(all: List<Reminder>): Reminder? {
        val w = wake ?: return null
        val f = first ?: return null
        val at = ms(w)
        if (at <= System.currentTimeMillis()) return null
        if (all.any { !it.done && it.triggerAt == at && it.title.startsWith("起床") }) return null
        return Reminder(
            id = Assistant.newId(),
            title = "起床(${f.start.format(HM_FMT)} ${f.r.title})",
            triggerAt = at,
            startAt = at,
        )
    }
}

/** 睡前預告、傍晚追問、每週回顧的排程。 */
object DailyJobs {
    const val ACTION_NIGHT = "com.freelife.app.NIGHT"
    const val ACTION_EVENING = "com.freelife.app.EVENING"
    const val ACTION_WEEKLY = "com.freelife.app.WEEKLY"
    const val ACTION_WAKE = "com.freelife.app.WAKE_SET"
    const val ACTION_DONE = "com.freelife.app.ITEM_DONE"
    const val ACTION_TOMORROW = "com.freelife.app.ITEM_TOMORROW"
    const val ACTION_DELETE = "com.freelife.app.ITEM_DELETE"
    const val EXTRA_ID = "id"
    const val EXTRA_OPEN_REVIEW = "open_review"

    private const val CH_NIGHT = "night_v2"
    private const val CH_EVENING = "evening_v2"
    private const val CH_WEEKLY = "weekly_v2"
    private const val ID_NIGHT = 7201
    private const val ID_WEEKLY = 7202

    private fun broadcast(ctx: Context, action: String, code: Int, id: Long = -1L): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, code,
            Intent(ctx, DailyReceiver::class.java).setAction(action).putExtra(EXTRA_ID, id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun at(ctx: Context, whenMs: Long, pi: PendingIntent) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMs, pi)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMs, pi)
        }
    }

    private fun nextDaily(now: LocalDateTime, minutes: Int): Long {
        val t = LocalDateTime.of(now.toLocalDate(), LocalTime.of(minutes / 60, minutes % 60))
        return ms(if (t.isAfter(now)) t else t.plusDays(1))
    }

    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val now = LocalDateTime.now()
        val night = broadcast(ctx, ACTION_NIGHT, 7101)
        val evening = broadcast(ctx, ACTION_EVENING, 7102)
        val weekly = broadcast(ctx, ACTION_WEEKLY, 7103)
        am.cancel(night)
        am.cancel(evening)
        am.cancel(weekly)
        if (AppSettings.nightEnabled(ctx)) at(ctx, nextDaily(now, AppSettings.nightMinutes(ctx)), night)
        if (AppSettings.eveningEnabled(ctx)) at(ctx, nextDaily(now, AppSettings.eveningMinutes(ctx)), evening)
        if (AppSettings.weeklyEnabled(ctx)) {
            var t = LocalDateTime.of(now.toLocalDate().with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)), LocalTime.of(20, 0))
            if (!t.isAfter(now)) t = t.plusWeeks(1)
            at(ctx, ms(t), weekly)
        }
    }

    private fun channel(ctx: Context, id: String, name: String) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(id) == null) {
            nm.createNotificationChannel(NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH))
        }
    }

    private fun openApp(ctx: Context, code: Int, review: Boolean = false): PendingIntent =
        PendingIntent.getActivity(
            ctx, code,
            Intent(ctx, MainActivity::class.java)
                .putExtra(EXTRA_OPEN_REVIEW, review)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    fun showNight(ctx: Context) {
        channel(ctx, CH_NIGHT, "睡前預告明天")
        val all = ReminderStore.load(ctx)
        val plan = NightPlan.compute(ctx, all, LocalDateTime.now())
        val b = NotificationCompat.Builder(ctx, CH_NIGHT)
            .setSmallIcon(R.drawable.ic_stat_bell)
            .setContentTitle("${AppSettings.address(ctx)},明天的預告")
            .setContentText(plan.message(ctx))
            .setStyle(NotificationCompat.BigTextStyle().bigText(plan.message(ctx)))
            .setContentIntent(openApp(ctx, 7111))
            .setAutoCancel(true)
        val w = plan.wake
        if (w != null && plan.wakeReminder(all) != null) {
            b.addAction(0, "設 ${w.format(HM_FMT)} 起床鬧鐘", broadcast(ctx, ACTION_WAKE, 7112))
        }
        nm(ctx).notify(ID_NIGHT, b.build())
    }

    private fun itemCode(id: Long, slot: Int) = 80_000 + ((id % 5_000).toInt() * 4) + slot

    fun showEvening(ctx: Context): Int {
        channel(ctx, CH_EVENING, "追問過時沒完成的事")
        val now = System.currentTimeMillis()
        val items = ReminderOps.overdue(ReminderStore.load(ctx), now).takeLast(4)
        val today = LocalDate.now()
        for (r in items) {
            val s = local(r.start)
            val whenText = when (s.toLocalDate()) {
                today -> if (s.hour >= 12) "下午" else "早上"
                today.minusDays(1) -> "昨天"
                else -> "${s.monthValue}/${s.dayOfMonth}"
            }
            val n = NotificationCompat.Builder(ctx, CH_EVENING)
                .setSmallIcon(R.drawable.ic_stat_bell)
                .setContentTitle("$whenText ${s.format(HM_FMT)} 的「${r.title}」完成了嗎?")
                .setContentText("按一下處理,事情不會默默留在清單裡。")
                .setContentIntent(openApp(ctx, itemCode(r.id, 0)))
                .setAutoCancel(true)
                .addAction(0, "完成了", broadcast(ctx, ACTION_DONE, itemCode(r.id, 1), r.id))
                .addAction(0, "改到明天", broadcast(ctx, ACTION_TOMORROW, itemCode(r.id, 2), r.id))
                .addAction(0, "刪除", broadcast(ctx, ACTION_DELETE, itemCode(r.id, 3), r.id))
                .build()
            nm(ctx).notify(itemCode(r.id, 0), n)
        }
        return items.size
    }

    fun showWeekly(ctx: Context) {
        channel(ctx, CH_WEEKLY, "每週回顧")
        val today = LocalDate.now()
        val stats = WeekStats.compute(ReminderStore.load(ctx), today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)))
        val text = "完成 ${stats.done}/${stats.total} 件,排程 ${stats.busyHoursText()}" +
            (stats.busiestLabel()?.let { ",最忙是$it" } ?: "") + "。點開看建議。"
        val n = NotificationCompat.Builder(ctx, CH_WEEKLY)
            .setSmallIcon(R.drawable.ic_stat_bell)
            .setContentTitle("${AppSettings.address(ctx)},這週回顧")
            .setContentText(text)
            .setContentIntent(openApp(ctx, 7121, review = true))
            .setAutoCancel(true)
            .build()
        nm(ctx).notify(ID_WEEKLY, n)
    }

    fun cancelItem(ctx: Context, id: Long) = nm(ctx).cancel(itemCode(id, 0))

    fun cancelNight(ctx: Context) = nm(ctx).cancel(ID_NIGHT)

    private fun nm(ctx: Context) = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
}

class DailyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(DailyJobs.EXTRA_ID, -1L)
        when (intent.action) {
            DailyJobs.ACTION_NIGHT -> {
                DailyJobs.schedule(context)
                DailyJobs.showNight(context)
            }
            DailyJobs.ACTION_EVENING -> {
                DailyJobs.schedule(context)
                DailyJobs.showEvening(context)
            }
            DailyJobs.ACTION_WEEKLY -> {
                DailyJobs.schedule(context)
                DailyJobs.showWeekly(context)
            }
            DailyJobs.ACTION_WAKE -> {
                val all = ReminderStore.load(context)
                NightPlan.compute(context, all, LocalDateTime.now()).wakeReminder(all)?.let {
                    ReminderOps.add(context, it)
                }
                DailyJobs.cancelNight(context)
            }
            DailyJobs.ACTION_DONE, DailyJobs.ACTION_TOMORROW, DailyJobs.ACTION_DELETE -> {
                val r = ReminderStore.get(context, id)
                if (r != null) {
                    when (intent.action) {
                        DailyJobs.ACTION_DONE -> ReminderOps.done(context, r)
                        DailyJobs.ACTION_TOMORROW -> ReminderOps.moveToTomorrow(context, r)
                        else -> ReminderOps.delete(context, r)
                    }
                }
                DailyJobs.cancelItem(context, id)
            }
        }
        VoiceWidget.refresh(context)
    }
}

/** 一週的統計:用在每週回顧。 */
data class WeekStats(
    val weekStart: LocalDate,
    val total: Int,
    val done: Int,
    val overdue: Int,
    val busyMinutes: Long,
    val meetingMinutes: Long,
    val perDayMinutes: List<Long>,
    val perDayCount: List<Int>,
    val freeAfternoons: List<Int>,
) {
    fun busyHoursText(): String = hoursText(busyMinutes)

    fun busiestIndex(): Int? = perDayMinutes.indices.maxByOrNull { perDayMinutes[it] * 1000 + perDayCount[it] }
        ?.takeIf { perDayMinutes[it] > 0 || perDayCount[it] > 0 }

    fun busiestLabel(): String? = busiestIndex()?.let { "週" + WEEKDAY_CHARS[it] }

    companion object {
        private val MEETING = Regex("會|會議|開會|簡報|座談|研習|訓練|勤教|值班")

        fun hoursText(m: Long): String =
            if (m % 60 == 0L) "${m / 60} 小時" else "%.1f 小時".format(m / 60.0)

        fun compute(all: List<Reminder>, weekStart: LocalDate): WeekStats {
            val occs = ScheduleModel.occurrences(all, weekStart, weekStart.plusDays(7))
            val now = LocalDateTime.now()
            val perMin = LongArray(7)
            val perCnt = IntArray(7)
            var meeting = 0L
            for (o in occs) {
                val i = Duration.between(weekStart.atStartOfDay(), o.start).toDays().toInt().coerceIn(0, 6)
                perCnt[i]++
                val e = o.end
                if (e != null) {
                    val m = Duration.between(o.start, e).toMinutes().coerceIn(0, 16 * 60)
                    perMin[i] += m
                    if (MEETING.containsMatchIn(o.r.title)) meeting += m
                }
            }
            val free = (0..6).filter { i ->
                val d = weekStart.plusDays(i.toLong())
                d.dayOfWeek.value <= 5 &&
                    ScheduleModel.freeGaps(
                        ScheduleModel.forDay(occs, d),
                        LocalDateTime.of(d, LocalTime.of(13, 30)),
                        LocalDateTime.of(d, LocalTime.of(17, 30)),
                        180,
                    ).isNotEmpty()
            }
            return WeekStats(
                weekStart = weekStart,
                total = occs.size,
                done = occs.count { it.r.done },
                overdue = occs.count { !it.r.done && it.r.repeat.isEmpty() && !it.r.ringless && it.effectiveEnd.isBefore(now) },
                busyMinutes = perMin.sum(),
                meetingMinutes = meeting,
                perDayMinutes = perMin.toList(),
                perDayCount = perCnt.toList(),
                freeAfternoons = free,
            )
        }
    }

    /** 規則產生的優化建議(不用 AI)。 */
    fun suggestions(): List<String> {
        val out = mutableListOf<String>()
        if (total == 0) return listOf("這週沒有記錄行程。下週試著把固定的事(值班、開會)先排進來,空檔才看得清楚。")
        val rate = if (total > 0) done * 100 / total else 0
        if (rate < 70) out += "完成率 $rate%。下週每天的小任務控制在 3 件內,做不完的晚上直接改到明天。"
        if (overdue > 0) out += "有 $overdue 件過時沒處理。傍晚的追問通知出現時,花 10 秒點掉它們。"
        busiestIndex()?.let { i ->
            if (perDayMinutes[i] >= 8 * 60) {
                out += "週${WEEKDAY_CHARS[i]}排了 ${hoursText(perDayMinutes[i])},太滿了。下週把同類型的事分散到兩天。"
            }
        }
        if (meetingMinutes >= 10 * 60) out += "開會和值勤類花了 ${hoursText(meetingMinutes)}。可以在會議之間留 15 分鐘緩衝,避免一直趕場。"
        if (freeAfternoons.isNotEmpty()) {
            val days = freeAfternoons.joinToString("、") { "週" + WEEKDAY_CHARS[it] }
            out += "$days 下午有 3 小時以上的空檔,適合排需要專心的工作或讀書。"
        }
        if (out.isEmpty()) out += "節奏不錯,完成率 $rate%。維持每天睡前看一眼明天的預告。"
        return out
    }
}
