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
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
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
        if (r.until != null && r.start > r.until) return true
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
            startAlert(r)
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
        startAlert(r)
        startVibration()
        showOverlay(r)
        handler.postDelayed(autoStop, AUTO_STOP_MS)
    }

    // ── 語音播報 ──
    // 用朗讀引擎把句子合成成 WAV(自己收集引擎吐出的聲音資料),再用「鬧鐘」音量播放並重複。
    // 不讓引擎自己播:三星引擎在背景直接念常常沒有聲音。合成不出來就換下一個引擎,全部不行才響鈴。
    private var synth: SpeechSynth? = null
    private var speechTexts: List<String> = emptyList()
    private val levelReady = BooleanArray(3)
    private var curLevel = -1
    private var startLevel = 0
    private var alertStart = 0L
    private var goodEngine: String? = null
    private var speechPlayer: MediaPlayer? = null
    private var speechActive = false
    private var savedAlarmVol = -1
    private val speechFallback = Runnable {
        // 9 秒內還沒合成好:先響鈴聲,絕不讓鬧鐘無聲;之後語音好了會換成語音
        if (speechActive && speechPlayer == null && player == null) {
            diag("9 秒內還沒合成好,先響鈴聲")
            startSound()
        }
    }

    /** 每念完一次,停 2.5 秒再念;響越久語氣越急(溫和 → 變急 → 催促)。 */
    private val replaySpeech = Runnable {
        if (!speechActive) return@Runnable
        val want = desiredLevel()
        val lv = (want downTo maxOf(curLevel, 0)).firstOrNull { levelReady[it] } ?: curLevel
        if (lv != curLevel && lv >= 0) {
            playLevel(lv)
        } else {
            speechPlayer?.let {
                try {
                    it.seekTo(0)
                    it.start()
                } catch (ignored: Exception) {
                }
            }
        }
    }

    private fun desiredLevel(): Int {
        val elapsed = System.currentTimeMillis() - alertStart
        val byTime = when {
            elapsed >= Roles.LEVEL3_AFTER_MS -> 2
            elapsed >= Roles.LEVEL2_AFTER_MS -> 1
            else -> 0
        }
        return maxOf(startLevel, byTime)
    }

    /** 同一件事被延後又響,記得是第幾次:第二次直接從「變急」開始,第三次起「催促」。 */
    private fun priorRings(r: Reminder): Int {
        val p = getSharedPreferences("alarm_rings", Context.MODE_PRIVATE)
        val key = "${r.id}_${r.start}"
        val count = if (p.getString("key", "") == key) p.getInt("count", 0) + 1 else 1
        p.edit().putString("key", key).putInt("count", count).apply()
        return count - 1
    }

    /** 依設定:語音播報 / 鈴聲 / 鈴聲加語音。語音不能用時自動改回鈴聲。 */
    private fun startAlert(r: Reminder) {
        boostAlarmVolume()
        when (AppSettings.alarmMode(this)) {
            "ring" -> startSound()
            "both" -> {
                startSound(quiet = true)
                startSpeech(r)
            }
            else -> startSpeech(r)
        }
    }

    private fun levelFile(lv: Int) = java.io.File(cacheDir, "alarm_speech_$lv.wav")

    /** 鬧鐘音量太小(常見 1/15)就暫時調大,結束後還原。 */
    private fun boostAlarmVolume() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            val aMax = am.getStreamMaxVolume(android.media.AudioManager.STREAM_ALARM)
            val aCur = am.getStreamVolume(android.media.AudioManager.STREAM_ALARM)
            val aWant = (aMax * AppSettings.alarmVolume(this) / 100f).toInt().coerceAtLeast((aMax * 0.7f).toInt())
            if (aCur < aWant) {
                if (savedAlarmVol < 0) savedAlarmVol = aCur
                am.setStreamVolume(android.media.AudioManager.STREAM_ALARM, aWant, 0)
            }
        } catch (e: Exception) {
            CrashLog.save(this, e)
        }
    }

    private fun restoreAlarmVolume() {
        if (savedAlarmVol < 0) return
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            am.setStreamVolume(android.media.AudioManager.STREAM_ALARM, savedAlarmVol, 0)
        } catch (ignored: Exception) {
        }
        savedAlarmVol = -1
    }

    private fun diag(msg: String) {
        SpeechDiag.add(this, msg)
    }

    private fun startSpeech(r: Reminder) {
        val now = System.currentTimeMillis()
        speechTexts = AlarmSpeech.lines(this, r, now)
        startLevel = priorRings(r).coerceIn(0, 2)
        alertStart = now
        levelReady.fill(false)
        curLevel = -1
        goodEngine = null
        SpeechDiag.begin(this, "鬧鐘")
        diag("角色:${Roles.active(this).label},從${Roles.LEVEL_NAMES[startLevel]}開始")
        speechActive = true
        handler.removeCallbacks(speechFallback)
        handler.postDelayed(speechFallback, 9000L)
        val engines = SpeechEngines.candidates(this)
        diag("引擎順序:" + engines.joinToString("→") { SpeechEngines.label(it) })
        trySynth(engines, 0)
    }

    private fun trySynth(engines: List<String?>, i: Int, attempt: Int = 0) {
        if (!speechActive) return
        if (i >= engines.size) {
            diag("所有引擎都合成不出聲音,改響鈴聲")
            if (player == null) startSound()
            return
        }
        val e = engines[i]
        val s = synth ?: SpeechSynth(this) { diag(it) }.also { synth = it }
        s.synth(e, speechTexts[startLevel], java.util.Locale.TAIWAN, levelFile(startLevel), 8000L) { res ->
            if (!speechActive) return@synth
            diag("[${SpeechEngines.label(e)}] ${if (res.ok) "成功" else "失敗"},${res.note}(${res.ms}ms)")
            if (res.ok) {
                if (e != null) AppSettings.setTtsEngine(this, e)
                goodEngine = e
                levelReady[startLevel] = true
                playLevel(startLevel)
                synthRest(startLevel + 1)
            } else if (attempt == 0) {
                // 引擎偶爾剛被叫醒時會拒絕第一次,等一秒換新連線再試一次
                s.release()
                handler.postDelayed({ trySynth(engines, i, 1) }, 1000L)
            } else {
                trySynth(engines, i + 1)
            }
        }
    }

    /** 第一段在播的同時,背景把後面更急的台詞也先合成好。 */
    private fun synthRest(lv: Int) {
        if (!speechActive || lv > 2) {
            synth?.release()
            return
        }
        val s = synth ?: return
        s.synth(goodEngine, speechTexts[lv], java.util.Locale.TAIWAN, levelFile(lv), 10_000L) { res ->
            if (!speechActive) return@synth
            if (res.ok) levelReady[lv] = true
            diag("${Roles.LEVEL_NAMES[lv]}:${if (res.ok) "準備好了" else "合成失敗,沿用前一段"}")
            synthRest(lv + 1)
        }
    }

    private fun playLevel(lv: Int) {
        if (!speechActive) return
        val f = levelFile(lv)
        releaseSpeechPlayer()
        // 先前因為等太久而響起的鈴聲,在「語音播報」模式下換成語音
        if (AppSettings.alarmMode(this) == "voice") stopRingOnly()
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            mp.setDataSource(f.absolutePath)
            mp.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK)
            // 催促階段開到最大聲;第一次開始念時,若設定了「漸強」就從小聲慢慢變大(和鈴聲一樣 20 秒)
            val v = if (lv >= 2) 1f else AppSettings.alarmVolume(this) / 100f
            val fadeIn = curLevel < 0 && lv < 2 && AppSettings.alarmFade(this)
            mp.setVolume(if (fadeIn) v * 0.15f else v, if (fadeIn) v * 0.15f else v)
            if (fadeIn) {
                speechFadeStep = 0
                handler.removeCallbacks(speechFade)
                handler.postDelayed(speechFade, 1000L)
            } else {
                handler.removeCallbacks(speechFade)
            }
            mp.setOnCompletionListener { handler.postDelayed(replaySpeech, 2500L) }
            mp.prepare()
            mp.start()
            speechPlayer = mp
            curLevel = lv
            handler.removeCallbacks(speechFallback)
            diag("播放${Roles.LEVEL_NAMES[lv]}")
        } catch (e: Exception) {
            diag("播放語音失敗:${e.message},改響鈴聲")
            CrashLog.save(this, e)
            mp.release()
            if (player == null) startSound()
        }
    }

    private fun stopRingOnly() {
        val p = player ?: return
        handler.removeCallbacks(fadeRunnable)
        try {
            p.stop()
        } catch (ignored: Exception) {
        }
        p.release()
        player = null
    }

    private fun releaseSpeechPlayer() {
        handler.removeCallbacks(replaySpeech)
        try {
            speechPlayer?.stop()
        } catch (ignored: Exception) {
        }
        speechPlayer?.release()
        speechPlayer = null
    }

    private fun stopSpeech() {
        speechActive = false
        handler.removeCallbacks(speechFade)
        handler.removeCallbacks(speechFallback)
        releaseSpeechPlayer()
        synth?.release()
        synth = null
        restoreAlarmVolume()
    }

    private fun alarmAttributes(): AudioAttributes =
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

    private fun startSound(quiet: Boolean = false) {
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
                val target = AppSettings.alarmVolume(this) / 100f * (if (quiet) 0.25f else 1f)
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

    private var speechFadeStep = 0
    private val speechFade = object : Runnable {
        override fun run() {
            val mp = speechPlayer ?: return
            if (curLevel >= 2) return
            speechFadeStep++
            val target = AppSettings.alarmVolume(this@AlarmService) / 100f
            val v = target * (0.15f + 0.85f * (speechFadeStep / 20f)).coerceAtMost(1f)
            try {
                mp.setVolume(v, v)
            } catch (ignored: Exception) {
            }
            if (speechFadeStep < 20) handler.postDelayed(this, 1000L)
        }
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
        stopSpeech()
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
        synth?.release()
        synth = null
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
            val now = System.currentTimeMillis()
            if ((r.until != null && r.start > r.until) ||
                (r.skipHolidays && Holidays.isHoliday(java.time.LocalDate.now()))
            ) {
                // 已過重複結束日,或今天是假日:這次不響,排到下一次
                val next = Repeat.advance(Repeat.shift(r), now)
                ReminderStore.upsert(context, next)
                AlarmScheduler.schedule(context, next)
                return
            }
            // 重複提醒:這一次用單次副本去響,系列本身往後移到下一次
            val clone = r.copy(
                id = Assistant.newId(), repeat = "", leadMin = 0, triggerAt = now, done = false,
                skipHolidays = false, until = null, tag = "",
            )
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
        DailyJobs.schedule(context)
    }
}

