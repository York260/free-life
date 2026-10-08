package com.freelife.app

import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class Kind { AMPM, TIME, LOCATION, START, END, REPEAT, LEAD }

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
    private val REPEAT_CHIPS = listOf("不重複", "每天", "平日", "每週", "每月")
    private val LEAD_CHIPS = listOf("準時響鈴", "提前10分鐘", "提前30分鐘", "提前1小時", "提前1天", "不提醒")
    private val NO_WORDS = Regex("^(不用|不要|不必|不|沒有|無|略過|跳過|否|no)")
    private val DAY_LEAD_RE = Regex("(\\d+|[一二兩三])\\s*天")
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
                            repeat = p.repeat.ifEmpty { draft.repeat },
                            leadMin = if (p.leadMin > 0) p.leadMin else draft.leadMin,
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
                                repeat = p.repeat.ifEmpty { draft.repeat },
                                leadMin = if (p.leadMin > 0) p.leadMin else draft.leadMin,
                                // 小任務只有開始時間;除非這句話本身就說了結束時間
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

            Kind.REPEAT -> {
                val code = when {
                    NO_WORDS.containsMatchIn(t) || t.startsWith("不重複") -> ""
                    t.contains("平日") || t.contains("工作日") -> "weekdays"
                    t.contains("週") || t.contains("周") || t.contains("星期") || t.contains("禮拜") -> "weekly"
                    t.contains("天") || t.contains("日") -> "daily"
                    t.contains("月") -> "monthly"
                    else -> null
                }
                if (code == null) {
                    Outcome.Ask(draft, Kind.REPEAT, "沒聽懂。請說「每天」「平日」「每週」「每月」,或按「不重複」", REPEAT_CHIPS)
                } else {
                    advance(draft.copy(repeat = code, askedRepeat = true), now)
                }
            }

            Kind.LEAD -> {
                if (ReminderParser.NO_RING_RE.containsMatchIn(t) || t == "不提醒") {
                    return advance(draft.copy(noRing = true, askedLead = true, askedRepeat = true), now)
                }
                val dayM = DAY_LEAD_RE.find(t)
                val mins: Int? = when {
                    t.contains("準時") || NO_WORDS.containsMatchIn(t) -> 0
                    dayM != null -> (ReminderParser.cnToInt(dayM.groupValues[1]) ?: 1) * 1440
                    else -> ReminderParser.parseDuration(t)
                }
                if (mins == null) {
                    Outcome.Ask(draft, Kind.LEAD, "沒聽懂。請說「10分鐘」「1小時」「1天」,或按「準時響鈴」「不提醒」", LEAD_CHIPS)
                } else {
                    advance(draft.copy(leadMin = mins, askedLead = true), now)
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

        // 完全沒有日期時間:小任務,問什麼時候開始(可選「現在」)
        if (d.date == null && d.time == null) {
            if (!d.askedStart) {
                return Outcome.Ask(d, Kind.START, "「${d.title}」什麼時候開始?", START_CHIPS)
            }
            val ms = now.atZone(zone).toInstant().toEpochMilli()
            val r = Reminder(id = newId(), title = d.title, location = d.location, startAt = ms)
            return Outcome.Done(r, "已記下小任務:${d.title}(開始時間 ${formatTrigger(ms)},不響鈴)")
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
        var dt: LocalDateTime
        if (date == null) {
            // 只說了時間:今天還沒到就是今天,否則明天
            val todayAt = LocalDateTime.of(now.toLocalDate(), time)
            dt = if (todayAt.isAfter(now)) todayAt else todayAt.plusDays(1)
            if (d.repeat == "weekdays") dt = Repeat.alignWeekday(dt)
        } else {
            dt = LocalDateTime.of(date, time)
            if (!dt.isAfter(now)) {
                if (d.repeat.isNotEmpty()) {
                    // 重複提醒:這次已經過了,就從下一次開始
                    dt = Repeat.firstAfter(dt, d.repeat, now)
                } else {
                    return Outcome.Ask(
                        d.copy(time = null, needAmPm = false), Kind.TIME,
                        "這個時間已經過了,請告訴我新的開始時間(例如「下午3點」)",
                        TIME_CHIPS,
                    )
                }
            } else if (d.repeat == "weekdays") {
                dt = Repeat.alignWeekday(dt)
            }
        }

        val end = resolveEnd(d, dt)
        if (end == null && !d.relative && !d.noEnd && !d.askedEnd) {
            return Outcome.Ask(d, Kind.END, "${formatTrigger(dt.atZone(zone).toInstant().toEpochMilli())}「${d.title}」到幾點結束?", END_CHIPS)
        }

        // 主動問:要不要重複、要不要提前提醒(「10分鐘後」這種臨時的不用問)
        if (!d.relative) {
            if (!d.askedLead && d.leadMin == 0 && !d.noRing) {
                return Outcome.Ask(d, Kind.LEAD, "「${d.title}」要怎麼提醒?", LEAD_CHIPS)
            }
            if (!d.askedRepeat && d.repeat.isEmpty() && !d.noRing) {
                return Outcome.Ask(d, Kind.REPEAT, "要重複嗎?", REPEAT_CHIPS)
            }
        }

        val startMs = dt.atZone(zone).toInstant().toEpochMilli()
        val nowMs = now.atZone(zone).toInstant().toEpochMilli()
        // 提前提醒:響鈴時間 = 開始 - 提前分鐘;如果提前的時間點已經過了,就改成開始時響
        val leadOk = d.leadMin > 0 && startMs - d.leadMin * 60_000L > nowMs
        val ringMs = if (leadOk) startMs - d.leadMin * 60_000L else startMs
        val ringText = when {
            d.noRing -> "只記錄,不提醒"
            leadOk -> "開始前 ${leadLabel(d.leadMin)}響鈴"
            else -> "開始時響鈴"
        }
        val trig: Long? = if (d.noRing) null else ringMs
        val rep = Repeat.label(d.repeat)
        val repText = if (rep.isEmpty()) "" else "(重複:$rep)"
        val where = if (d.location.isBlank()) "" else " @${d.location}"
        if (end != null) {
            val endMs = end.atZone(zone).toInstant().toEpochMilli()
            val r = Reminder(
                id = newId(), title = d.title, location = d.location,
                triggerAt = trig, startAt = startMs, endAt = endMs,
                repeat = if (d.noRing) "" else d.repeat, leadMin = if (d.noRing) 0 else d.leadMin,
                timed = d.noRing,
            )
            return Outcome.Done(r, "已設定排程$repText:${formatRange(startMs, endMs)} ${d.title}$where($ringText)")
        }
        val r = Reminder(
            id = newId(), title = d.title, location = d.location,
            triggerAt = trig, startAt = startMs,
            repeat = if (d.noRing) "" else d.repeat, leadMin = if (d.noRing) 0 else d.leadMin,
            timed = d.noRing,
        )
        return Outcome.Done(r, "已記下小任務$repText:${formatTrigger(startMs)} ${d.title}$where($ringText)")
    }
}
