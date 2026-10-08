package com.freelife.app

import android.content.Context
import java.io.IOException
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** 助理的人格:名字、對你的稱呼、語氣。AI 的系統提示詞由這裡組出來。 */
object Persona {
    val TONES = listOf(
        "witty" to "沉穩幽默",
        "concise" to "簡潔專業",
        "warm" to "溫暖貼心",
    )

    private fun style(tone: String): String = when (tone) {
        "concise" -> "語氣簡潔專業,不寒暄,直接給結論。"
        "warm" -> "語氣溫暖貼心,像體貼的朋友,偶爾鼓勵對方,但不誇張。"
        else -> "個性像電影《鋼鐵人》裡的 AI 管家賈維斯:語氣沉穩有禮、反應敏捷、效率第一," +
            "帶一點英式的乾冷幽默與輕描淡寫的調侃(例如對使用者忘東忘西的習慣淡淡吐槽一句),但永遠站在使用者這邊、可靠、不說教。" +
            "幽默點到為止,每次回覆最多一句,事情緊急或使用者焦慮時收起玩笑。"
    }

    fun base(ctx: Context): String {
        val name = AppSettings.assistantName(ctx)
        val address = AppSettings.address(ctx)
        return "你是「$name」,使用者的專屬 AI 生活助理,負責管理他的行程與提醒。" +
            "用繁體中文(台灣用語)說話,稱呼使用者「$address」。${style(AppSettings.tone(ctx))}" +
            "回答要短,口語,適合直接唸出來:不要用 Markdown、不要列點符號、不要 emoji。"
    }

    private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("M/d (E)", Locale.TAIWAN)
    private val ISO_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val FULL: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd (E) HH:mm", Locale.TAIWAN)

    /** 給 AI 看的行程(附編號,AI 要改或刪時用編號指名)。 */
    fun refList(reminders: List<Reminder>, now: LocalDateTime): List<Reminder> {
        val zone = ZoneId.systemDefault()
        val nowMs = now.atZone(zone).toInstant().toEpochMilli()
        val timed = reminders
            .filter { !it.done && it.hasTime }
            .filter { (it.endAt ?: it.start) >= nowMs - 86_400_000L }
            .filter { it.start <= now.plusDays(14).atZone(zone).toInstant().toEpochMilli() }
            .sortedBy { it.start }
            .take(25)
        val quick = reminders.filter { !it.done && !it.hasTime }.take(8)
        return timed + quick
    }

    private fun scheduleLines(reminders: List<Reminder>, now: LocalDateTime): String {
        val refs = refList(reminders, now)
        if (refs.isEmpty()) return "(目前沒有行程)\n"
        return refs.mapIndexed { i, r ->
            val span = when {
                r.endAt != null -> formatRange(r.start, r.endAt)
                r.hasTime -> formatTrigger(r.start)
                else -> "小任務(沒有時間)"
            }
            val loc = if (r.location.isBlank()) "" else " @${r.location}"
            val rep = if (r.repeat.isEmpty()) "" else "(${Repeat.label(r.repeat)}重複)"
            val ring = if (r.hasTime && r.ringless) "(只記錄)" else ""
            "#${i + 1} $span ${r.title}$loc$rep$ring"
        }.joinToString("\n") + "\n"
    }

    private fun dateTable(today: LocalDate): String {
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val sb = StringBuilder()
        sb.append("今天=").append(today.format(ISO_DAY)).append(" ").append(today.format(DAY))
        sb.append(";明天=").append(today.plusDays(1).format(ISO_DAY))
        sb.append(";後天=").append(today.plusDays(2).format(ISO_DAY)).append('\n')
        sb.append("下週一到週日:")
        for (i in 0..6) {
            val d = monday.plusWeeks(1).plusDays(i.toLong())
            sb.append(d.format(DAY)).append("=").append(d.format(ISO_DAY))
            if (i < 6) sb.append(";")
        }
        return sb.toString()
    }

    private fun holidayLines(today: java.time.LocalDate): String {
        val br = Holidays.breaksBetween(today, today.plusDays(90))
        val singles = (0..90L).map { today.plusDays(it) }
            .filter { Holidays.isHoliday(it) && Holidays.longBreak(it) == null }
        val parts = br.map { "${it.third}連假 ${it.first.format(ISO_DAY)}~${it.second.format(ISO_DAY)}" } +
            singles.map { "${Holidays.name(it)} ${it.format(ISO_DAY)}" }
        return if (parts.isEmpty()) "近期沒有國定假日。" else "近期國定假日:" + parts.joinToString(";") + "。"
    }

