package com.freelife.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.time.LocalTime

/** 語音鬧鐘每個步驟的結果,讓設定頁看得到「為什麼沒唸」。也寫進 logcat(標籤 FreeLifeProbe),雲端模擬器測試會讀。 */
object SpeechDiag {
    private const val PREF = "speech_diag"
    private const val KEY = "log"
    const val TAG = "FreeLifeProbe"
    private val main = Handler(Looper.getMainLooper())

    /** 設定頁開著時,紀錄一變就更新畫面。 */
    @Volatile
    var listener: (() -> Unit)? = null

    fun begin(ctx: Context, title: String = "開始") {
        val line = "${LocalTime.now().withNano(0)} $title"
        Log.i(TAG, line)
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY, line).commit()
        notifyChange()
    }

    fun add(ctx: Context, msg: String) {
        Log.i(TAG, msg)
        try {
            val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            p.edit().putString(KEY, ((p.getString(KEY, "") ?: "") + "\n" + msg).takeLast(6000)).commit()
        } catch (ignored: Exception) {
        }
        notifyChange()
    }

    private fun notifyChange() {
        val l = listener ?: return
        main.post { l() }
    }

    fun load(ctx: Context): String =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, null) ?: "還沒有紀錄"
}
