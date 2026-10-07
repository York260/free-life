package com.freelife.app

import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class Kind { AMPM, TIME, LOCATION, START, END }

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
    private val START_CHIPS = listOf("現在", "1小時後", "今天晚上7點", "明天早上8點", "略過")
    private val END_CHIPS = listOf("30分鐘", "1小時", "2小時", "沒有結束時間")
    private val NOW_WORDS = setOf("現在", "馬上", "立刻", "立即", "now")
    private val NO_END_WORDS = SKIP_WORDS + setOf("沒有結束時間", "沒有結束", "不知道", "不確定", "未定")
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
                            endTime = p.endTime ?: draft.endTime,
                            endAmbig = if (p.endTime != null) p.endAmbig else draft.endAmbig,
                            durationMin = p.durationMin ?: draft.durationMin,
                            relative = draft.relative || p.relative,
                        ),
                        now,
                    )
                }
            }

            Kind.START -> {
                if (t.lowercase() in SKIP_WORDS || t in NOW_WORDS) {
                    advance(draft.copy(askedStart = true), now)
                } else {
                    val p = ReminderParser.parse(t, now)
                    if (p.date == null && p.time == null) {
                        Outcome.Ask(
                            draft, Kind.START,
                            "我沒看懂時間,請再說一次,例如「今天晚上7點」「1小時後」,或按「現在」",
                            START_CHIPS,
                        )
                    } else {
                        advance(
                            draft.copy(
                                date = p.date,
                                dateExplicit = p.dateExplicit,
                                time = p.time,
                                needAmPm = p.needAmPm,
                                endTime = p.endTime,
                                endAmbig = p.endAmbig,
                                durationMin = p.durationMin,
                                relative = p.relative,
                                askedStart = true,
                                // 隨手小事只有開始時間;除非這句話本身就說了結束時間
                                noEnd = p.endTime == null && p.durationMin == null,
                            ),
                            now,
                        )
                    }
                }
            }

            Kind.END -> {
                if (t.lowercase() in NO_END_WORDS) {
                    advance(draft.copy(askedEnd = true, noEnd = true), now)
                } else {
                    val dur = ReminderParser.parseDuration(t)
                    val p = ReminderParser.parse(t, now)
                    when {
                        p.time != null -> advance(
                            draft.copy(
                                endTime = p.time,
                                endAmbig = p.needAmPm,
                                durationMin = null,
                                askedEnd = true,
                            ),
                            now,
                        )
                        dur != null -> advance(
                            draft.copy(durationMin = dur, endTime = null, askedEnd = true),
                            now,
                        )
                        else -> Outcome.Ask(
                            draft, Kind.END,
                            "我沒看懂,請說「1小時」或「下午5點」,或按「沒有結束時間」",
                            END_CHIPS,
                        )
                    }
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

    /** 結束時間:明確的結束時鐘,或開始加上持續時間;沒有就回傳 null。 */
    private fun resolveEnd(d: Draft, start: LocalDateTime): LocalDateTime? {
        val et = d.endTime
        if (et != null) {
            val a = LocalDateTime.of(start.toLocalDate(), et)
            if (d.endAmbig && et.hour in 1..11) {
                // 沒說上午下午:取開始之後最近的那一個
                val b = a.plusHours(12)
                return when {
                    a.isAfter(start) -> a
                    b.isAfter(start) -> b
                    else -> a.plusDays(1)
                }
            }
            return if (a.isAfter(start)) a else a.plusDays(1)
        }
        val mins = d.durationMin
        if (mins != null) return start.plusMinutes(mins.toLong())
        return null
    }

    private fun advance(d: Draft, now: LocalDateTime): Outcome {
        val zone = ZoneId.systemDefault()

        // 完全沒有日期時間:隨手小事,問什麼時候開始(可選「現在」)
        if (d.date == null && d.time == null) {
            if (!d.askedStart) {
                return Outcome.Ask(d, Kind.START, "「${d.title}」什麼時候開始?", START_CHIPS)
            }
            val ms = now.atZone(zone).toInstant().toEpochMilli()
            val r = Reminder(id = newId(), title = d.title, location = d.location, startAt = ms)
            return Outcome.Done(r, "已記下隨手小事:${d.title}(開始時間 ${formatTrigger(ms)},不響鈴)")
        }

        val time = d.time
        if (time != null && d.needAmPm) return askAmPm(d)
        if (time == null) {
            return Outcome.Ask(
                d, Kind.TIME,
                "${dateLabel(d)}「${d.title}」幾點開始?",
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
                    "這個時間已經過了,請告訴我新的開始時間(例如「下午3點」)",
                    TIME_CHIPS,
                )
            }
        }

        val end = resolveEnd(d, dt)
        if (end == null && !d.relative && !d.noEnd && !d.askedEnd) {
            return Outcome.Ask(d, Kind.END, "${formatTrigger(dt.atZone(zone).toInstant().toEpochMilli())}「${d.title}」到幾點結束?", END_CHIPS)
        }

        val startMs = dt.atZone(zone).toInstant().toEpochMilli()
        val where = if (d.location.isBlank()) "" else " @${d.location}"
        if (end != null) {
            val endMs = end.atZone(zone).toInstant().toEpochMilli()
            val r = Reminder(
                id = newId(), title = d.title, location = d.location,
                triggerAt = startMs, startAt = startMs, endAt = endMs,
            )
            return Outcome.Done(r, "已設定排程:${formatRange(startMs, endMs)} ${d.title}$where(開始時響鈴)")
        }
        val r = Reminder(
            id = newId(), title = d.title, location = d.location,
            triggerAt = startMs, startAt = startMs,
        )
        return Outcome.Done(r, "已記下隨手小事:${formatTrigger(startMs)} ${d.title}$where(開始時響鈴)")
    }
}
