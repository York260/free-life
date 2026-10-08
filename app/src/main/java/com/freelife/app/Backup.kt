package com.freelife.app

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** 備份(JSON,可完整還原)與匯出行事曆(.ics,給 Google 日曆等匯入)。 */
object Backup {
    private val UTC: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

    fun exportJson(ctx: Context): String {
        val o = JSONObject()
        o.put("app", "free-life")
        o.put("version", 1)
        o.put("exportedAt", System.currentTimeMillis())
        o.put("reminders", ReminderStore.encodeArray(ReminderStore.load(ctx)))
        return o.toString(2)
    }

    /** 匯入:同一筆(id 相同)以檔案為準,其他保留。回傳匯入筆數。 */
    fun importJson(ctx: Context, text: String): Int {
        val o = JSONObject(text)
        val list = ReminderStore.decode(o.getJSONArray("reminders").toString())
        val merged = ReminderStore.load(ctx).associateBy { it.id }.toMutableMap()
        list.forEach { merged[it.id] = it }
        ReminderStore.saveAll(ctx, merged.values.sortedBy { it.start })
        AlarmScheduler.rescheduleAll(ctx)
        return list.size
    }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\n", "\\n")

    fun exportIcs(ctx: Context): String {
        val sb = StringBuilder()
        sb.append("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Free Life//TW\r\nCALSCALE:GREGORIAN\r\n")
        val stamp = UTC.format(Instant.now())
        for (r in ReminderStore.load(ctx)) {
            if (!r.hasTime) continue
            val start = Instant.ofEpochMilli(r.start)
            val end = Instant.ofEpochMilli(r.endAt ?: (r.start + 15 * 60_000L))
            sb.append("BEGIN:VEVENT\r\n")
            sb.append("UID:").append(r.id).append("@free-life\r\n")
            sb.append("DTSTAMP:").append(stamp).append("\r\n")
            sb.append("DTSTART:").append(UTC.format(start)).append("\r\n")
            sb.append("DTEND:").append(UTC.format(end)).append("\r\n")
            sb.append("SUMMARY:").append(esc(r.title)).append("\r\n")
            if (r.location.isNotBlank()) sb.append("LOCATION:").append(esc(r.location)).append("\r\n")
            if (r.done) sb.append("STATUS:CONFIRMED\r\n")
            val rule = when (r.repeat) {
                "daily" -> "FREQ=DAILY"
                "weekdays" -> "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR"
                "weekly" -> "FREQ=WEEKLY"
                "monthly" -> "FREQ=MONTHLY"
                else -> null
            }
            if (rule != null) {
                val until = r.until?.let { ";UNTIL=" + UTC.format(Instant.ofEpochMilli(it)) } ?: ""
                sb.append("RRULE:").append(rule).append(until).append("\r\n")
            }
            if (r.triggerAt != null) {
                sb.append("BEGIN:VALARM\r\nACTION:DISPLAY\r\nDESCRIPTION:").append(esc(r.title)).append("\r\n")
                sb.append("TRIGGER:-PT").append(r.leadMin).append("M\r\nEND:VALARM\r\n")
            }
            sb.append("END:VEVENT\r\n")
        }
        sb.append("END:VCALENDAR\r\n")
        return sb.toString()
    }

    fun write(ctx: Context, uri: Uri, text: String) {
        ctx.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            ?: throw IllegalStateException("無法寫入檔案")
    }

    fun read(ctx: Context, uri: Uri): String =
        ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: throw IllegalStateException("無法讀取檔案")
}
