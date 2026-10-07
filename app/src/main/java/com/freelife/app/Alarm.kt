package com.freelife.app

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.app.KeyguardManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

const val EXTRA_ID = "reminder_id"
private const val ACTION_FIRE = "com.freelife.app.FIRE"
private const val ACTION_DONE = "com.freelife.app.DONE"
private const val ACTION_SNOOZE = "com.freelife.app.SNOOZE"
private const val ACTION_LOUD = "com.freelife.app.LOUD"
private const val EXTRA_SNOOZE_MIN = "snooze_minutes"
private const val EXTRA_SNOOZE_UNTIL = "snooze_until"
private const val EXTRA_BUSY_ID = "busy_id"
/** 會議中先靜音;這麼久沒處理就改成正常響鈴,避免提醒被漏掉。 */
private const val CONFLICT_GRACE_MS = 3 * 60 * 1000L
private const val CHANNEL_ID = "alarm_v1"
private const val QUIET_CHANNEL_ID = "alarm_quiet_v1"
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
        PreMeetingScheduler.schedule(ctx, r)
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
        PreMeetingScheduler.cancel(ctx, id)
    }

    /** 開機或更新後重新排程;重複提醒錯過的次數直接略過,排到下一次。 */
    fun rescheduleAll(ctx: Context) {
        val now = System.currentTimeMillis()
        ReminderStore.load(ctx).forEach { r0 ->
            val r = if (r0.repeat.isNotEmpty()) Repeat.advance(r0, now) else r0
            if (r != r0) ReminderStore.upsert(ctx, r)
            schedule(ctx, r)
        }
    }
}

object AlarmNotifier {

    /** 建立兩個頻道:安靜頻道(平常用,聲音由 AlarmService 自己播)與帶聲音的備援頻道。 */
    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(QUIET_CHANNEL_ID) == null) {
            val quiet = NotificationChannel(QUIET_CHANNEL_ID, "鬧鐘畫面", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "鬧鐘響起時的常駐通知(聲音與震動由 App 自己播放,不會被下拉通知列或音量鍵關掉)"
                setSound(null, null)
                enableVibration(false)
                setBypassDnd(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            nm.createNotificationChannel(quiet)
        }
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val loud = NotificationChannel(CHANNEL_ID, "鬧鐘提醒(備援)", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "鬧鐘服務無法啟動時,改用通知本身的聲音提醒"
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
            nm.createNotificationChannel(loud)
        }
    }

    fun cancel(ctx: Context, id: Long) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(codeOf(id))
        AlarmService.stop(ctx)
    }

    /** 組出鬧鐘通知。fallback = true 時由通知自己響(帶聲音、持續響、10 分鐘後消失)。 */
    fun build(ctx: Context, r: Reminder, channelId: String, fallback: Boolean): Notification {
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
        val b = NotificationCompat.Builder(ctx, channelId)
            .setSmallIcon(R.drawable.ic_stat_bell)
            .setContentTitle(r.title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(full)
            .setFullScreenIntent(full, true)
            .addAction(0, "延後10分鐘", snooze)
            .addAction(0, "完成", done)
        if (fallback) b.setTimeoutAfter(AUTO_STOP_MS)
        val n = b.build()
        if (fallback) n.flags = n.flags or Notification.FLAG_INSISTENT
        return n
    }

    /** 會議中的靜音版通知:不響不跳全螢幕,給三個選項。 */
    fun buildConflict(ctx: Context, r: Reminder, busy: Reminder): Notification {
        val code = codeOf(r.id)
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val busyEnd = (busy.endAt ?: System.currentTimeMillis()) + 30_000L
        val after = Intent(ctx, ActionReceiver::class.java)
            .setAction(ACTION_SNOOZE).putExtra(EXTRA_ID, r.id).putExtra(EXTRA_SNOOZE_UNTIL, busyEnd)
        val loud = Intent(ctx, ActionReceiver::class.java).setAction(ACTION_LOUD).putExtra(EXTRA_ID, r.id)
        val close = Intent(ctx, ActionReceiver::class.java).setAction(ACTION_DONE).putExtra(EXTRA_ID, r.id)
        val open = PendingIntent.getActivity(ctx, code, Intent(ctx, MainActivity::class.java), flags)
        return NotificationCompat.Builder(ctx, QUIET_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_bell)
            .setContentTitle(r.title)
            .setContentText("「${busy.title}」進行中,先不響鈴(3 分鐘沒處理會響)")
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(open)
            .addAction(0, "會後再提醒", PendingIntent.getBroadcast(ctx, code, after, flags))
            .addAction(0, "照常響", PendingIntent.getBroadcast(ctx, code + 1, loud, flags))
            .addAction(0, "關閉", PendingIntent.getBroadcast(ctx, code + 2, close, flags))
            .build()
    }

    /** 備援:直接用通知響(AlarmService 啟動失敗時才會走到)。 */
    fun show(ctx: Context, r: Reminder) {
        ensureChannel(ctx)
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(codeOf(r.id), build(ctx, r, CHANNEL_ID, true))
    }
}

