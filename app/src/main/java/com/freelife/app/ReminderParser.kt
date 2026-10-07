package com.freelife.app

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters

/**
 * 解析中的提醒草稿。
 * 有日期或時間就是有開始時間(hasWhen);沒有則要追問開始時間。
 * needAmPm = 只說了「3點」這類沒有上午/下午的時間,time 裡暫存原始小時,等使用者回答。
 * endTime / durationMin = 結束時間(同一天的時鐘)或持續多久;endAmbig = 結束時間沒說上午下午。
 * relative = 「10分鐘後」這種相對時間;noEnd = 使用者表示沒有結束時間(小任務)。
 */
data class Draft(
    val title: String,
    val date: LocalDate? = null,
    val dateExplicit: Boolean = false,
    val time: LocalTime? = null,
    val needAmPm: Boolean = false,
    val location: String = "",
    val askedLocation: Boolean = false,
    val endTime: LocalTime? = null,
    val endAmbig: Boolean = false,
    val durationMin: Int? = null,
    val relative: Boolean = false,
    val askedStart: Boolean = false,
    val askedEnd: Boolean = false,
    val noEnd: Boolean = false,
    val repeat: String = "",
    val leadMin: Int = 0,
    val askedRepeat: Boolean = false,
    val askedLead: Boolean = false,
)

/** 不需要網路、不需要 AI 的中文日期時間解析(第一版)。 */
object ReminderParser {

    private val cnDigits = mapOf(
        '零' to 0, '〇' to 0, '一' to 1, '二' to 2, '兩' to 2, '三' to 3, '四' to 4,
        '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9,
    )

    private val LOCATION_RE = Regex("(?:@|地點[: ]?)\\s*(\\S+)")
    private val REL_RE = Regex("(半|\\d+|[一二兩三四五六七八九十]+)\\s*(?:個)?(分鐘|分|小時|鐘頭|天)\\s*(?:之)?後")
    private val DAY_WORD_RE = Regex("大後天|大后天|後天|后天|明天|明日|今天|今日")
    private val WEEKDAY_RE = Regex("(下下|下個|下|這個|這|本)?\\s*(?:週|周|星期|禮拜|礼拜)([一二三四五六日天])")
    private val MONTH_DAY_RE = Regex("(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*(?:日|號|号)?|(\\d{1,2})/(\\d{1,2})")
    private val DAY_ONLY_RE = Regex("(\\d{1,2})\\s*(?:日|號|号)")
    private val TIME_RE = Regex(
        "(凌晨|清晨|早上|早晨|上午|中午|下午|傍晚|晚上|夜裡|半夜)?\\s*" +
            "(?:(\\d{1,2}):(\\d{2})|(\\d{1,2}|[零〇一二兩三四五六七八九十]{1,3})\\s*[點点](?:鐘|整)?(?:\\s*(半)|\\s*(\\d{1,2})\\s*分)?" +
            // 四位數寫法:1220 = 12:20、0830 = 08:30(年份、金額、日期不算)
            "|(?<![\\d/:.])([01]\\d|2[0-3])([0-5]\\d)(?!\\d|年|元|塊|號|号|日|月|人|個|分|點|点|%|:|/|\\.\\d|-\\d{1,2}-\\d{1,2}))"
    )
    private val WEEKDAYS_RE = Regex(
        "(?:每(?:個)?)?(?:平日|工作日|(?:週|周|星期|禮拜)一\\s*(?:到|至|~|\\-)\\s*(?:週|周|星期|禮拜)?五)"
    )
    private val WEEKLY_RE = Regex("每(?:個)?(?:週|周|星期|禮拜)([一二三四五六日天])")
    private val MONTHLY_RE = Regex("每(?:個)?月\\s*(\\d{1,2})\\s*(?:日|號|号)")
    private val DAILY_RE = Regex("每天|每日")
    private val LEAD_RE1 = Regex(
        "(?:提前|提早)\\s*(半|\\d+|[一二兩三四五六七八九十]+)\\s*(?:個)?\\s*(分鐘|分|小時|鐘頭|天)(?:先)?(?:提醒我?|叫我|通知我?)?"
    )
    private val LEAD_RE2 = Regex(
        "(半|\\d+|[一二兩三四五六七八九十]+)\\s*(?:個)?\\s*(分鐘|分|小時|鐘頭|天)前(?:先)?(?:提醒我?|叫我|通知我?)"
    )
    private val END_SEP_RE = Regex("\\s*(?:到|至|~|～|\\-|－|—|–)\\s*")
    private val DUR_RE = Regex("(一個半|半|\\d+|[一二兩三四五六七八九十]+)\\s*(?:個)?\\s*(小時|鐘頭|分鐘|分)")
    private val FILLER_RE = Regex("提醒我|提醒|叫我|記得|幫我|請你|麻煩你|麻煩")
    private val PLACE_HINT_RE = Regex(
        "看診|看醫|牙醫|醫院|診所|門診|開會|會議|面試|約會|約診|相約|聚餐|聚會|吃飯|餐督|勤務|訓練|上課|出差|拜訪|報到|檢查|演講|考試"
    )

