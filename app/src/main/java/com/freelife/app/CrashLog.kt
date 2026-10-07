package com.freelife.app

import android.app.Application
import android.content.Context
import java.io.PrintWriter
import java.io.StringWriter
import java.time.LocalDateTime

/** 把當機或排程失敗的原因存在手機裡,下次開 App 時顯示,方便回報問題。 */
object CrashLog {
    private const val PREF = "crash"
    private const val KEY = "last"

    fun save(ctx: Context, e: Throwable) {
        try {
            val sw = StringWriter()
            e.printStackTrace(PrintWriter(sw))
            val text = "${LocalDateTime.now()}\n$sw"
            ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY, text.take(4000))
                .commit()
        } catch (ignored: Throwable) {
            // 記錄失敗不能再造成另一次當機
        }
    }

    fun load(ctx: Context): String? =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, null)

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}

class FreeLifeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            CrashLog.save(this, error)
            previous?.uncaughtException(thread, error)
        }
    }
}
