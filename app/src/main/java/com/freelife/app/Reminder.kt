package com.freelife.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 一筆提醒。triggerAt 為 null 代表「隨手小事」(沒有時間,只是待辦);
 * 有 triggerAt 代表「排程」,到時間會用鬧鐘等級提醒。
 */
data class Reminder(
    val id: Long,
    val title: String,
    val location: String = "",
    val triggerAt: Long? = null,
    val done: Boolean = false,
) {
    val isScheduled: Boolean get() = triggerAt != null
}

private val timeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("M/d (E) HH:mm", Locale.TAIWAN)

fun formatTrigger(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(timeFormatter)

/** 以 SharedPreferences 存成 JSON,資料只留在手機上。 */
object ReminderStore {
    private const val PREF = "reminders"
    private const val KEY = "list"

    @Synchronized
    fun load(ctx: Context): List<Reminder> {
        val raw = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Reminder(
                    id = o.getLong("id"),
                    title = o.getString("title"),
                    location = o.optString("location", ""),
                    triggerAt = if (o.has("triggerAt") && !o.isNull("triggerAt")) o.getLong("triggerAt") else null,
                    done = o.optBoolean("done", false),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    private fun save(ctx: Context, list: List<Reminder>) {
        val arr = JSONArray()
        list.forEach { r ->
            val o = JSONObject()
            o.put("id", r.id)
            o.put("title", r.title)
            o.put("location", r.location)
            if (r.triggerAt != null) o.put("triggerAt", r.triggerAt)
            o.put("done", r.done)
            arr.put(o)
        }
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, arr.toString())
            .apply()
    }

    @Synchronized
    fun get(ctx: Context, id: Long): Reminder? = load(ctx).firstOrNull { it.id == id }

    @Synchronized
    fun upsert(ctx: Context, reminder: Reminder) {
        val list = load(ctx).toMutableList()
        val idx = list.indexOfFirst { it.id == reminder.id }
        if (idx >= 0) list[idx] = reminder else list.add(reminder)
        save(ctx, list)
    }

    @Synchronized
    fun delete(ctx: Context, id: Long) {
        save(ctx, load(ctx).filterNot { it.id == id })
    }
}