    fun needsPlace(title: String): Boolean = PLACE_HINT_RE.containsMatchIn(title)

    /** 全形數字、全形冒號與斜線轉成半形。 */
    fun normalize(s: String): String {
        val sb = StringBuilder()
        for (c in s) {
            when {
                c in '０'..'９' -> sb.append('0' + (c - '０'))
                c == '：' -> sb.append(':')
                c == '／' -> sb.append('/')
                else -> sb.append(c)
            }
        }
        return fixSpokenMinutes(sb.toString())
    }

    private val SPOKEN_MIN_RE = Regex("([點点])([零〇一二兩三四五六七八九十]{1,3})(分)?+(?![小個鐘人元塊公歲年])")
    private val BARE_MIN_RE = Regex("([點点])(\\d{1,2})(?![\\d分號号日月:])")

    /** 語音常出現「十二點二十分」「3點20」:分鐘改成阿拉伯數字並補上「分」。 */
    private fun fixSpokenMinutes(s: String): String {
        var out = SPOKEN_MIN_RE.replace(s) { m ->
            val raw = m.groupValues[2].trimStart('零', '〇').ifEmpty { "0" }
            val n = cnToInt(raw)
            if (n == null || n !in 1..59) m.value else "${m.groupValues[1]}${n}分"
        }
        out = BARE_MIN_RE.replace(out) { m ->
            val n = m.groupValues[2].toInt()
            if (n in 1..59) "${m.groupValues[1]}${n}分" else m.value
        }
        return out
    }

    /** 阿拉伯數字或中文數字(到 99)轉整數。 */
    fun cnToInt(s: String): Int? {
        if (s.isEmpty()) return null
        s.toIntOrNull()?.let { return it }
        val tenIdx = s.indexOf('十')
        if (tenIdx >= 0) {
            val tens = if (tenIdx == 0) 1 else (cnDigits[s[0]] ?: return null)
            val ones = if (tenIdx + 1 >= s.length) 0 else (cnDigits[s[tenIdx + 1]] ?: return null)
            return tens * 10 + ones
        }
        return if (s.length == 1) cnDigits[s[0]] else null
    }

    private data class RawTime(val hour: Int, val minute: Int, val period: String)

    /** 從 TIME_RE 的比對結果取出時、分、時段;不合理的數字回傳 null。 */
    private fun readTime(g: List<String>): RawTime? {
        if (g[7].isNotEmpty()) {
            // 四位數是 24 小時制,沒說上午下午也不用再問
            return RawTime(g[7].toInt(), g[8].toInt(), if (g[1].isEmpty()) "24h" else g[1])
        }
        val colon = g[2].isNotEmpty()
        val hRaw: Int? = if (colon) g[2].toIntOrNull() else cnToInt(g[4])
        val mRaw: Int? = when {
            colon -> g[3].toIntOrNull()
            g[5].isNotEmpty() -> 30
            else -> g[6].toIntOrNull() ?: 0
        }
        if (hRaw == null || mRaw == null || hRaw !in 0..24 || mRaw !in 0..59) return null
        return RawTime(hRaw, mRaw, g[1])
    }

    /** 套用上午/下午等時段;回傳時間與「是否還需要問上午下午」。 */
    private fun applyPeriod(t: RawTime): Pair<LocalTime, Boolean> {
        var h: Int = t.hour
        var ask = false
        when (t.period) {
            "下午", "傍晚" -> {
                if (h in 1..11) h += 12
            }
            "晚上", "夜裡" -> {
                if (h in 1..11) h += 12 else if (h == 12) h = 0
            }
            "中午" -> {
                if (h in 1..4) h += 12
            }
            "凌晨", "半夜" -> {
                if (h == 12) h = 0
            }
            "" -> {
                if (h in 1..11) ask = true
            }
            else -> {}
        }
        if (h == 24) h = 0
        return Pair(LocalTime.of(h, t.minute), ask)
    }

    /** 「2小時」「半小時」「30分鐘」轉成分鐘數;沒有就回傳 null。 */
    fun parseDuration(text: String): Int? {
        val m = DUR_RE.find(normalize(text)) ?: return null
        return durationOf(m)
    }

