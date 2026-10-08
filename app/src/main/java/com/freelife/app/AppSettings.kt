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
