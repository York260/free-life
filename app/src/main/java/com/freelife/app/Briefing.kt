package com.freelife.app

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 一筆排程提醒與它對應的本地時間。 */
data class Item(val r: Reminder, val at: LocalDateTime, val end: LocalDateTime? = null)

/** 每日確認用的行程整理。 */
data class DayPlan(
    val now: LocalDateTime,
    val today: List<Item>,
    val tomorrow: List<Item>,
    val overdue: List<Item>,
    val quick: List<Reminder>,
)

/** 一點建議;有 action 時可以一鍵把它加入提醒。 */
data class Suggestion(
    val text: String,
    val actionLabel: String? = null,
    val action: Reminder? = null,
)

object Briefing {
    private val HM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val FULL: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd (E) HH:mm", Locale.TAIWAN)
    private val APPT_RE = Regex("看診|看醫|牙醫|醫院|診所|門診|約診|回診|檢查|預約|報到")
    private val MEAL_RE = Regex("午餐|午飯|吃飯|聚餐|用餐")
    private val LEADING_MARK_RE = Regex("^\\s*(?:[-•*]|\\d+[.、)])\\s*")

    private const val SYSTEM_PROMPT =
        "你是使用者的貼身行程秘書。請用繁體中文(台灣用語),根據使用者今天與明天的行程," +
            "提出恰好三點具體、可以馬上行動的建議。" +
            "常見的好建議:預約或看診前先打電話確認、隔天很早有事就建議設起床鬧鐘、" +
            "中午有會議或勤務就提早買午餐、行程之間留交通時間、已過時沒完成的事要處理。" +
            "每一點一句話,不超過 40 個字,不要客套。" +
            "只輸出 JSON 字串陣列,內容是三個字串,不要任何其他文字。"

    /** 整理出今天剩下的、明天的、已過時未完成的排程,以及沒有時間的小任務。 */
    fun collect(now: LocalDateTime, all: List<Reminder>): DayPlan {
        val zone = ZoneId.systemDefault()
        val items = all
            .filter { !it.done && it.hasTime }
            .map { r ->
                val t = r.start
                Item(
                    r,
                    LocalDateTime.ofInstant(Instant.ofEpochMilli(t), zone),
                    r.endAt?.let { LocalDateTime.ofInstant(Instant.ofEpochMilli(it), zone) },
                )
            }
            .sortedBy { it.at }
        val today = now.toLocalDate()
        return DayPlan(
            now = now,
            today = items.filter { it.at.toLocalDate() == today && !(it.end ?: it.at).isBefore(now) },
            tomorrow = items.filter { it.at.toLocalDate() == today.plusDays(1) },
            overdue = items.filter { (it.end ?: it.at).isBefore(now) },
            quick = all.filter { !it.done && !it.hasTime },
        )
    }

    private fun remind(title: String, at: LocalDateTime): Reminder =
        Reminder(
            id = Assistant.newId(),
            title = title,
            triggerAt = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            startAt = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
        )

    private fun withAction(
        text: String,
        label: String,
        title: String,
        at: LocalDateTime,
        now: LocalDateTime,
    ): Suggestion =
        if (at.isAfter(now)) Suggestion(text, label, remind(title, at)) else Suggestion(text)

    fun hello(now: LocalDateTime): String =
        if (now.hour < 11) "早安" else if (now.hour < 18) "午安" else "晚安"