    private fun durationOf(m: MatchResult): Int? {
        val numText = m.groupValues[1]
        val hour = m.groupValues[2] == "小時" || m.groupValues[2] == "鐘頭"
        val n: Double = when (numText) {
            "一個半" -> 1.5
            "半" -> 0.5
            else -> (cnToInt(numText) ?: return null).toDouble()
        }
        if (numText == "一個半" && !hour) return null
        val minutes = (if (hour) n * 60 else n).toInt()
        return if (minutes > 0) minutes else null
    }

    /** 「30分鐘」「半小時」「1天」轉成分鐘;不合理就回傳 null。 */
    private fun leadMinutes(m: MatchResult): Int? {
        val numText = m.groupValues[1]
        val n: Double = if (numText == "半") 0.5 else (cnToInt(numText) ?: return null).toDouble()
        val minutes = when (m.groupValues[2]) {
            "分鐘", "分" -> n
            "小時", "鐘頭" -> n * 60
            else -> n * 1440
        }.toInt()
        return if (minutes > 0) minutes else null
    }

    private fun cut(s: String, r: IntRange): String =
        s.substring(0, r.first) + " " + s.substring(r.last + 1)

    fun parse(raw: String, now: LocalDateTime): Draft {
        var s = normalize(raw.trim())
        val today = now.toLocalDate()
        var date: LocalDate? = null
        var dateExplicit = false
        var time: LocalTime? = null
        var needAmPm = false
        var location = ""
        var endTime: LocalTime? = null
        var endAmbig = false
        var durationMin: Int? = null
        var relative = false
        var repeat = ""
        var leadMin = 0

        // 地點:@診所 或 地點診所
        val locMatch = LOCATION_RE.find(s)
        if (locMatch != null) {
            location = locMatch.groupValues[1]
            s = cut(s, locMatch.range)
        }

        // 重複:每天、平日、每週三、每月15號
        val wkdays = WEEKDAYS_RE.find(s)
        val weekly = WEEKLY_RE.find(s)
        val monthly = MONTHLY_RE.find(s)
        val daily = DAILY_RE.find(s)
        if (wkdays != null) {
            repeat = "weekdays"
            s = cut(s, wkdays.range)
        } else if (weekly != null) {
            repeat = "weekly"
            s = s.replaceRange(weekly.range, "週" + weekly.groupValues[1])
        } else if (monthly != null) {
            repeat = "monthly"
            s = s.replaceRange(monthly.range, monthly.groupValues[1] + "號")
        } else if (daily != null) {
            repeat = "daily"
            s = cut(s, daily.range)
        }

        // 提前提醒:提前30分鐘、1小時前提醒我
        val lead = LEAD_RE1.find(s) ?: LEAD_RE2.find(s)
        if (lead != null) {
            val mins = leadMinutes(lead)
            if (mins != null) {
                leadMin = mins
                s = cut(s, lead.range)
            }
        }

        // 相對時間:10分鐘後、2小時後、3天後
        val relMatch = REL_RE.find(s)
        if (relMatch != null) {
            val numText = relMatch.groupValues[1]
            val unit = relMatch.groupValues[2]
            val n: Double? = if (numText == "半") 0.5 else cnToInt(numText)?.toDouble()
            if (n != null) {
                val minutes: Long? = when (unit) {
                    "分鐘", "分" -> n.toLong()
                    "小時", "鐘頭" -> (n * 60).toLong()
                    else -> null
                }
                if (minutes != null) {
                    val dt = now.plusMinutes(minutes).withSecond(0).withNano(0)
                    date = dt.toLocalDate()
                    time = dt.toLocalTime()
                    relative = true
                } else {
                    date = today.plusDays(n.toLong())
                }
                dateExplicit = true
                s = cut(s, relMatch.range)
            }
        }

        // 今天、明天、後天
        if (date == null) {
            val m = DAY_WORD_RE.find(s)
            if (m != null) {
                val offset = when (m.value) {
                    "大後天", "大后天" -> 3L
                    "後天", "后天" -> 2L
                    "明天", "明日" -> 1L
                    else -> 0L
                }
                date = today.plusDays(offset)
                dateExplicit = true
                s = cut(s, m.range)
            }
        }

        // 週三、下週五
        if (date == null) {
            val m = WEEKDAY_RE.find(s)
            if (m != null) {
                val prefix = m.groupValues[1]
                val dow = when (m.groupValues[2]) {
                    "一" -> 0L
                    "二" -> 1L
                    "三" -> 2L
                    "四" -> 3L
                    "五" -> 4L
                    "六" -> 5L
                    else -> 6L
                }
                val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                var d = when {
                    prefix == "下下" -> monday.plusWeeks(2).plusDays(dow)
                    prefix.startsWith("下") -> monday.plusWeeks(1).plusDays(dow)
                    else -> monday.plusDays(dow)
                }
                // 沒有「下」「這」「本」時,指最近的那一天
                if (prefix.isEmpty() && d.isBefore(today)) d = d.plusWeeks(1)
                date = d
                dateExplicit = true
                s = cut(s, m.range)
            }
        }

        // 10月7日、10/7
        if (date == null) {
            val m = MONTH_DAY_RE.find(s)
            if (m != null) {
                val g = m.groupValues
                val mo = (if (g[1].isNotEmpty()) g[1] else g[3]).toIntOrNull()
                val dd = (if (g[2].isNotEmpty()) g[2] else g[4]).toIntOrNull()
                if (mo != null && dd != null) {
                    var d = runCatching { LocalDate.of(today.year, mo, dd) }.getOrNull()
                    if (d != null && d.isBefore(today)) {
                        d = runCatching { LocalDate.of(today.year + 1, mo, dd) }.getOrNull()
                    }
                    if (d != null) {
                        date = d
                        dateExplicit = true
                        s = cut(s, m.range)
                    }
                }
            }
        }

        // 只說幾號:15號
        if (date == null) {
            val m = DAY_ONLY_RE.find(s)
            if (m != null) {
                val dd = m.groupValues[1].toIntOrNull()
                if (dd != null) {
                    var d = runCatching { today.withDayOfMonth(dd) }.getOrNull()
                    if (d == null || d.isBefore(today)) {
                        d = runCatching { today.plusMonths(1).withDayOfMonth(dd) }.getOrNull()
                    }
                    if (d != null) {
                        date = d
                        dateExplicit = true
                        s = cut(s, m.range)
                    }
                }
            }
        }

        // 時間:下午3點、15:30、三點半、8點10分;後面可接結束時間(3點到5點、14:00-15:30)
        if (time == null) {
            val tm = TIME_RE.find(s)
            val raw = if (tm != null) readTime(tm.groupValues) else null
            if (tm != null && raw != null) {
                val (t0, ask0) = applyPeriod(raw)
                time = t0
                needAmPm = ask0
                val restStart = tm.range.last + 1
                val rest = s.substring(restStart)
                for (sep in END_SEP_RE.findAll(rest)) {
                    val after = rest.substring(sep.range.last + 1)
                    val em = TIME_RE.find(after)
                    if (em == null || em.range.first != 0) continue
                    val er = readTime(em.groupValues) ?: continue
                    val (t1, ask1) = applyPeriod(er)
                    endTime = t1
                    endAmbig = ask1
                    val endRange = (restStart + sep.range.first)..(restStart + sep.range.last + em.range.last + 1)
                    s = cut(s, endRange)
                    break
                }
                s = cut(s, tm.range)
            }
        }

        // 開始沒說上午下午、結束有說:挑在結束之前最晚的那個(3點到下午5點 → 15:00)
        val st = time
        val et = endTime
        if (st != null && et != null && needAmPm && !endAmbig) {
            val c1 = st
            val c2 = st.plusHours(12)
            if (c2.isBefore(et)) {
                time = c2
                needAmPm = false
            } else if (c1.isBefore(et)) {
                needAmPm = false
            }
        }

        // 持續時間:開會2小時、半小時(只在已有日期或時間、且還沒有結束時間時)
        if (endTime == null && !relative && (date != null || time != null)) {
            val dm = DUR_RE.find(s)
            if (dm != null) {
                val mins = durationOf(dm)
                if (mins != null) {
                    durationMin = mins
                    s = cut(s, dm.range)
                }
            }
        }

        var title = s
            .replace(FILLER_RE, " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        title = title
            .trimStart { it.isWhitespace() || it in ",\uFF0C、。:;\uFF1B的在要" }
            .trimEnd { it.isWhitespace() || it in ",\uFF0C、。:;\uFF1B" }
        if (title.isEmpty()) {
            title = if (date != null || time != null) "提醒" else raw.trim().ifEmpty { "提醒" }
        }

        return Draft(
            title = title,
            date = date,
            dateExplicit = dateExplicit,
            time = time,
            needAmPm = needAmPm,
            location = location,
            endTime = endTime,
            endAmbig = endAmbig,
            durationMin = durationMin,
            relative = relative,
            repeat = repeat,
            leadMin = leadMin,
        )
    }
}
