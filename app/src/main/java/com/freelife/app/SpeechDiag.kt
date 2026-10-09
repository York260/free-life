package com.freelife.app

import android.content.Context
import java.time.LocalTime

/** 語音鬧鐘每個步驟的結果,讓設定頁看得到「為什麼沒唸」。 */
object SpeechDiag {
    private const val PREF = "speech_diag"
    private const val KEY = "log"

    fun begin(ctx: Context) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString(KEY, "${LocalTime.now().withNano(0)} 開始").commit()
    }

    fun add(ctx: Context, msg: String) {
        try {
            val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            p.edit().putString(KEY, (p.getString(KEY, "") ?: "") + "\n" + msg).commit()
        } catch (ignored: Exception) {
        }
    }

    fun load(ctx: Context): String =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, null) ?: "還沒有紀錄"
}
