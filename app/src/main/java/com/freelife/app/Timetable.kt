package com.freelife.app

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** 課表的一堂課:星期(1=週一)、開始、結束、課名、地點。 */
data class ClassRow(
    val day: Int = 1,
    val start: LocalTime = LocalTime.of(8, 10),
    val end: LocalTime = LocalTime.of(9, 0),
    val title: String = "",
    val location: String = "",
)

const val TIMETABLE_TAG = "timetable"

object TimetableBuilder {
    /** 每堂課 → 每週重複、不響鈴、到寒假前一天為止的行程。 */
    fun build(rows: List<ClassRow>, semesterStart: LocalDate, winterStart: LocalDate, skipHolidays: Boolean): List<Reminder> {
        val zone = ZoneId.systemDefault()
        val until = winterStart.minusDays(1).atTime(23, 59).atZone(zone).toInstant().toEpochMilli()
        return rows.filter { it.title.isNotBlank() && it.end.isAfter(it.start) }.map { c ->
            val first = semesterStart.with(TemporalAdjusters.nextOrSame(java.time.DayOfWeek.of(c.day)))
            val s = LocalDateTime.of(first, c.start).atZone(zone).toInstant().toEpochMilli()
            val e = LocalDateTime.of(first, c.end).atZone(zone).toInstant().toEpochMilli()
            Reminder(
                id = Assistant.newId(),
                title = c.title.trim(),
                location = c.location.trim(),
                triggerAt = null,
                startAt = s,
                endAt = e,
                repeat = "weekly",
                timed = true,
                skipHolidays = skipHolidays,
                until = until,
                tag = TIMETABLE_TAG,
            )
        }
    }

    fun fromExisting(list: List<Reminder>): List<ClassRow> {
        val zone = ZoneId.systemDefault()
        return list.filter { it.tag == TIMETABLE_TAG }.map { r ->
            val s = LocalDateTime.ofInstant(Instant.ofEpochMilli(r.start), zone)
            val e = r.endAt?.let { LocalDateTime.ofInstant(Instant.ofEpochMilli(it), zone) } ?: s.plusMinutes(50)
            ClassRow(s.dayOfWeek.value, s.toLocalTime(), e.toLocalTime(), r.title, r.location)
        }.sortedWith(compareBy({ it.day }, { it.start }))
    }

    /** 從課表照片讀出每堂課(需要 AI)。 */
    fun fromImage(ctx: android.content.Context, b64: String): List<ClassRow> {
        val system = "你負責把課表圖片轉成 JSON。只輸出一個 JSON 物件:" +
            "{\"classes\":[{\"day\":1,\"start\":\"08:10\",\"end\":\"09:00\",\"title\":\"國文\",\"location\":\"A101\"}]}。" +
            "day 1=週一…7=週日;同一天連續好幾節的同一門課合併成一筆;沒有地點就空字串;空堂不要列。"
        val raw = ClaudeClient.chatWithImage(AppSettings.apiKey(ctx), AppSettings.model(ctx), system, b64, "請讀出這張課表。", 1500)
        val arr = Llm.extractObject(raw).optJSONArray("classes") ?: return emptyList()
        val out = mutableListOf<ClassRow>()
        for (i in 0 until arr.length()) {
            val o: JSONObject = arr.optJSONObject(i) ?: continue
            val s = runCatching { LocalTime.parse(o.optString("start")) }.getOrNull() ?: continue
            val e = runCatching { LocalTime.parse(o.optString("end")) }.getOrNull() ?: s.plusMinutes(50)
            out += ClassRow(o.optInt("day", 1).coerceIn(1, 7), s, e, o.optString("title"), o.optString("location"))
        }
        return out
    }
}

