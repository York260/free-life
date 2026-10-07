package com.freelife.app

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat

const val EXTRA_ID = "reminder_id"
private const val ACTION_FIRE = "com.freelife.app.FIRE"
private const val ACTION_DONE = "com.freelife.app.DONE"
private const val ACTION_SNOOZE = "com.freelife.app.SNOOZE"
private const val EXTRA_SNOOZE_MIN = "snooze_minutes"
private const val CHANNEL_ID = "alarm_v1"
private const val AUTO_STOP_MS = 10 * 60 * 1000L

/** requestCode / 通知編號:把 Long id 壓成正的 Int。 */
private fun codeOf(id: Long): Int = (id and 0x7fffffffL).toInt()

/** 用 AlarmManager.setAlarmClock 排程:跟系統鬧鐘同等級,Doze 省電模式下也會準時觸發。 */
object AlarmScheduler {

    private fun firePending(ctx: Context, id: Long): PendingIntent {
        val intent = Intent(ctx, AlarmReceiver::class.java)
            .setAction(ACTION_FIRE)
            .putExtra(EXTRA_ID, id)
        return PendingIntent.getBroadcast(
            ctx, codeOf(id), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /** Android 12 以上必須持有「鬧鐘與提醒」權限才能排精確鬧鐘。 */
    fun canScheduleExact(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return am.canScheduleExactAlarms()
    }

    /** 排程成功(或不需要排程)回傳 true;沒有權限等原因失敗時回傳 false,不會讓 App 當機。 */
    fun schedule(ctx: Context, r: Reminder): Boolean {
        val at = r.triggerAt ?: return true
        if (r.done || at <= System.currentTimeMillis()) return true
        return try {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val show = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), firePending(ctx, r.id))
            true
        } catch (e: SecurityException) {
            CrashLog.save(ctx, e)
            false
        }
    }

    fun cancel(ctx: Context, id: Long) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(firePending(ctx, id))
    }

    fun rescheduleAll(ctx: Context) {
        ReminderStore.load(ctx).forEach { schedule(ctx, it) }
    }
}

object AlarmNotifier {

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(CHANNEL_ID, "鬧鐘提醒", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "到時間用鬧鐘音量響鈴,靜音與勿擾模式下仍會提醒"
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 600, 400, 600, 400, 600)
            setBypassDnd(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(channel)
    }

    fun cancel(ctx: Context, id: Long) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(codeOf(id))
    }

    fun show(ctx: Context, r: Reminder) {
        ensureChannel(ctx)
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val code = codeOf(r.id)
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

        val full = PendingIntent.getActivity(
            ctx, code,
            Intent(ctx, AlarmActivity::class.java)
                .putExtra(EXTRA_ID, r.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            flags,
        )
        val done = PendingIntent.getBroadcast(
            ctx, code,
            Intent(ctx, ActionReceiver::class.java).setAction(ACTION_DONE).putExtra(EXTRA_ID, r.id),
            flags,
        )
        val snooze = PendingIntent.getBroadcast(
            ctx, code,
            Intent(ctx, ActionReceiver::class.java)
                .setAction(ACTION_SNOOZE)
                .putExtra(EXTRA_ID, r.id)
                .putExtra(EXTRA_SNOOZE_MIN, 10),
            flags,
        )

        val text = if (r.location.isBlank()) "時間到了" else "時間到了 · ${r.location}"
        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_bell)
            .setContentTitle(r.title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setTimeoutAfter(AUTO_STOP_MS)
            .setContentIntent(full)
            .setFullScreenIntent(full, true)
            .addAction(0, "延後10分鐘", snooze)
            .addAction(0, "完成", done)
            .build()
        // 鈴聲持續響到按下「完成」或「延後」(最久 10 分鐘)
        n.flags = n.flags or Notification.FLAG_INSISTENT
        nm.notify(code, n)
    }
}

/** 完成 / 延後 的共用動作,通知按鈕與鬧鐘畫面都用它。 */
object AlarmActions {

    fun done(ctx: Context, id: Long) {
        AlarmNotifier.cancel(ctx, id)
        AlarmScheduler.cancel(ctx, id)
        val r = ReminderStore.get(ctx, id) ?: return
        ReminderStore.upsert(ctx, r.copy(done = true))
    }

    fun snooze(ctx: Context, id: Long, minutes: Int) {
        AlarmNotifier.cancel(ctx, id)
        val r = ReminderStore.get(ctx, id) ?: return
        val updated = r.copy(triggerAt = System.currentTimeMillis() + minutes * 60_000L, done = false)
        ReminderStore.upsert(ctx, updated)
        AlarmScheduler.schedule(ctx, updated)
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_ID, -1L)
        if (id < 0) return
        val r = ReminderStore.get(context, id) ?: return
        if (r.done) return
        AlarmNotifier.show(context, r)
    }
}

class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_ID, -1L)
        if (id < 0) return
        when (intent.action) {
            ACTION_DONE -> AlarmActions.done(context, id)
            ACTION_SNOOZE -> AlarmActions.snooze(context, id, intent.getIntExtra(EXTRA_SNOOZE_MIN, 10))
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AlarmScheduler.rescheduleAll(context)
    }
}
