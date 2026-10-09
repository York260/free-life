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
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** 各種「直接開始聽」的入口:桌面小工具、快速設定磁貼、長按圖示捷徑、側邊鍵用的第二個圖示。 */
object VoiceEntry {
    const val ACTION_VOICE = "com.freelife.app.VOICE"
    const val ACTION_TYPE = "com.freelife.app.TYPE"

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

/** 桌面小工具:今天的下一件行程 + 時間軸色條,右上角有打字和語音兩個快捷鍵。 */
class VoiceWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) manager.updateAppWidget(id, build(context))
    }

    companion object {
        fun build(ctx: Context): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.widget_voice)
            val open = PendingIntent.getActivity(
                ctx, 7003,
                Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val type = PendingIntent.getActivity(
                ctx, 7002,
                Intent(ctx, MainActivity::class.java).setAction(VoiceEntry.ACTION_TYPE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            v.setOnClickPendingIntent(R.id.widget_root, open)
            v.setOnClickPendingIntent(R.id.widget_type, type)
            v.setOnClickPendingIntent(R.id.widget_mic, VoiceEntry.pending(ctx))
            try {
                fill(ctx, v)
            } catch (e: Exception) {
                CrashLog.save(ctx, e)
            }
            return v
        }

        private fun fill(ctx: Context, v: RemoteViews) {
            val zone = ZoneId.systemDefault()
            val nowMs = System.currentTimeMillis()
            val today = LocalDate.now()
            val all = ReminderStore.load(ctx)
            val todayItems = ScheduleModel.occurrences(all, today, today.plusDays(1))
                .filter { it.r.hasTime }
                .sortedBy { it.start }
            val upcoming = ScheduleModel.occurrences(all.filter { !it.done }, today, today.plusDays(3))
                .filter { it.effectiveEnd.atZone(zone).toInstant().toEpochMilli() >= nowMs }
                .minByOrNull { it.start }
            val left = todayItems.count { !it.r.done && it.effectiveEnd.atZone(zone).toInstant().toEpochMilli() >= nowMs }
            if (upcoming == null) {
                v.setTextViewText(R.id.widget_label, "目前沒有接下來的行程")
                v.setTextViewText(R.id.widget_next, "輕鬆一下")
            } else {
                val startMs = upcoming.start.atZone(zone).toInstant().toEpochMilli()
                val mins = ((startMs - nowMs) / 60000).toInt()
                val rel = when {
                    mins <= 0 -> "進行中"
                    mins < 60 -> "$mins 分鐘後"
                    mins < 24 * 60 -> "${mins / 60} 小時 ${mins % 60} 分後"
                    else -> ""
                }
                val day = when (upcoming.start.toLocalDate()) {
                    today -> ""
                    today.plusDays(1) -> "明天 "
                    else -> upcoming.start.toLocalDate().let { "${it.monthValue}/${it.dayOfMonth} " }
                }
                v.setTextViewText(R.id.widget_label, "下一件 · $rel · 今天還有 $left 件".replace(" ·  · ", " · "))
                v.setTextViewText(R.id.widget_next, day + upcoming.start.toLocalTime().toString().take(5) + "  " + upcoming.r.title)
            }
            v.setImageViewBitmap(R.id.widget_strip, stripBitmap(todayItems, LocalDateTime.now()))
        }

        private fun stripBitmap(items: List<Occ>, now: LocalDateTime): Bitmap {
            val w = 720
            val h = 44
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.color = 0xFF1E2B44.toInt()
            c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), 12f, 12f, p)
            val (from, span) = trackRange(items)
            fun x(min: Int) = (min - from).coerceIn(0, span).toFloat() / span * w
            p.color = 0x55FFFFFF
            for (hh in listOf(9, 12, 15, 18, 21)) {
                if (hh * 60 > from && hh * 60 < from + span) c.drawRect(x(hh * 60), 0f, x(hh * 60) + 2f, h.toFloat(), p)
            }
            fun mins(t: java.time.LocalTime) = t.hour * 60 + t.minute
            for (o in items) {
                val s = mins(o.start.toLocalTime())
                val e = when {
                    o.end == null -> s + 30
                    o.end.toLocalDate() != o.date -> from + span
                    else -> mins(o.end.toLocalTime())
                }
                val x0 = x(s)
                val x1 = maxOf(x(e), x0 + 10f)
                val base = when {
                    o.r.done -> 0xFF8A94A6.toInt()
                    o.end == null -> 0xFFFFB86B.toInt()
                    else -> 0xFF5CE1FF.toInt()
                }
                p.color = base
                p.alpha = if (o.r.ringless) 115 else 235
                c.drawRoundRect(RectF(x0, 6f, x1, h - 6f), 8f, 8f, p)
            }
            p.alpha = 255
            p.color = 0xFFFFFFFF.toInt()
            val nx = x(mins(now.toLocalTime()))
            c.drawRect(nx - 2f, 0f, nx + 2f, h.toFloat(), p)
            return bmp
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
