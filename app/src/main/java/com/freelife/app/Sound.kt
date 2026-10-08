package com.freelife.app

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Handler
import android.os.Looper

/** 鬧鐘鈴聲:使用者挑的聲音(沒挑就用系統預設鬧鐘)、音量、漸強。 */
object AlarmSound {
    /** 內建的賈維斯風格鈴聲(原創合成):代號、名稱、說明。 */
    val BUILTIN = listOf(
        Triple("jarvis_boot", "系統啟動", "HUD 開機,上行琶音"),
        Triple("jarvis_scan", "HUD 掃描", "掃頻加數位確認音"),
        Triple("jarvis_reactor", "反應爐警示", "低頻脈動加警報,最有喚醒力"),
        Triple("jarvis_chime", "輕柔通知", "兩聲鐘響,溫和"),
    )

    private const val PREFIX = "builtin:"

    fun builtinKey(ctx: Context): String? =
        AppSettings.alarmSound(ctx).takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)

    fun chosen(ctx: Context): Uri? {
        val s = AppSettings.alarmSound(ctx)
        if (s.isBlank()) return null
        if (s.startsWith(PREFIX)) {
            val id = ctx.resources.getIdentifier(s.removePrefix(PREFIX), "raw", ctx.packageName)
            return if (id != 0) Uri.parse("android.resource://${ctx.packageName}/$id") else null
        }
        return Uri.parse(s)
    }

    fun title(ctx: Context): String {
        builtinKey(ctx)?.let { k -> BUILTIN.firstOrNull { it.first == k }?.let { return "賈維斯・${it.second}" } }
        val u = chosen(ctx) ?: return "系統預設鬧鐘"
        return try {
            RingtoneManager.getRingtone(ctx, u)?.getTitle(ctx) ?: "自訂鈴聲"
        } catch (e: Exception) {
            "自訂鈴聲"
        }
    }

    private var preview: MediaPlayer? = null
    private val main = Handler(Looper.getMainLooper())
    private val stopper = Runnable { stopPreview() }

    fun stopPreview() {
        main.removeCallbacks(stopper)
        try {
            preview?.stop()
        } catch (ignored: Exception) {
        }
        preview?.release()
        preview = null
    }

    /** 試聽 5 秒,用鬧鐘音量大小播放(和真的響起時一樣)。 */
    fun preview(ctx: Context) {
        stopPreview()
        val candidates = listOfNotNull(
            chosen(ctx),
            RingtoneManager.getActualDefaultRingtoneUri(ctx, RingtoneManager.TYPE_ALARM),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
        )
        val v = AppSettings.alarmVolume(ctx) / 100f
        for (uri in candidates) {
            val mp = MediaPlayer()
            try {
                mp.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                mp.setDataSource(ctx, uri)
                mp.setVolume(v, v)
                mp.prepare()
                mp.start()
                preview = mp
                main.postDelayed(stopper, 5000L)
                return
            } catch (e: Exception) {
                mp.release()
            }
        }
    }
}

/** 語音辨識結果的整理:挑最像「記事」的那個候選,並修正常見的口語寫法。 */
object VoiceFix {
    private val CJK_GAP = Regex("(?<=[\\u4e00-\\u9fff0-9]) +(?=[\\u4e00-\\u9fff0-9])")

    fun clean(s: String): String = CJK_GAP.replace(s.trim(), "")

    /** 候選裡第一個有讀到日期或時間的;都沒有就用第一個。 */
    fun best(candidates: List<String>, now: java.time.LocalDateTime): String {
        val cleaned = candidates.map { clean(it) }.filter { it.isNotEmpty() }
        if (cleaned.isEmpty()) return ""
        for (c in cleaned) {
            val d = ReminderParser.parse(c, now)
            if (d.date != null || d.time != null) return c
        }
        return cleaned.first()
    }

    /** 提示辨識引擎可能會出現的詞(Android 13 以上有效,其他版本會忽略)。 */
    fun hints(titles: List<String>): ArrayList<String> {
        val base = listOf(
            "提醒我", "明天", "後天", "大後天", "下週", "下下週", "每天", "平日", "每週", "每月",
            "上午", "下午", "晚上", "中午", "點半", "分鐘", "小時", "提前", "開會", "看診", "餐督", "勤務",
        )
        return ArrayList((base + titles.take(20)).distinct())
    }
}