    /** 對話用的系統提示詞:人格 + 現在時間 + 目前行程 + 輸出格式與規則。 */
    fun chatSystem(ctx: Context, now: LocalDateTime, reminders: List<Reminder>): String =
        base(ctx) + "\n\n" +
            "現在是 ${now.format(FULL)}(台灣時間)。\n" +
            dateTable(now.toLocalDate()) + "\n" + holidayLines(now.toLocalDate()) + "\n\n" +
            "使用者未完成的行程(最近 14 天,#編號用來指名):\n" + scheduleLines(reminders, now) + "\n" +
            "你的工作:把使用者說的話變成提醒;或回答和行程有關的問題;或簡短閒聊。\n" +
            "只輸出一個 JSON 物件,不要任何其他文字,格式:\n" +
            "{\"say\":\"對使用者說的話\",\"ask\":false,\"reminders\":[{\"title\":\"帶文件\",\"location\":\"\"," +
            "\"start\":\"2026-10-14T14:00\",\"end\":null,\"ring\":true,\"repeat\":\"\",\"leadMin\":0," +
            "\"skipHolidays\":false,\"until\":null}]," +
            "\"updates\":[{\"ref\":3,\"start\":\"2026-10-15T14:30\",\"end\":\"2026-10-15T15:30\"}]," +
            "\"deletes\":[{\"ref\":5}]," +
            "\"plan\":null}\n" +
            "規則:\n" +
            "1. 一句話裡有多個提醒(例如「前一天晚上也提醒一次」)就拆成多筆,每筆有自己的 title 與 start,title 要讓人一看就懂。\n" +
            "2. 缺少必要資訊(哪一天、幾點、上午或下午不明)時,ask 設為 true、reminders 設為空陣列,在 say 裡只問一個最關鍵的問題。" +
            "只說「下午」沒說幾點也要反問。看診、開會這類事可以順便問地點,但地點不是必要的。\n" +
            "3. start 用本地時間 yyyy-MM-ddTHH:mm。沒有時間的待辦,start 為 null、ring 為 false。\n" +
            "4. 開會、看診、聚餐這類有時段的事:使用者沒說結束時間就預設 end = start 加 1 小時,並在 say 裡提一下「先抓一小時」。" +
            "單純的提醒(帶文件、打電話)end 為 null。\n" +
            "5. repeat 只能是 \"\"、\"daily\"、\"weekdays\"、\"weekly\"、\"monthly\";leadMin 是提前幾分鐘響鈴,準時就是 0。\n" +
            "6. ring 預設 true;使用者說「不用提醒」「只是記錄」「只想知道有這件事」時 ring 設 false(照樣要有 start),並且不要再問重複或提前。\n" +
            "7. 時間已經過了不要建立,改為反問。\n" +
            "8. 新提醒和既有行程時間重疊時,在 say 裡簡短提醒並問要不要調整,但仍然建立。\n" +
            "9. 只是問答(例如今天有什麼事、哪個時段有空)時,reminders 為空陣列、ask 為 false,根據上面的行程回答。\n" +
            "10. say 最多三句,口語。\n" +
            "11. 有時間的提醒,如果使用者沒提到要不要重複、也沒提到提前提醒,第一次先 ask 設為 true、reminders 設為空陣列," +
            "在 say 裡用一句話問「要重複或提前提醒嗎?」,並記住這件事的內容。使用者回答後再建立(回答「不用」「準時」就都不設)。" +
            "每件事只問一次,使用者說過不用就不要再問;小任務(沒有時間)不用問。\n" +
            "13. 要修改或取消既有行程時,用 updates / deletes,ref 填上面清單的 # 編號;updates 只填要改的欄位(title、location、start、end、ring)," +
            "延後或提前時 start 和 end 都要給新的值。App 會先請使用者確認才執行,所以 say 用「要把…改成…嗎?」這種口吻。找不到對應的行程就反問。\n" +
            "14. 使用者要「找時間做某事」「這週要讀三小時書」時,不要自己排時間,改填 plan:" +
            "{\"title\":\"讀書\",\"minutes\":180,\"from\":\"2026-10-12\",\"to\":\"2026-10-18\",\"chunk\":60,\"ring\":true}," +
            "from/to 是日期範圍(含),chunk 是每段分鐘數(預設 60);App 會自動塞進空檔並請使用者確認。\n" +
            "15. 問「某時段有沒有空」時,根據清單回答,reminders/updates/deletes 都留空。\n" +
            "16. 有重複(repeat 不是空字串)的新提醒,如果使用者沒說國定假日要不要照常,先 ask=true 問一句「國定假日和連假也要嗎?」;" +
            "回答不要就設 skipHolidays=true。until 是重複的最後一天(yyyy-MM-dd),沒說就 null。\n" +
            "12. 使用者常用語音輸入,文字可能有同音錯字或漏字(時間、地點、人名尤其容易),請依上下文推測原意;真的無法判斷再反問。"
}

