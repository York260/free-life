package com.freelife.app

import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class Kind { AMPM, TIME, LOCATION }

sealed class Outcome {
    /** 資訊不夠,需要追問使用者。 */
    data class Ask(
        val draft: Draft,
        val kind: Kind,
        val prompt: String,
        val chips: List<String>,
    ) : Outcome()

    /** 資訊齊全,可以存檔。 */
    data class Done(val reminder: Reminder, val message: String) : Outcome()
}

/** 把使用者輸入變成提醒;缺資訊(上午下午、幾點、地點)時追問。 */
object Assistant {
    private val TIME_CHIPS = listOf("早上8點", "中午12點", "下午3點", "晚上7點")
    private val SKIP_WORDS = setOf("略過", "跳過", "不用", "沒有", "無", "不必", "skip")
    private val PM_RE = Regex("下午|晚|午後|PM|pm")
    private val AM_RE = Regex("上午|早|凌晨|AM|am")
    private val dateFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("M/d (E)", Locale.TAIWAN)

    private var lastId = 0L

    @Synchronized
    fun newId(): Long {
        val n = maxOf(System.currentTimeMillis(), lastId + 1)
        lastId = n
        return n
    }

    fun start(text: String, now: LocalDateTime): Outcome =
        advance(ReminderParser.parse(text, now), now)

    fun reply(draft: Draft, kind: Kind, text: String, now: LocalDateTime): Outcome {
        val t = ReminderParser.normalize(text.trim())
        return when (kind) {
            Kind.AMPM -> {
                val raw = draft.time
                val pm = PM_RE.containsMatchIn(t)
                val am = AM_RE.containsMatchIn(t)
                if (raw == null || (!pm && !am)) {
                    askAmPm(draft)
                } else {
                    val h = if (pm && raw.hour in 1..11) raw.hour + 12 else raw.hour
                    advance(draft.copy(time = raw.withHour(h), needAmPm = false), now)
                }
            }

            Kind.TIME -> {
                val p = ReminderParser.parse(t, now)
                var time: LocalTime? = p.time
                var need = p.needAmPm
                if (time == null) {
                    val n = t.toIntOrNull()
                    if (n != null && n in 0..23) {
                        time = LocalTime.of(n, 0)
                        need = n in 1..11
                    }
                }
                if (time == null) {
                    Outcome.Ask(
                        draft, Kind.TIME,
                        "我沒看懂時間,請再說一次,例如「下午3點」或「15:30」",
                        TIME_CHIPS,
                    )
                } else {
                    advance(
                        draft.copy(
                            time = time,
                            needAmPm = need,
                            date = draft.date ?: p.date,
                            dateExplicit = draft.dateExplicit || p.dateExplicit,
                        ),
                        now,
                    )
                }
            }

            Kind.LOCATION -> {
                val skip = t.lowercase() in SKIP_WORDS
                advance(
                    draft.copy(
                        location = if (skip || t.isEmpty()) draft.location else t,
                        askedLocation = true,
                    ),
                    now,
                )
            }
        }
    }

    private fun askAmPm(d: Draft): Outcome {
        val t = d.time ?: LocalTime.of(9, 0)
        val clock = String.format(Locale.ROOT, "%d:%02d", t.hour, t.minute)
        return Outcome.Ask(
            d, Kind.AMPM,
            "「${d.title}」是上午還是下午 $clock?",
            listOf("上午", "下午"),
        )
    }

    private fun dateLabel(d: Draft): String {
        val date = d.date ?: return ""
        return date.format(dateFmt) + " "
    }

    private fun advance(d: Draft, now: LocalDateTime): Outcome {
        if (!d.scheduled) {
            val r = Reminder(id = newId(), title = d.title, location = d.location)
            return Outcome.Done(r, "已記下隨手小事:${d.title}")
        }

        val time = d.time
        if (time != null && d.needAmPm) return askAmPm(d)
        if (time == null) {
            return Outcome.Ask(
                d, Kind.TIME,
                "${dateLabel(d)}「${d.title}」要幾點提醒你?",
                TIME_CHIPS,
            )
        }

        if (!d.askedLocation && d.location.isBlank() && ReminderParser.needsPlace(d.title)) {
            return Outcome.Ask(d, Kind.LOCATION, "地點在哪裡?(可略過)", listOf("略過"))
        }

        val date = d.date
        val dt: LocalDateTime
        if (date == null) {
            // 只說了時間:今天還沒到就是今天,否則明天
            val todayAt = LocalDateTime.of(now.toLocalDate(), time)
            dt = if (todayAt.isAfter(now)) todayAt else todayAt.plusDays(1)
        } else {
            dt = LocalDateTime.of(date, time)
            if (!dt.isAfter(now)) {
                return Outcome.Ask(
                    d.copy(time = null, needAmPm = false), Kind.TIME,
                    "這個時間已經過了,請告訴我新的時間(例如「下午3點」)",
                    TIME_CHIPS,
                )
            }
        }

        val millis = dt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val r = Reminder(id = newId(), title = d.title, location = d.location, triggerAt = millis)
        val where = if (d.location.isBlank()) "" else " @${d.location}"
        return Outcome.Done(r, "已設定鬧鐘提醒:${formatTrigger(millis)} ${d.title}$where")
    }
}
