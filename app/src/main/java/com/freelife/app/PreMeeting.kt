package com.freelife.app

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

private const val ACTION_PRE = "com.freelife.app.PRE_MEETING"
private const val PRE_LEAD_MS = 10 * 60 * 1000L
private const val HEADS_UP_CHANNEL = "heads_up_v1"

private fun preCode(id: Long): Int = (id and 0x7fffffffL).toInt()

/**
 * 開會前 10 分鐘的主動警告:排程開始前,如果期間有別的提醒會響,
 * 先通知「這段時間有哪些提醒會被靜音」,讓你有機會改時間或關掉。
 */
object PreMeetingScheduler {

    private fun pending(ctx: Context, id: Long): PendingIntent {
        val intent = Intent(ctx, PreMeetingReceiver::class.java)
            .setAction(ACTION_PRE)
            .putExtra(EXTRA_ID, id)
        return PendingIntent.getBroadcast(
            ctx, preCode(id), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    fun cancel(ctx: Context, id: Long) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pending(ctx, id))
    }

    fun schedule(ctx: Context, r: Reminder) {
        cancel(ctx, r.id)
        val end = r.endAt ?: return
        if (r.done || end - r.start > MAX_MEETING_MS) return
        val at = r.start - PRE_LEAD_MS
        if (at <= System.currentTimeMillis()) return
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(ctx, r.id))
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(ctx, r.id))
        }
    }
}

class PreMeetingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_ID, -1L)
        if (id < 0 || !AppSettings.conflictAsk(context)) return
        val meeting = ReminderStore.get(context, id) ?: return
        val end = meeting.endAt ?: return
        if (meeting.done) return

        // 會議期間會響的其他提醒
        val hits = ReminderStore.load(context).filter { o ->
            val t = o.triggerAt
            !o.done && o.id != meeting.id && t != null && t >= meeting.start && t < end
        }.sortedBy { it.triggerAt }
        if (hits.isEmpty()) return

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(HEADS_UP_CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(HEADS_UP_CHANNEL, "開會前提醒", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "排程開始前,告訴你期間有哪些提醒會被靜音"
                },
            )
        }
        val names = hits.take(3).joinToString("、") { "「${it.title}」" }
        val more = if (hits.size > 3) " 等 ${hits.size} 項" else ""
        val open = PendingIntent.getActivity(
            context, preCode(id), Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, HEADS_UP_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_bell)
            .setContentTitle("${AppSettings.address(context)},10 分鐘後:${meeting.title}")
            .setContentText("${formatRange(meeting.start, end)},期間有提醒會先靜音:$names$more")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "${formatRange(meeting.start, end)}\n期間有提醒會先靜音:$names$more\n點開可以改時間或關閉。",
                ),
            )
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        nm.notify(preCode(id) xor 0x20000000, n)
    }
}