    /** 今天(8 點到 18 點、現在之後)最長的一段空檔,至少 60 分鐘;沒有就回傳 null。 */
    fun freeSlot(plan: DayPlan): Pair<LocalDateTime, LocalDateTime>? {
        val day = plan.now.toLocalDate()
        val dayStart = LocalDateTime.of(day, LocalTime.of(8, 0))
        val dayEnd = LocalDateTime.of(day, LocalTime.of(18, 0))
        var cursor = if (plan.now.isAfter(dayStart)) plan.now else dayStart
        if (!cursor.isBefore(dayEnd)) return null
        var best: Pair<LocalDateTime, LocalDateTime>? = null

        fun consider(from: LocalDateTime, to: LocalDateTime) {
            val mins = java.time.Duration.between(from, to).toMinutes()
            if (mins < 60) return
            val b = best
            if (b == null || mins > java.time.Duration.between(b.first, b.second).toMinutes()) {
                best = Pair(from, to)
            }
        }

        for (it in plan.today) {
            val s = it.at
            val e = it.end ?: it.at.plusMinutes(15)
            if (s.isAfter(cursor)) consider(cursor, if (s.isBefore(dayEnd)) s else dayEnd)
            if (e.isAfter(cursor)) cursor = e
            if (!cursor.isBefore(dayEnd)) break
        }
        if (cursor.isBefore(dayEnd)) consider(cursor, dayEnd)
        return best
    }

    private fun durationText(from: LocalDateTime, to: LocalDateTime): String {
        val mins = java.time.Duration.between(from, to).toMinutes().toInt()
        val h = mins / 60
        val r = mins % 60
        return when {
            h == 0 -> "$r 分鐘"
            r == 0 -> "$h 小時"
            else -> "$h 小時 $r 分鐘"
        }
    }

    /** 不需要 AI 的簡報問候:用你設定的稱呼與語氣,講今天的件數與空檔。 */
    fun greeting(ctx: Context, plan: DayPlan): String {
        val sb = StringBuilder("${hello(plan.now)},${AppSettings.address(ctx)}。")
        val next = plan.today.firstOrNull()
        if (next == null) {
            sb.append("今天剩下的時間沒有排程。")
        } else {
            sb.append("今天還有 ${plan.today.size} 件事,下一件是 ${next.at.format(HM)} 的「${next.r.title}」。")
        }
        val slot = freeSlot(plan)
        if (slot != null) {
            sb.append("${slot.first.format(HM)} 到 ${slot.second.format(HM)} 是空檔(約 ${durationText(slot.first, slot.second)}),")
            sb.append(
                if (plan.quick.isNotEmpty()) "可以先處理小任務「${plan.quick.first().title}」。" else "可以留給自己。",
            )
        }
        when (AppSettings.tone(ctx)) {
            "witty" -> sb.append("時間由我盯著,請放心。")
            "warm" -> sb.append("今天也加油,有我在。")
        }
        return sb.toString()
    }