/** 課表:輸入這學期的課(或拍照自動填),存成不提醒的每週行程,到寒假為止。 */
@Composable
fun TimetableScreen(
    reminders: List<Reminder>,
    onSave: (List<Reminder>, Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val existing = remember { TimetableBuilder.fromExisting(reminders.toList()) }
    val rows = remember { mutableStateListOf<ClassRow>().apply { addAll(existing.ifEmpty { listOf(ClassRow()) }) } }
    val semesterStart = remember { LocalDate.now() }
    var winterStart by remember { mutableStateOf<LocalDate?>(null) }
    var skipHol by remember { mutableStateOf(true) }
    var msg by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun pickDate(cur: LocalDate, onPicked: (LocalDate) -> Unit) {
        DatePickerDialog(ctx, { _, y, m, d -> onPicked(LocalDate.of(y, m + 1, d)) }, cur.year, cur.monthValue - 1, cur.dayOfMonth).show()
    }

    fun pickTime(cur: LocalTime, onPicked: (LocalTime) -> Unit) {
        TimePickerDialog(ctx, { _, h, m -> onPicked(LocalTime.of(h, m)) }, cur.hour, cur.minute, true).show()
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        msg = "讀取課表中…"
        Thread {
            val res = runCatching { TimetableBuilder.fromImage(ctx, ImageUtil.jpegBase64(ctx, uri)) }
            Handler(Looper.getMainLooper()).post {
                busy = false
                val list = res.getOrNull()
                if (list.isNullOrEmpty()) {
                    msg = "讀不出課表" + (res.exceptionOrNull()?.message?.let { ":$it" } ?: "") + "。可以手動輸入。"
                } else {
                    rows.clear()
                    rows.addAll(list)
                    msg = "讀到 ${list.size} 堂課,請檢查一下時間和課名。"
                }
            }
        }.start()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("課表", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            TextButton(onClick = onBack) { Text("返回") }
        }
        Text(
            "存成每週重複、不響鈴的行程,行程圖上看得到,到寒假前一天為止。",
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
        )
        if (Llm.configured(ctx)) {
            OutlinedButton(
                onClick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                enabled = !busy,
                modifier = Modifier.padding(top = 8.dp),
            ) {
                AppIcon(Glyph.CAMERA, scheme.primary, 18.dp, Modifier.padding(end = 6.dp))
                Text("用課表照片自動填")
            }
        }
        msg?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp)) }

        SectionTitle("寒假什麼時候開始?(開學後重新輸入新課表就好)")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { pickDate(winterStart ?: LocalDate.now().plusMonths(3)) { winterStart = it } }) {
                Text(winterStart?.toString() ?: "選擇寒假第一天")
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("國定假日、連假停課(跳過)")
            Switch(checked = skipHol, onCheckedChange = { skipHol = it })
        }

        SectionTitle("每堂課")
        rows.forEachIndexed { i, c ->
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = scheme.surfaceVariant),
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Chips((1..7).map { it to "週" + WEEKDAY_CHARS[it - 1] }, c.day) { rows[i] = c.copy(day = it) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { pickTime(c.start) { t -> rows[i] = rows[i].copy(start = t, end = if (rows[i].end.isAfter(t)) rows[i].end else t.plusMinutes(50)) } }) {
                            Text(c.start.toString())
                        }
                        Text("到")
                        TextButton(onClick = { pickTime(c.end) { t -> rows[i] = rows[i].copy(end = t) } }) { Text(c.end.toString()) }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { rows.removeAt(i) }) { Text("刪除", color = scheme.error) }
                    }
                    OutlinedTextField(
                        value = c.title,
                        onValueChange = { rows[i] = rows[i].copy(title = it) },
                        label = { Text("課名") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = c.location,
                        onValueChange = { rows[i] = rows[i].copy(location = it) },
                        label = { Text("教室(可空白)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                }
            }
        }
        OutlinedButton(
            onClick = {
                val last = rows.lastOrNull()
                rows.add(if (last != null) ClassRow(last.day, last.end.plusMinutes(10), last.end.plusMinutes(60)) else ClassRow())
            },
            modifier = Modifier.padding(top = 4.dp),
        ) { Text("+ 加一堂課") }

        fun tell(text: String) {
            msg = text
            android.widget.Toast.makeText(ctx, text, android.widget.Toast.LENGTH_LONG).show()
        }
        Button(
            onClick = {
                val w = winterStart
                val blank = rows.count { it.title.isBlank() }
                val bad = rows.count { it.title.isNotBlank() && !it.end.isAfter(it.start) }
                when {
                    w == null -> tell("請先在上面選「寒假第一天」,課表才知道排到哪天為止。")
                    !w.isAfter(semesterStart) -> tell("寒假第一天要在今天之後。")
                    bad > 0 -> tell("有 $bad 堂課的結束時間早於開始時間,請修正。")
                    else -> {
                        val list = try {
                            TimetableBuilder.build(rows.toList(), semesterStart, w, skipHol)
                        } catch (e: Exception) {
                            CrashLog.save(ctx, e)
                            emptyList()
                        }
                        if (list.isEmpty()) {
                            tell(if (blank > 0) "課名不能空白,請填上課名。" else "還沒有填任何課。")
                        } else {
                            try {
                                onSave(list, true)
                                tell("已排好 ${list.size} 堂課,每週重複到 ${w.minusDays(1)}" + if (blank > 0) "($blank 堂沒課名的略過)" else "")
                            } catch (e: Exception) {
                                CrashLog.save(ctx, e)
                                tell("存檔失敗:${e.message}")
                            }
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        ) { Text(if (existing.isEmpty()) "排進行程" else "更新課表(取代舊的)") }
        msg?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = scheme.primary, modifier = Modifier.padding(top = 8.dp)) }
        Spacer(Modifier.height(24.dp))
    }
}
