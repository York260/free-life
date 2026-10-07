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