    /** 內建規則建議:不需要網路,最多回傳三點。 */
    fun rules(plan: DayPlan): List<Suggestion> {
        val now = plan.now
        val today = now.toLocalDate()
        val tomorrow = today.plusDays(1)
        val out = mutableListOf<Suggestion>()

        // 時間重疊的行程:最優先提醒
        val timeline = plan.today + plan.tomorrow
        var clash: Pair<Item, Item>? = null
        outer@ for (i in timeline.indices) {
            val a = timeline[i]
            for (j in i + 1 until timeline.size) {
                val b = timeline[j]
                val overlap = if (a.end != null) b.at.isBefore(a.end) else b.at == a.at
                if (overlap) {
                    clash = Pair(a, b)
                    break@outer
                }
            }
        }
        if (clash != null) {
            val dayWord = if (clash.first.at.toLocalDate() == today) "今天" else "明天"
            out += Suggestion(
                "$dayWord「${clash.first.r.title}」(${span(clash.first)})和「${clash.second.r.title}」" +
                    "(${span(clash.second)})時間重疊,要改其中一個嗎?",
            )
        }

        // 明天有預約或看診:今天先電話確認
        val appt = plan.tomorrow.firstOrNull { APPT_RE.containsMatchIn(it.r.title) }
        if (appt != null) {
            out += withAction(
                "明天 ${appt.at.format(HM)} 有「${appt.r.title}」,今天要不要先打電話確認預約與時間?",
                "加入今天 17:00 提醒",
                "確認明天「${appt.r.title}」的預約",
                LocalDateTime.of(today, LocalTime.of(17, 0)),
                now,
            )
        }

        // 明天很早有事,或排程很多:建議設起床鬧鐘
        val first = plan.tomorrow.firstOrNull()
        if (first != null) {
            val early = first.at.toLocalTime().isBefore(LocalTime.of(8, 31))
            if (early || plan.tomorrow.size >= 3) {
                var wake = if (early) first.at.minusMinutes(90) else LocalDateTime.of(tomorrow, LocalTime.of(7, 0))
                val floor = LocalDateTime.of(tomorrow, LocalTime.of(5, 0))
                if (wake.isBefore(floor)) wake = floor
                val text = if (early) {
                    "明天最早 ${first.at.format(HM)} 就有「${first.r.title}」,要不要設一個 ${wake.format(HM)} 的起床鬧鐘?"
                } else {
                    "明天有 ${plan.tomorrow.size} 件排程,要不要設一個 ${wake.format(HM)} 的起床鬧鐘,從容一點?"
                }
                out += withAction(
                    text,
                    "加入起床鬧鐘 ${wake.format(HM)}",
                    "起床(明天 ${first.at.format(HM)} 有「${first.r.title}」)",
                    wake,
                    now,
                )
            }
        }

        // 中午有事:提早買午餐
        val lunch = (plan.today + plan.tomorrow).firstOrNull {
            val t = it.at.toLocalTime()
            !t.isBefore(LocalTime.of(11, 0)) && t.isBefore(LocalTime.of(14, 0)) &&
                !MEAL_RE.containsMatchIn(it.r.title)
        }
        if (lunch != null) {
            val dayWord = if (lunch.at.toLocalDate() == today) "今天" else "明天"
            val at = lunch.at.minusMinutes(60)
            out += withAction(
                "$dayWord ${lunch.at.format(HM)} 有「${lunch.r.title}」,要不要提早買好午餐?",
                "加入 ${at.format(HM)} 買午餐提醒",
                "買午餐($dayWord ${lunch.at.format(HM)} 有「${lunch.r.title}」)",
                at,
                now,
            )
        }

        // 已過時沒完成的事
        if (plan.overdue.isNotEmpty()) {
            out += Suggestion(
                "有 ${plan.overdue.size} 件已過時還沒完成(例如「${plan.overdue.first().r.title}」),要完成、刪除或改時間嗎?",
            )
        }

        // 今天下一件
        val next = plan.today.firstOrNull()
        if (next != null) {
            val where = if (next.r.location.isBlank()) "" else ",地點在 ${next.r.location},留意出發時間"
            out += Suggestion("今天下一件是 ${next.at.format(HM)} 的「${next.r.title}」$where。")
        }

        // 小任務
        if (plan.quick.size >= 5) {
            out += Suggestion("小任務累積 ${plan.quick.size} 件了,要不要挑 1 到 2 件今天先做掉?")
        } else if (plan.quick.isNotEmpty()) {
            out += Suggestion("今天順手把「${plan.quick.first().title}」處理掉怎麼樣?")
        }

        if (plan.today.isEmpty() && plan.tomorrow.isEmpty()) {
            out += Suggestion("今天和明天都還沒有排程,有要提醒的事可以現在輸入。")
        }

        val fallback = listOf(
            Suggestion("睡前花一分鐘看一下明天的行程,再決定要不要設鬧鐘。"),
            Suggestion("把今天最重要的一件事排進具體時間,比放在待辦清單更容易做完。"),
            Suggestion("出門前再看一次今天的清單,確認該帶的東西。"),
        )
        return (out + fallback).take(3)
    }

    /** 「15:00」或「15:00–17:00」(跨日的結束時間前面加「隔天」)。 */
    fun span(i: Item): String {
        val e = i.end ?: return i.at.format(HM)
        val day = if (e.toLocalDate() == i.at.toLocalDate()) "" else "隔天"
        return i.at.format(HM) + "–" + day + e.format(HM)
    }

    private fun itemLine(i: Item): String {
        val loc = if (i.r.location.isBlank()) "" else " @${i.r.location}"
        return "- ${span(i)} ${i.r.title}$loc"
    }

