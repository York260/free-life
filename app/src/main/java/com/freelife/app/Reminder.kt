package com.freelife.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 一筆提醒。每一筆都有開始時間;
 * - 排程:有開始與結束時間(endAt 不是 null)。
 * - 隨手小事:只有開始時間。
 * triggerAt 是響鈴時間,null 代表不響鈴(使用者沒有指定時間,開始時間就是記下它的時刻)。
 * 延後響鈴只會改 triggerAt,不會改開始與結束時間。
 */
data class Reminder(
    val id: Long,
    val title: String,
    val location: String = "",
    val triggerAt: Long? = null,
    val done: Boolean = false,
    val startAt: Long = 0L,
    val endAt: Long? = null,
) {
    /** 開始時間;舊資料沒有存開始時間,就用響鈴時間或建立時間。 */
    val start: Long get() = if (startAt > 0L) startAt else (triggerAt ?: id)
    val isScheduled: Boolean get() = endAt != null
}

private val timeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("M/d (E) HH:mm", Locale.TAIWAN)

private val hmFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

fun formatTrigger(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(timeFormatter)

/** 「10/8 (三) 15:00–17:00」;跨日時結束時間也帶日期。 */
fun formatRange(startMs: Long, endMs: Long): String {
    val zone = ZoneId.systemDefault()
    val s = Instant.ofEpochMilli(startMs).atZone(zone)
    val e = Instant.ofEpochMilli(endMs).atZone(zone)
    return if (s.toLocalDate() == e.toLocalDate()) {
        formatTrigger(startMs) + "–" + e.format(hmFormatter)
    } else {
        formatTrigger(startMs) + " – " + formatTrigger(endMs)
    }
}

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
                    startAt = o.optLong("startAt", 0L),
                    endAt = if (o.has("endAt") && !o.isNull("endAt")) o.getLong("endAt") else null,
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
            if (r.startAt > 0L) o.put("startAt", r.startAt)
            if (r.endAt != null) o.put("endAt", r.endAt)
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
