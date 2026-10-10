package com.freelife.app

import android.content.Context

/** 目前預設的 Claude 模型(小而快,每天一次的建議很省錢)。可在設定頁修改。 */
const val DEFAULT_MODEL = "claude-haiku-4-5-20251001"

/** App 設定,存在手機本機。 */
object AppSettings {
    private const val PREF = "settings"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun briefingEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean("briefing_enabled", true)

    fun setBriefingEnabled(ctx: Context, value: Boolean) {
        prefs(ctx).edit().putBoolean("briefing_enabled", value).apply()
    }

    /** 每日確認的時間,以「當天第幾分鐘」表示,預設 07:30。 */
    fun briefingMinutes(ctx: Context): Int = prefs(ctx).getInt("briefing_minutes", 7 * 60 + 30)

    fun setBriefingMinutes(ctx: Context, value: Int) {
        prefs(ctx).edit().putInt("briefing_minutes", value).apply()
    }

    /** 提醒響起時如果人正在開會(別的排程進行中),先靜音問我怎麼處理。預設開啟。 */
    fun conflictAsk(ctx: Context): Boolean = prefs(ctx).getBoolean("conflict_ask", true)

    fun setConflictAsk(ctx: Context, value: Boolean) {
        prefs(ctx).edit().putBoolean("conflict_ask", value).apply()
    }

    /** 助理的名字、對使用者的稱呼、語氣(witty / concise / warm)。 */
    fun assistantName(ctx: Context): String =
        (prefs(ctx).getString("assistant_name", "") ?: "").ifBlank { "小助" }

    fun setAssistantName(ctx: Context, value: String) {
        prefs(ctx).edit().putString("assistant_name", value.trim()).apply()
    }

    fun address(ctx: Context): String =
        (prefs(ctx).getString("address", "") ?: "").ifBlank { "長官" }

    fun setAddress(ctx: Context, value: String) {
        prefs(ctx).edit().putString("address", value.trim()).apply()
    }

    fun tone(ctx: Context): String = prefs(ctx).getString("tone", "witty") ?: "witty"

    fun setTone(ctx: Context, value: String) {
        prefs(ctx).edit().putString("tone", value).apply()
    }

    /** 助理的回覆是否用語音朗讀;連續對話 = 助理問完問題後自動開啟麥克風。 */
    fun voiceReply(ctx: Context): Boolean = prefs(ctx).getBoolean("voice_reply", false)

    fun setVoiceReply(ctx: Context, value: Boolean) {
        prefs(ctx).edit().putBoolean("voice_reply", value).apply()
    }

    fun converse(ctx: Context): Boolean = prefs(ctx).getBoolean("converse", false)

    fun setConverse(ctx: Context, value: Boolean) {
        prefs(ctx).edit().putBoolean("converse", value).apply()
    }

    /** 省錢模式(預設開):簡單的記事句子用內建規則處理,不叫 AI。 */
    fun thrift(ctx: Context): Boolean = prefs(ctx).getBoolean("thrift", true)

    fun setThrift(ctx: Context, value: Boolean) {
        prefs(ctx).edit().putBoolean("thrift", value).apply()
    }

    /** 自訂鬧鐘鈴聲(uri 字串),空字串 = 系統預設鬧鐘。 */
    fun alarmSound(ctx: Context): String = prefs(ctx).getString("alarm_sound", "") ?: ""

    fun setAlarmSound(ctx: Context, value: String) {
        prefs(ctx).edit().putString("alarm_sound", value).apply()
    }

    /** 鬧鐘音量比例 20–100(乘在系統鬧鐘音量上)。 */
    fun alarmVolume(ctx: Context): Int = prefs(ctx).getInt("alarm_volume", 100).coerceIn(20, 100)

    fun setAlarmVolume(ctx: Context, value: Int) {
        prefs(ctx).edit().putInt("alarm_volume", value.coerceIn(20, 100)).apply()
    }

    /** 鈴聲漸強:從小聲慢慢變大。 */
    fun alarmFade(ctx: Context): Boolean = prefs(ctx).getBoolean("alarm_fade", false)

    fun setAlarmFade(ctx: Context, value: Boolean) {
        prefs(ctx).edit().putBoolean("alarm_fade", value).apply()
    }

    /** 從桌面圖示開啟 App 時,直接進助理並開始聽(搭配側邊鍵雙擊)。 */
    fun openToVoice(ctx: Context): Boolean = prefs(ctx).getBoolean("open_to_voice", false)