    fun promptText(plan: DayPlan): String {
        val sb = StringBuilder()
        sb.append("現在時間:").append(plan.now.format(FULL)).append('\n')
        fun section(title: String, list: List<Item>) {
            sb.append(title).append(':')
            if (list.isEmpty()) {
                sb.append("(沒有)\n")
            } else {
                sb.append('\n')
                list.forEach { sb.append(itemLine(it)).append('\n') }
            }
        }
        section("今天剩下的排程", plan.today)
        section("明天的排程", plan.tomorrow)
        if (plan.overdue.isNotEmpty()) section("已過時還沒完成", plan.overdue)
        if (plan.quick.isNotEmpty()) {
            sb.append("還沒有時間的小任務:")
                .append(plan.quick.take(10).joinToString("、") { it.title })
                .append('\n')
        }
        sb.append("請給我三點建議。")
        return sb.toString()
    }

    fun parseSuggestions(raw: String): List<String> {
        val s = raw.trim()
        val a = s.indexOf('[')
        val b = s.lastIndexOf(']')
        if (a >= 0 && b > a) {
            try {
                val arr = JSONArray(s.substring(a, b + 1))
                val list = (0 until arr.length())
                    .map { arr.optString(it).trim() }
                    .filter { it.isNotEmpty() }
                if (list.isNotEmpty()) return list.take(3)
            } catch (e: JSONException) {
                // 不是合法 JSON,改用逐行解析
            }
        }
        return s.lines()
            .map { it.replace(LEADING_MARK_RE, "").trim() }
            .filter { it.isNotEmpty() }
            .take(3)
    }

    /** 請 AI 以助理的個性寫簡報問候,並給三點建議;失敗會丟出 IOException,由呼叫端退回內建版本。 */
    fun aiBriefing(ctx: Context, plan: DayPlan): Pair<String, List<String>> {
        val system = Persona.base(ctx) + "\n\n" +
            "你要寫今天的早晨簡報,並提出恰好三點具體、可以馬上行動的建議。" +
            "常見的好建議:預約或看診前先打電話確認、隔天很早有事就建議設起床鬧鐘、" +
            "中午有會議或勤務就提早買午餐、行程之間留交通時間、已過時沒完成的事要處理、" +
            "把空檔拿來處理小任務。\n" +
            "只輸出 JSON:{\"greeting\":\"2到3句問候與今天概況,可以提到空檔與建議先處理的小任務,依你的個性說話\"," +
            "\"suggestions\":[\"建議一\",\"建議二\",\"建議三\"]}。每點建議一句話,不超過 40 字。"
        val slot = freeSlot(plan)
        val slotLine = if (slot == null) {
            "\n今天沒有超過一小時的空檔。"
        } else {
            "\n今天的空檔:${slot.first.format(HM)} 到 ${slot.second.format(HM)}(約 ${durationText(slot.first, slot.second)})。"
        }
        val raw = Llm.chat(ctx, system, listOf(ChatMsg(true, promptText(plan) + slotLine)), 700)
        val obj = Llm.extractObject(raw)
        val arr = obj.optJSONArray("suggestions")
        val list = if (arr == null) {
            emptyList()
        } else {
            (0 until arr.length()).map { arr.optString(it).trim() }.filter { it.isNotEmpty() }.take(3)
        }
        if (list.isEmpty()) throw IOException("AI 回覆的格式無法解析")
        val g = obj.optString("greeting").trim().ifEmpty { greeting(ctx, plan) }
        return Pair(g, list)
    }

    /** 呼叫 Claude 產生三點建議;失敗會丟出 IOException,由呼叫端退回內建建議。 */
    fun aiSuggestions(apiKey: String, model: String, plan: DayPlan): List<String> {
        val raw = ClaudeClient.complete(apiKey, model, SYSTEM_PROMPT, promptText(plan), 500)
        val list = parseSuggestions(raw)
        if (list.isEmpty()) throw IOException("AI 回覆的格式無法解析")
        return list
    }
}

/** 直接用 HTTPS 呼叫 Claude Messages API(不需要額外套件)。 */
object ClaudeClient {
    private const val ENDPOINT = "https://api.anthropic.com/v1/messages"

