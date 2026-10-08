package com.freelife.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.RemoteViews
import java.time.LocalDate
import java.time.ZoneId

/** 各種「直接開始聽」的入口:桌面小工具、快速設定磁貼、長按圖示捷徑、側邊鍵用的第二個圖示。 */
object VoiceEntry {
    const val ACTION_VOICE = "com.freelife.app.VOICE"

    fun intent(ctx: Context): Intent =
        Intent(ctx, MainActivity::class.java)
            .setAction(ACTION_VOICE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    fun pending(ctx: Context): PendingIntent =
        PendingIntent.getActivity(
            ctx, 7001, intent(ctx),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** 這個 Intent 是不是要求「直接開始聽」(動作、或是從語音圖示別名進來)。 */
    fun isVoice(i: Intent?): Boolean {
        if (i == null) return false
        if (i.action == ACTION_VOICE) return true
        return i.component?.className?.endsWith("VoiceLauncher") == true
    }
}

/** 桌面小工具:左邊大麥克風,點一下開始說話;右邊顯示下一件行程。 */
class VoiceWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) manager.updateAppWidget(id, build(context))
    }

    companion object {
        fun build(ctx: Context): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.widget_voice)
            v.setOnClickPendingIntent(R.id.widget_root, VoiceEntry.pending(ctx))
            v.setTextViewText(R.id.widget_next, nextText(ctx))
            return v
        }

        private fun nextText(ctx: Context): String {
            val zone = ZoneId.systemDefault()
            val now = System.currentTimeMillis()
            val today = LocalDate.now()
            val occs = ScheduleModel.occurrences(
                ReminderStore.load(ctx).filter { !it.done },
                today,
                today.plusDays(2),
            )
            val next = occs
                .filter { it.effectiveEnd.atZone(zone).toInstant().toEpochMilli() >= now }
                .minByOrNull { it.start }
                ?: return "目前沒有接下來的行程"
            val time = next.start.toLocalTime().toString().take(5)
            val day = when (next.start.toLocalDate()) {
                today -> "今天"
                today.plusDays(1) -> "明天"
                else -> ""
            }
            return "下一件 $day $time ${next.r.title}".replace("  ", " ")
        }

        fun refresh(ctx: Context) {
            try {
                val mgr = AppWidgetManager.getInstance(ctx)
                val ids = mgr.getAppWidgetIds(ComponentName(ctx, VoiceWidget::class.java))
                for (id in ids) mgr.updateAppWidget(id, build(ctx))
            } catch (ignored: Exception) {
            }
        }
    }
}

/** 下拉快速設定面板的「語音記事」磁貼。 */
class VoiceTileService : TileService() {
    override fun onStartListening() {
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            label = getString(R.string.tile_voice)
            updateTile()
        }
    }

    @Suppress("DEPRECATION")
    override fun onClick() {
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(VoiceEntry.pending(this))
        } else {
            startActivityAndCollapse(VoiceEntry.intent(this))
        }
    }
}