/** 鬧鐘要唸的話:依目前角色,三段台詞(溫和 → 變急 → 催促)。 */
object AlarmSpeech {
    private fun whenText(r: Reminder, nowMs: Long): String {
        val zone = java.time.ZoneId.systemDefault()
        val start = java.time.Instant.ofEpochMilli(r.start).atZone(zone).toLocalDateTime()
        val mins = ((r.start - nowMs) / 60_000L).toInt()
        return when {
            mins >= 1440 -> "明天${clock(start)}"
            mins >= 60 -> "${mins / 60}小時${if (mins % 60 > 0) "${mins % 60}分" else ""}後"
            mins >= 2 -> "${mins}分鐘後"
            mins <= -2 -> "已經開始了"
            else -> "現在"
        }
    }

    /** 三段台詞,第 0 段最溫和。 */
    fun lines(ctx: Context, r: Reminder, nowMs: Long): List<String> {
        val zone = java.time.ZoneId.systemDefault()
        val code = AppSettings.tone(ctx)
        val addr = Roles.address(ctx, code)
        val w = whenText(r, nowMs)
        val end = r.endAt?.let { clock(java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDateTime()) }
        return (0..2).map { lv -> Roles.fill(Roles.line(ctx, code, lv), addr, w, r.title.trim(), r.location.trim(), end) }
    }

    /** 第一段(相容舊呼叫)。 */
    fun text(ctx: Context, r: Reminder, nowMs: Long): String = lines(ctx, r, nowMs)[0]

    /** 設定頁試聽用的例句。 */
    fun sample(ctx: Context, level: Int = 0, code: String = AppSettings.tone(ctx)): String =
        Roles.fill(Roles.line(ctx, code, level), Roles.address(ctx, code), "10分鐘後", "開會", "三樓會議室", "下午三點")

    /** 18:30 → 「晚上六點半」,唸起來比較自然。 */
    fun clock(t: java.time.LocalDateTime): String {
        val h = t.hour
        val period = when (h) {
            in 0..4 -> "凌晨"
            in 5..11 -> "早上"
            12 -> "中午"
            in 13..17 -> "下午"
            else -> "晚上"
        }
        val h12 = if (h % 12 == 0) 12 else h % 12
        val m = when (t.minute) {
            0 -> ""
            30 -> "半"
            else -> "${t.minute}分"
        }
        return "$period${h12}點$m"
    }
}