/** AI 這一輪的結果:要對使用者說的話、是否在等使用者回答、已建立的提醒、被略過的。 */
data class AiTurn(
    val say: String,
    val ask: Boolean,
    val created: List<Reminder>,
    val skipped: List<String>,
    /** (原本, 改後) */
    val updates: List<Pair<Reminder, Reminder>> = emptyList(),
    val deletes: List<Reminder> = emptyList(),
    val plan: PlanRequest? = null,
)

/** 「找時間做某事」:App 幫忙塞進空檔。 */
data class PlanRequest(
    val title: String,
    val minutes: Int,
    val from: java.time.LocalDate,
    val to: java.time.LocalDate,
    val chunk: Int,
    val ring: Boolean,
)

object AiAssistant {

    private fun parseLocal(s: String?): LocalDateTime? {
        if (s.isNullOrBlank() || s == "null") return null
        return try {
            LocalDateTime.parse(s.trim())
        } catch (e: Exception) {
            null
        }
    }

    /** 跟 AI 講一輪話,並把它拆出來的提醒轉成 Reminder(還沒存檔)。 */
    fun respond(ctx: Context, history: List<ChatMsg>, now: LocalDateTime, reminders: List<Reminder>): AiTurn {
        val raw = Llm.chat(ctx, Persona.chatSystem(ctx, now, reminders), history, 600)
        return parseTurn(raw, now, reminders)
    }

    /** 圖片(公文、通知、海報、截圖)裡的行程。結果一律先給使用者確認。 */
    fun respondImage(ctx: Context, jpegBase64: String, note: String, now: LocalDateTime, reminders: List<Reminder>): AiTurn {
        val system = Persona.chatSystem(ctx, now, reminders) +
            "\n\n這次使用者傳來一張圖片(可能是公文、開會通知、海報、課表或聊天截圖)。" +
            "找出裡面所有需要記下的行程(日期、時間、地點、事由),全部放進 reminders,不要反問(ask 一律 false);" +
            "沒寫年份就用今天之後最近的那個日期;只有日期沒有時間就把 start 設為那天 09:00 並在 say 裡提醒時間是猜的。" +
            "say 用一兩句話摘要你找到什麼。圖片裡沒有行程就說明,reminders 留空。"
        val prompt = if (note.isBlank()) "請讀出這張圖裡的行程。" else note
        val raw = ClaudeClient.chatWithImage(
            AppSettings.apiKey(ctx), AppSettings.model(ctx), system, jpegBase64, prompt, 900,
        )
        return parseTurn(raw, now, reminders).copy(ask = false)
    }

    private fun parseTurn(raw: String, now: LocalDateTime, reminders: List<Reminder>): AiTurn {
        val obj = Llm.extractObject(raw)
        val say = obj.optString("say").trim()
        val ask = obj.optBoolean("ask", false)
        val arr = obj.optJSONArray("reminders")

        val zone = ZoneId.systemDefault()
        val nowMs = now.atZone(zone).toInstant().toEpochMilli()
        val created = mutableListOf<Reminder>()
        val skipped = mutableListOf<String>()
        if (arr != null && !ask) {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val title = o.optString("title").trim()
                if (title.isEmpty()) continue
                val location = o.optString("location", "").trim()
                var repeat = o.optString("repeat", "")
                if (repeat !in setOf("daily", "weekdays", "weekly", "monthly")) repeat = ""
                val lead = if (o.optBoolean("ring", true)) o.optInt("leadMin", 0).coerceIn(0, 10_080) else 0
                val ring = o.optBoolean("ring", true)

                var start = parseLocal(o.optString("start", ""))
                if (start == null) {
                    // 沒有時間:小任務,記下建立時刻,不響鈴
                    created += Reminder(id = Assistant.newId(), title = title, location = location, startAt = nowMs)
                    continue
                }
                if (!start.isAfter(now)) {
                    if (repeat.isNotEmpty()) {
                        start = Repeat.firstAfter(start, repeat, now)
                    } else {
                        skipped += title
                        continue
                    }
                }
                var end = parseLocal(o.optString("end", ""))
                var endMs: Long? = null
                if (end != null && end.isAfter(start)) {
                    endMs = end.atZone(zone).toInstant().toEpochMilli()
                }
                val startMs = start.atZone(zone).toInstant().toEpochMilli()
                val ringOn = ring
                val ringAt = if (lead > 0 && startMs - lead * 60_000L > nowMs) startMs - lead * 60_000L else startMs
                created += Reminder(
                    id = Assistant.newId(),
                    title = title,
                    location = location,
                    triggerAt = if (ringOn) ringAt else null,
                    startAt = startMs,
                    endAt = endMs,
                    timed = !ringOn,
                    skipHolidays = repeat.isNotEmpty() && o.optBoolean("skipHolidays", false),
                    until = runCatching { java.time.LocalDate.parse(o.optString("until")) }.getOrNull()
                        ?.atTime(23, 59)?.atZone(zone)?.toInstant()?.toEpochMilli(),
                    repeat = repeat,
                    leadMin = lead,
                )
            }
        }

