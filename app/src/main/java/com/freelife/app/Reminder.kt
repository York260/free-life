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
 * - 小任務:只有開始時間。
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
    /** 重複規則(見 Repeat):空字串代表不重複。 */
    val repeat: String = "",
    /** 提前幾分鐘響鈴;0 代表開始時才響。 */
    val leadMin: Int = 0,
    /** 使用者指定了時間、但不響鈴(只想記錄):仍然要出現在行程圖上。 */
    val timed: Boolean = false,
    /** 重複項目遇到國定假日/連假時跳過。 */
    val skipHolidays: Boolean = false,
    /** 重複到哪一天為止(含當天結束);null 代表一直重複。 */
    val until: Long? = null,
    /** 分組標記,例如 "timetable" = 課表。 */
    val tag: String = "",
) {
    /** 開始時間;舊資料沒有存開始時間,就用響鈴時間或建立時間。 */
    val start: Long get() = if (startAt > 0L) startAt else (triggerAt ?: id)
    val isScheduled: Boolean get() = endAt != null

    /** 有時間(響鈴、有結束時間、或只記錄的時間),會出現在行程圖上。 */
    val hasTime: Boolean get() = triggerAt != null || endAt != null || timed

    /** 不響鈴、只記錄。 */
    val ringless: Boolean get() = triggerAt == null
}

private val timeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("M/d (E) HH:mm", Locale.TAIWAN)

private val hmFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

fun formatTrigger(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(timeFormatter)

/** 提前時間的文字:30 → 「30 分鐘」,60 → 「1 小時」,1440 → 「1 天」。 */
fun leadLabel(min: Int): String = when {
    min % 1440 == 0 -> "${min / 1440} 天"
    min % 60 == 0 -> "${min / 60} 小時"
    else -> "$min 分鐘"
}

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

    /** JSON 文字轉成提醒清單(備份匯入也用這個)。格式錯誤丟例外。 */
    fun decode(raw: String): List<Reminder> {
        val arr = JSONArray(raw)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Reminder(
                id = o.getLong("id"),
                title = o.getString("title"),
                location = o.optString("location", ""),
                triggerAt = if (o.has("triggerAt") && !o.isNull("triggerAt")) o.getLong("triggerAt") else null,
                done = o.optBoolean("done", false),
                startAt = o.optLong("startAt", 0L),
                endAt = if (o.has("endAt") && !o.isNull("endAt")) o.getLong("endAt") else null,
                repeat = o.optString("repeat", ""),
                leadMin = o.optInt("leadMin", 0),
                timed = o.optBoolean("timed", false),
                skipHolidays = o.optBoolean("skipHolidays", false),
                until = if (o.has("until") && !o.isNull("until")) o.getLong("until") else null,
                tag = o.optString("tag", ""),
            )
        }
    }

    fun encodeArray(list: List<Reminder>): JSONArray {
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
            if (r.repeat.isNotEmpty()) o.put("repeat", r.repeat)
            if (r.leadMin > 0) o.put("leadMin", r.leadMin)
            if (r.timed) o.put("timed", true)
            if (r.skipHolidays) o.put("skipHolidays", true)
            if (r.until != null) o.put("until", r.until)
            if (r.tag.isNotEmpty()) o.put("tag", r.tag)
            arr.put(o)
        }
        return arr
    }

    @Synchronized
    fun load(ctx: Context): List<Reminder> {
        val raw = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return emptyList()
        return try {
            decode(raw)
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun saveAll(ctx: Context, list: List<Reminder>) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, encodeArray(list).toString())
            .apply()
        VoiceWidget.refresh(ctx)
    }

    private fun save(ctx: Context, list: List<Reminder>) = saveAll(ctx, list)

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