/**
 * 鬧鐘響鈴服務:自己用「鬧鐘音量」循環播放鈴聲並持續震動,
 * 所以下拉通知列、按音量鍵、橫幅消失都不會讓它停下,只有按「完成」或「延後」才會停(最久 10 分鐘)。
 */
class AlarmService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val autoStop = Runnable { stopSelf() }
    private val escalate = Runnable { escalateNow() }
    private var currentId = -1L
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var overlay: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getLongExtra(EXTRA_ID, -1L) ?: -1L
        val r = if (id >= 0) ReminderStore.get(this, id) else null
        if (r == null || r.done) {
            stopSelf()
            return START_NOT_STICKY
        }

        val busyId = intent?.getLongExtra(EXTRA_BUSY_ID, -1L) ?: -1L
        val busy = if (busyId >= 0) ReminderStore.get(this, busyId) else null
        currentId = id

        AlarmNotifier.ensureChannel(this)
        try {
            val n = if (busy != null) {
                AlarmNotifier.buildConflict(this, r, busy)
            } else {
                AlarmNotifier.build(this, r, QUIET_CHANNEL_ID, false)
            }
            ServiceCompat.startForeground(
                this,
                codeOf(id),
                n,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } catch (e: Exception) {
            CrashLog.save(this, e)
            AlarmNotifier.show(this, r)
            stopSelf()
            return START_NOT_STICKY
        }

        stopPlayback()
        if (busy != null) {
            // 會議中:不出聲,只震動一下並顯示橫幅;沒人處理就升級成正常響鈴
            startVibration(once = true)
            showOverlay(r, busy)
            handler.postDelayed(escalate, CONFLICT_GRACE_MS)
        } else {
            startSound()
            startVibration()
            showOverlay(r)
        }
        handler.postDelayed(autoStop, AUTO_STOP_MS)
        return START_NOT_STICKY
    }

    /** 靜音等待逾時:改成正常響鈴。 */
    private fun escalateNow() {
        val r = ReminderStore.get(this, currentId)
        if (r == null || r.done) {
            stopSelf()
            return
        }
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(codeOf(r.id), AlarmNotifier.build(this, r, QUIET_CHANNEL_ID, false))
        } catch (e: Exception) {
            CrashLog.save(this, e)
        }
        stopPlayback()
        startSound()
        startVibration()
        showOverlay(r)
        handler.postDelayed(autoStop, AUTO_STOP_MS)
    }

    private fun alarmAttributes(): AudioAttributes =
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

    private fun startSound() {
        // 依序嘗試:使用者設定的鬧鐘鈴聲 → 系統預設鬧鐘 → 預設通知音 → 預設鈴聲
        val candidates = listOfNotNull(
            AlarmSound.chosen(this),
            RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
        )
        var lastError: Exception? = null
        for (uri in candidates) {
            val mp = MediaPlayer()
            try {
                mp.setAudioAttributes(alarmAttributes())
                mp.setDataSource(this, uri)
                mp.isLooping = true
                mp.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK)
                val target = AppSettings.alarmVolume(this) / 100f
                val fade = AppSettings.alarmFade(this)
                mp.setVolume(if (fade) target * 0.15f else target, if (fade) target * 0.15f else target)
                mp.prepare()
                mp.start()
                player = mp
                if (fade) startFade(target)
                return
            } catch (e: Exception) {
                lastError = e
                mp.release()
            }
        }
        if (lastError != null) CrashLog.save(this, lastError)
    }

    private var fadeStep = 0
    private val fadeRunnable = object : Runnable {
        override fun run() {
            val mp = player ?: return
            fadeStep++
            val target = AppSettings.alarmVolume(this@AlarmService) / 100f
            val v = target * (0.15f + 0.85f * (fadeStep / 20f)).coerceAtMost(1f)
            try {
                mp.setVolume(v, v)
            } catch (ignored: Exception) {
            }
            if (fadeStep < 20) handler.postDelayed(this, 1000L)
        }
    }

    /** 20 秒內從小聲慢慢變到設定的音量。 */
    private fun startFade(target: Float) {
        fadeStep = 0
        handler.removeCallbacks(fadeRunnable)
        handler.postDelayed(fadeRunnable, 1000L)
    }

    @Suppress("DEPRECATION")
    private fun startVibration(once: Boolean = false) {
        try {
            val v: Vibrator = if (Build.VERSION.SDK_INT >= 31) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (v.hasVibrator()) {
                v.vibrate(
                    if (once) {
                        VibrationEffect.createWaveform(longArrayOf(0, 400, 200, 400), -1)
                    } else {
                        VibrationEffect.createWaveform(longArrayOf(0, 800, 600), 0)
                    },
                    alarmAttributes(),
                )
                vibrator = v
            }
        } catch (e: Exception) {
            CrashLog.save(this, e)
        }
    }

    /**
     * 螢幕亮著、沒鎖屏時(正在用手機),在畫面最上方畫一條不會自動消失的鬧鐘橫幅。
     * 需要「顯示在其他應用程式上層」權限;沒給權限就只會有系統通知。
     * 鎖屏或螢幕關著時由全螢幕通知負責。
     */
    private fun showOverlay(r: Reminder, busy: Reminder? = null) {
        removeOverlay()
        try {
            if (!Settings.canDrawOverlays(this)) return
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            if (!pm.isInteractive || km.isKeyguardLocked) return

            val density = resources.displayMetrics.density
            fun px(v: Int): Int = (v * density).toInt()

            fun makeButton(label: String, color: Int, onClick: () -> Unit): Button {
                val b = Button(this)
                b.text = label
                b.setTextColor(Color.WHITE)
                b.background = GradientDrawable().apply {
                    setColor(color)
                    cornerRadius = px(12).toFloat()
                }
                b.setOnClickListener { onClick() }
                return b
            }

            val appCtx = applicationContext
            val id = r.id

            val box = LinearLayout(this)
            box.orientation = LinearLayout.VERTICAL
            box.setPadding(px(16), px(12), px(16), px(12))
            box.background = GradientDrawable().apply {
                setColor(Color.argb(245, 32, 33, 36))
                cornerRadius = px(20).toFloat()
            }

            val title = TextView(this)
            title.text = "⏰ ${r.title}"
            title.setTextColor(Color.WHITE)
            title.textSize = 20f
            box.addView(title)

            val detail = when {
                busy != null -> "「${busy.title}」進行中,先不響鈴"
                r.location.isBlank() -> "時間到了"
                else -> "時間到了 · ${r.location}"
            }
            val sub = TextView(this)
            sub.text = detail
            sub.setTextColor(Color.rgb(200, 200, 200))
            sub.textSize = 14f
            box.addView(sub)

            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            val rowParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            rowParams.topMargin = px(8)
            val btnParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            btnParams.marginEnd = px(8)
            val btnParamsLast = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            if (busy != null) {
                val busyEnd = (busy.endAt ?: System.currentTimeMillis()) + 30_000L
                row.addView(
                    makeButton("會後再提醒", Color.rgb(60, 64, 67)) { AlarmActions.snoozeUntil(appCtx, id, busyEnd) },
                    btnParams,
                )
                row.addView(
                    makeButton("照常響", Color.rgb(180, 83, 9)) { AlarmService.start(appCtx, id) },
                    btnParams,
                )
                row.addView(
                    makeButton("關閉", Color.rgb(79, 70, 229)) { AlarmActions.done(appCtx, id) },
                    btnParamsLast,
                )
            } else {
                row.addView(
                    makeButton("延後 10 分鐘", Color.rgb(60, 64, 67)) { AlarmActions.snooze(appCtx, id, 10) },
                    btnParams,
                )
                row.addView(
                    makeButton("完成", Color.rgb(79, 70, 229)) { AlarmActions.done(appCtx, id) },
                    btnParamsLast,
                )
            }
            box.addView(row, rowParams)

            val statusBarId = resources.getIdentifier("status_bar_height", "dimen", "android")
            val statusBar = if (statusBarId > 0) resources.getDimensionPixelSize(statusBarId) else px(24)

            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            )
            lp.gravity = Gravity.TOP
            lp.y = statusBar + px(4)

            // 外層留左右邊距,橫幅才不會貼齊螢幕邊緣
            val wrapper = FrameLayout(this)
            wrapper.setPadding(px(8), 0, px(8), 0)
            wrapper.addView(box)

            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm.addView(wrapper, lp)
            overlay = wrapper
        } catch (e: Exception) {
            CrashLog.save(this, e)
        }
    }

    private fun removeOverlay() {
        val v = overlay ?: return
        try {
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v)
        } catch (ignored: Exception) {
            // 視窗已經不在了
        }
        overlay = null
    }

    private fun stopPlayback() {
        handler.removeCallbacks(autoStop)
        handler.removeCallbacks(escalate)
        handler.removeCallbacks(fadeRunnable)
        try {
            player?.stop()
        } catch (ignored: Exception) {
            // 尚未開始播放就停止時會丟例外,忽略即可
        }
        player?.release()
        player = null
        vibrator?.cancel()
        vibrator = null
        removeOverlay()
    }

    override fun onDestroy() {
        stopPlayback()
        super.onDestroy()
    }

    companion object {
        /** busyId = 正在進行的排程(開會中);帶了就先靜音問使用者。 */
        fun start(ctx: Context, id: Long, busyId: Long = -1L) {
            ContextCompat.startForegroundService(
                ctx,
                Intent(ctx, AlarmService::class.java)
                    .putExtra(EXTRA_ID, id)
                    .putExtra(EXTRA_BUSY_ID, busyId),
            )
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, AlarmService::class.java))
        }
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
        snoozeUntil(ctx, id, System.currentTimeMillis() + minutes * 60_000L)
    }

    /** 延後到指定時間(例如會議結束)。只改響鈴時間,開始與結束時間不變。 */
    fun snoozeUntil(ctx: Context, id: Long, atMs: Long) {
        AlarmNotifier.cancel(ctx, id)
        val r = ReminderStore.get(ctx, id) ?: return
        val at = maxOf(atMs, System.currentTimeMillis() + 60_000L)
        val updated = r.copy(triggerAt = at, done = false)
        ReminderStore.upsert(ctx, updated)
        AlarmScheduler.schedule(ctx, updated)
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_ID, -1L)
        if (id < 0) return
        var r = ReminderStore.get(context, id) ?: return
        if (r.done) return
        var ringId = id
        if (r.repeat.isNotEmpty()) {
            // 重複提醒:這一次用單次副本去響,系列本身往後移到下一次
            val now = System.currentTimeMillis()
            val clone = r.copy(id = Assistant.newId(), repeat = "", leadMin = 0, triggerAt = now, done = false)
            ReminderStore.upsert(context, clone)
            val next = Repeat.advance(Repeat.shift(r), now)
            ReminderStore.upsert(context, next)
            AlarmScheduler.schedule(context, next)
            r = clone
            ringId = clone.id
        }
        try {
            // 人正在開會(別的排程進行中)就先靜音問使用者;設定裡可以關掉
            val busy = if (AppSettings.conflictAsk(context)) {
                Conflicts.inProgress(ReminderStore.load(context), r, System.currentTimeMillis())
            } else {
                null
            }
            AlarmService.start(context, ringId, busy?.id ?: -1L)
        } catch (e: Exception) {
            CrashLog.save(context, e)
            AlarmNotifier.show(context, r)
        }
    }
}

class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_ID, -1L)
        if (id < 0) return
        when (intent.action) {
            ACTION_DONE -> AlarmActions.done(context, id)
            ACTION_SNOOZE -> {
                val until = intent.getLongExtra(EXTRA_SNOOZE_UNTIL, -1L)
                if (until > 0L) {
                    AlarmActions.snoozeUntil(context, id, until)
                } else {
                    AlarmActions.snooze(context, id, intent.getIntExtra(EXTRA_SNOOZE_MIN, 10))
                }
            }
            ACTION_LOUD -> try {
                AlarmService.start(context, id)
            } catch (e: Exception) {
                CrashLog.save(context, e)
            }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AlarmScheduler.rescheduleAll(context)
        BriefingScheduler.schedule(context)
    }
}