        var text = say
        if (text.isEmpty()) {
            text = when {
                created.isNotEmpty() -> "已經幫你記下了。"
                ask -> "我需要再確認一下細節,可以再說一次嗎?"
                else -> "好的。"
            }
        }
        if (skipped.isNotEmpty()) {
            text += "(「${skipped.joinToString("、")}」的時間已經過了,沒有建立。)"
        }
        // 修改與刪除既有行程(用編號對回去)
        val refs = Persona.refList(reminders, now)
        fun ref(o: org.json.JSONObject): Reminder? {
            val n = o.optInt("ref", -1)
            return refs.getOrNull(n - 1)
        }
        val updates = mutableListOf<Pair<Reminder, Reminder>>()
        obj.optJSONArray("updates")?.let { ua ->
            for (i in 0 until ua.length()) {
                val o = ua.optJSONObject(i) ?: continue
                val old = ref(o) ?: continue
                var r = old
                o.optString("title", "").trim().takeIf { it.isNotEmpty() && it != "null" }?.let { r = r.copy(title = it) }
                if (o.has("location") && !o.isNull("location")) r = r.copy(location = o.optString("location").trim())
                val ns = parseLocal(o.optString("start", ""))
                val ne = parseLocal(o.optString("end", ""))
                if (ns != null) {
                    val newStart = ns.atZone(zone).toInstant().toEpochMilli()
                    val delta = newStart - old.start
                    val newEnd = ne?.atZone(zone)?.toInstant()?.toEpochMilli() ?: old.endAt?.let { it + delta }
                    r = r.copy(startAt = newStart, endAt = newEnd, timed = old.timed || old.hasTime)
                } else if (ne != null) {
                    r = r.copy(endAt = ne.atZone(zone).toInstant().toEpochMilli())
                }
                if (o.has("ring") && !o.isNull("ring")) {
                    r = if (o.optBoolean("ring")) r.copy(triggerAt = r.start, timed = false) else r.copy(triggerAt = null, timed = true, repeat = "")
                }
                if (r.triggerAt != null) {
                    val ringAt = if (r.leadMin > 0) r.start - r.leadMin * 60_000L else r.start
                    r = r.copy(triggerAt = ringAt)
                }
                if (r != old) updates += Pair(old, r)
            }
        }
        val deletes = mutableListOf<Reminder>()
        obj.optJSONArray("deletes")?.let { da ->
            for (i in 0 until da.length()) {
                val o = da.optJSONObject(i) ?: continue
                ref(o)?.let { deletes += it }
            }
        }
        val plan = obj.optJSONObject("plan")?.let { p ->
            val title = p.optString("title").trim()
            val mins = p.optInt("minutes", 0)
            val from = runCatching { java.time.LocalDate.parse(p.optString("from")) }.getOrNull() ?: now.toLocalDate()
            val to = runCatching { java.time.LocalDate.parse(p.optString("to")) }.getOrNull() ?: from.plusDays(6)
            if (title.isEmpty() || mins <= 0) null else PlanRequest(
                title, mins.coerceAtMost(40 * 60), from, if (to.isBefore(from)) from else to,
                p.optInt("chunk", 60).coerceIn(15, 240), p.optBoolean("ring", true),
            )
        }
        return AiTurn(text, ask, created, skipped, updates, deletes, plan)
    }
}