    fun setOpenToVoice(ctx: Context, value: Boolean) {
        prefs(ctx).edit().putBoolean("open_to_voice", value).apply()
    }

    // ── 每日助理 ──
    private fun b(ctx: Context, k: String, d: Boolean) = prefs(ctx).getBoolean(k, d)
    private fun i(ctx: Context, k: String, d: Int) = prefs(ctx).getInt(k, d)
    private fun putB(ctx: Context, k: String, v: Boolean) = prefs(ctx).edit().putBoolean(k, v).apply()
    private fun putI(ctx: Context, k: String, v: Int) = prefs(ctx).edit().putInt(k, v).apply()

    /** 睡前預告明天,預設 21:30。 */
    fun nightEnabled(ctx: Context) = b(ctx, "night_on", true)
    fun setNightEnabled(ctx: Context, v: Boolean) = putB(ctx, "night_on", v)
    fun nightMinutes(ctx: Context) = i(ctx, "night_min", 21 * 60 + 30)
    fun setNightMinutes(ctx: Context, v: Int) = putI(ctx, "night_min", v)

    /** 傍晚追問過時沒完成的事,預設 18:30。 */
    fun eveningEnabled(ctx: Context) = b(ctx, "evening_on", true)
    fun setEveningEnabled(ctx: Context, v: Boolean) = putB(ctx, "evening_on", v)
    fun eveningMinutes(ctx: Context) = i(ctx, "evening_min", 18 * 60 + 30)
    fun setEveningMinutes(ctx: Context, v: Int) = putI(ctx, "evening_min", v)

    /** 每週回顧(週日 20:00)。 */
    fun weeklyEnabled(ctx: Context) = b(ctx, "weekly_on", true)
    fun setWeeklyEnabled(ctx: Context, v: Boolean) = putB(ctx, "weekly_on", v)

    /** 起床到第一件事之間的準備時間,預設 60 分鐘。 */
    fun prepMinutes(ctx: Context) = i(ctx, "prep_min", 60)
    fun setPrepMinutes(ctx: Context, v: Int) = putI(ctx, "prep_min", v)

    /** 建議睡眠時間,預設 7.5 小時(5 個睡眠週期)。 */
    fun sleepMinutes(ctx: Context) = i(ctx, "sleep_min", 450)
    fun setSleepMinutes(ctx: Context, v: Int) = putI(ctx, "sleep_min", v)

    /** 鬧鐘響法:voice 語音播報(預設)、ring 鈴聲、both 鈴聲加語音。 */
    /** 自動測試或鬧鐘成功念出聲音的朗讀引擎(套件名稱),空字串 = 還沒測過。 */
    fun ttsEngine(ctx: Context): String = prefs(ctx).getString("tts_engine", "") ?: ""
    fun setTtsEngine(ctx: Context, v: String) = prefs(ctx).edit().putString("tts_engine", v).apply()

    /** 指定的朗讀聲音名稱,空字串 = 自動挑。 */
    fun ttsVoice(ctx: Context): String = prefs(ctx).getString("tts_voice", "") ?: ""
    fun setTtsVoice(ctx: Context, v: String) = prefs(ctx).edit().putString("tts_voice", v).apply()

    /** 音調(1.0 = 自然,越低越低沉)。 */
    fun ttsPitch(ctx: Context): Float = prefs(ctx).getFloat("tts_pitch", 1.0f)
    fun setTtsPitch(ctx: Context, v: Float) = prefs(ctx).edit().putFloat("tts_pitch", v).apply()

    /** 語速(1.0 = 正常)。 */
    fun ttsRate(ctx: Context): Float = prefs(ctx).getFloat("tts_rate", 1.0f)
    fun setTtsRate(ctx: Context, v: Float) = prefs(ctx).edit().putFloat("tts_rate", v).apply()

    fun alarmMode(ctx: Context): String = prefs(ctx).getString("alarm_mode", "voice") ?: "voice"
    fun setAlarmMode(ctx: Context, v: String) = prefs(ctx).edit().putString("alarm_mode", v).apply()

    fun apiKey(ctx: Context): String = prefs(ctx).getString("api_key", "") ?: ""

    fun setApiKey(ctx: Context, value: String) {
        prefs(ctx).edit().putString("api_key", value.trim()).apply()
    }

    fun model(ctx: Context): String {
        val m = prefs(ctx).getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL
        return m.ifBlank { DEFAULT_MODEL }
    }

    fun setModel(ctx: Context, value: String) {
        prefs(ctx).edit().putString("model", value.trim()).apply()
    }
}
