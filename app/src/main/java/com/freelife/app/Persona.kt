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

    private fun scheduleLines(reminders: List<Reminder>, now: LocalDateTime): String {
        val zone = ZoneId.systemDefault()
        val lines = reminders
            .filter { !it.done && (it.triggerAt != null || it.endAt != null) }
            .sortedBy { it.start }
            .filter { (it.endAt ?: it.start) >= now.atZone(zone).toInstant().toEpochMilli() }
            .filter { it.start <= now.plusDays(7).atZone(zone).toInstant().toEpochMilli() }
            .take(15)
            .map { r ->
                val span = if (r.endAt != null) formatRange(r.start, r.endAt) else formatTrigger(r.start)
                val loc = if (r.location.isBlank()) "" else " @${r.location}"
                val rep = if (r.repeat.isEmpty()) "" else "(${Repeat.label(r.repeat)}重複)"
                "- $span ${r.title}$loc$rep"
            }
        val quick = reminders.filter { !it.done && it.triggerAt == null && it.endAt == null }.take(5)
        val sb = StringBuilder()
        sb.append(if (lines.isEmpty()) "(目前沒有排程)\n" else lines.joinToString("\n") + "\n")
        if (quick.isNotEmpty()) sb.append("沒有時間的小任務:").append(quick.joinToString("、") { it.title }).append('\n')
        return sb.toString()
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

    /** 對話用的系統提示詞:人格 + 現在時間 + 目前行程 + 輸出格式與規則。 */
    fun chatSystem(ctx: Context, now: LocalDateTime, reminders: List<Reminder>): String =
        base(ctx) + "\n\n" +
            "現在是 ${now.format(FULL)}(台灣時間)。\n" +
            dateTable(now.toLocalDate()) + "\n\n" +
            "使用者未來 7 天內未完成的行程(更久以後的未列出):\n" + scheduleLines(reminders, now) + "\n" +
            "你的工作:把使用者說的話變成提醒;或回答和行程有關的問題;或簡短閒聊。\n" +
            "只輸出一個 JSON 物件,不要任何其他文字,格式:\n" +
            "{\"say\":\"對使用者說的話\",\"ask\":false,\"reminders\":[{\"title\":\"帶文件\",\"location\":\"\"," +
            "\"start\":\"2026-10-14T14:00\",\"end\":null,\"ring\":true,\"repeat\":\"\",\"leadMin\":0}]}\n" +
            "規則:\n" +
            "1. 一句話裡有多個提醒(例如「前一天晚上也提醒一次」)就拆成多筆,每筆有自己的 title 與 start,title 要讓人一看就懂。\n" +
            "2. 缺少必要資訊(哪一天、幾點、上午或下午不明)時,ask 設為 true、reminders 設為空陣列,在 say 裡只問一個最關鍵的問題。" +
            "只說「下午」沒說幾點也要反問。看診、開會這類事可以順便問地點,但地點不是必要的。\n" +
            "3. start 用本地時間 yyyy-MM-ddTHH:mm。沒有時間的待辦,start 為 null、ring 為 false。\n" +
            "4. 開會、看診、聚餐這類有時段的事:使用者沒說結束時間就預設 end = start 加 1 小時,並在 say 裡提一下「先抓一小時」。" +
            "單純的提醒(帶文件、打電話)end 為 null。\n" +
            "5. repeat 只能是 \"\"、\"daily\"、\"weekdays\"、\"weekly\"、\"monthly\";leadMin 是提前幾分鐘響鈴,準時就是 0。\n" +
            "6. ring 預設 true,只有使用者明說不用響才設 false。\n" +
            "7. 時間已經過了不要建立,改為反問。\n" +
            "8. 新提醒和既有行程時間重疊時,在 say 裡簡短提醒並問要不要調整,但仍然建立。\n" +
            "9. 只是問答(例如今天有什麼事、哪個時段有空)時,reminders 為空陣列、ask 為 false,根據上面的行程回答。\n" +
            "10. say 最多三句,口語。\n" +
            "11. 有時間的提醒,如果使用者沒提到要不要重複、也沒提到提前提醒,第一次先 ask 設為 true、reminders 設為空陣列," +
            "在 say 裡用一句話問「要重複或提前提醒嗎?」,並記住這件事的內容。使用者回答後再建立(回答「不用」「準時」就都不設)。" +
            "每件事只問一次,使用者說過不用就不要再問;小任務(沒有時間)不用問。"
}

/** AI 這一輪的結果:要對使用者說的話、是否在等使用者回答、已建立的提醒、被略過的。 */
data class AiTurn(
    val say: String,
    val ask: Boolean,
    val created: List<Reminder>,
    val skipped: List<String>,
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
        val raw = Llm.chat(ctx, Persona.chatSystem(ctx, now, reminders), history, 450)
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
                val lead = o.optInt("leadMin", 0).coerceIn(0, 10_080)
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
                val ringOn = ring || repeat.isNotEmpty()
                val ringAt = if (lead > 0 && startMs - lead * 60_000L > nowMs) startMs - lead * 60_000L else startMs
                created += Reminder(
                    id = Assistant.newId(),
                    title = title,
                    location = location,
                    triggerAt = if (ringOn) ringAt else null,
                    startAt = startMs,
                    endAt = endMs,
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
        return AiTurn(text, ask, created, skipped)
    }
}