    fun complete(apiKey: String, model: String, system: String, user: String, maxTokens: Int): String =
        chat(apiKey, model, system, listOf(ChatMsg(true, user)), maxTokens)

    /** 多輪對話:history 要由使用者開頭、角色交替。 */
    fun chat(apiKey: String, model: String, system: String, history: List<ChatMsg>, maxTokens: Int): String {
        val messages = JSONArray()
        history.forEach { m ->
            messages.put(
                JSONObject().put("role", if (m.fromUser) "user" else "assistant").put("content", m.text),
            )
        }
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", maxTokens)
            .put("system", system)
            .put("messages", messages)

        val conn = URL(ENDPOINT).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 45_000
            conn.doOutput = true
            conn.setRequestProperty("content-type", "application/json")
            conn.setRequestProperty("x-api-key", apiKey)
            conn.setRequestProperty("anthropic-version", "2023-06-01")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IOException(describeError(code, text))

            val content = JSONObject(text).getJSONArray("content")
            val sb = StringBuilder()
            for (i in 0 until content.length()) {
                val block = content.getJSONObject(i)
                if (block.optString("type") == "text") sb.append(block.optString("text"))
            }
            return sb.toString()
        } catch (e: JSONException) {
            throw IOException("回應格式錯誤", e)
        } finally {
            conn.disconnect()
        }
    }

    private fun describeError(code: Int, text: String): String {
        val serverMessage = try {
            JSONObject(text).getJSONObject("error").optString("message")
        } catch (e: JSONException) {
            ""
        }
        val hint = when (code) {
            401 -> "金鑰無效"
            403 -> "金鑰沒有權限"
            429 -> "請求太頻繁或超出額度"
            else -> ""
        }
        return listOf("HTTP $code", hint, serverMessage).filter { it.isNotBlank() }.joinToString(":")
    }
}

/** 每天固定時間跳出「確認行程」通知。 */
object BriefingScheduler {
    private const val ACTION = "com.freelife.app.BRIEFING"
    private const val REQUEST = 7001

    private fun pending(ctx: Context): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, REQUEST,
            Intent(ctx, BriefingReceiver::class.java).setAction(ACTION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    fun nextTrigger(now: LocalDateTime, minutes: Int): LocalDateTime {
        val t = LocalDateTime.of(now.toLocalDate(), LocalTime.of(minutes / 60, minutes % 60))
        return if (t.isAfter(now)) t else t.plusDays(1)
    }

    fun cancel(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pending(ctx))
    }

    fun schedule(ctx: Context) {
        cancel(ctx)
        if (!AppSettings.briefingEnabled(ctx)) return
        val at = nextTrigger(LocalDateTime.now(), AppSettings.briefingMinutes(ctx))
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(ctx))
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(ctx))
        }
    }
}

object BriefingNotifier {
    private const val CHANNEL = "briefing_v1"
    private const val NOTIFICATION_ID = 7003
    const val EXTRA_OPEN_BRIEFING = "open_briefing"

    fun show(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) == null) {
            val channel = NotificationChannel(CHANNEL, "每日行程確認", NotificationManager.IMPORTANCE_DEFAULT)
            channel.description = "每天早上提醒你確認今天與明天的行程"
            nm.createNotificationChannel(channel)
        }
        val plan = Briefing.collect(LocalDateTime.now(), ReminderStore.load(ctx))
        val open = PendingIntent.getActivity(
            ctx, 7002,
            Intent(ctx, MainActivity::class.java)
                .putExtra(EXTRA_OPEN_BRIEFING, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = "今天還有 ${plan.today.size} 件排程,明天 ${plan.tomorrow.size} 件。點開確認並看三點建議。"
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_bell)
            .setContentTitle("${Briefing.hello(LocalDateTime.now())},${AppSettings.address(ctx)}。確認今天的行程")
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        nm.notify(NOTIFICATION_ID, n)
    }
}

class BriefingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        BriefingScheduler.schedule(context) // 先排好明天的
        BriefingNotifier.show(context)
    }
}
