package com.freelife.app

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dateFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("M/d (E)", Locale.TAIWAN)
private val timeFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

private fun toLocal(ms: Long): LocalDateTime =
    LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault())

private fun toMillis(dt: LocalDateTime): Long =
    dt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

private val LEAD_OPTIONS = listOf(
    0 to "準時",
    10 to "10 分鐘",
    30 to "30 分鐘",
    60 to "1 小時",
    1440 to "1 天",
)

/** 一排可左右滑動的單選按鈕;選到的是實心。 */
@Composable
private fun <T> ChoiceRow(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Row(
        modifier = Modifier
            .padding(top = 4.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (value, label) ->
            if (value == selected) {
                Button(onClick = { onSelect(value) }) { Text(label) }
            } else {
                OutlinedButton(onClick = { onSelect(value) }) { Text(label) }
            }
        }
    }
}

/**
 * 編輯一筆提醒:標題、地點、開始時間、結束時間(有結束時間就是排程)、是否在開始時響鈴。
 * 儲存時回傳改好的 Reminder;id 與完成狀態不變。
 */
@Composable
fun EditReminderDialog(
    r: Reminder,
    onSave: (Reminder) -> Unit,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)? = null,
    onToggleDone: (() -> Unit)? = null,
) {
    val ctx = LocalContext.current
    var title by remember(r.id) { mutableStateOf(r.title) }
    var location by remember(r.id) { mutableStateOf(r.location) }
    var start by remember(r.id) { mutableStateOf(toLocal(r.start).withSecond(0).withNano(0)) }
    var hasEnd by remember(r.id) { mutableStateOf(r.endAt != null) }
    var end by remember(r.id) {
        mutableStateOf(
            (r.endAt?.let { toLocal(it) } ?: toLocal(r.start).plusHours(1)).withSecond(0).withNano(0),
        )
    }
    var ring by remember(r.id) { mutableStateOf(r.triggerAt != null) }
    var repeat by remember(r.id) { mutableStateOf(r.repeat) }
    var lead by remember(r.id) { mutableStateOf(r.leadMin) }
    var skipHol by remember(r.id) { mutableStateOf(r.skipHolidays) }
    var error by remember(r.id) { mutableStateOf<String?>(null) }

    fun pickDate(current: LocalDateTime, onPicked: (LocalDateTime) -> Unit) {
        DatePickerDialog(
            ctx,
            { _, y, m, d -> onPicked(LocalDateTime.of(y, m + 1, d, current.hour, current.minute)) },
            current.year,
            current.monthValue - 1,
            current.dayOfMonth,
        ).show()
    }

    fun pickTime(current: LocalDateTime, onPicked: (LocalDateTime) -> Unit) {
        TimePickerDialog(
            ctx,
            { _, h, min -> onPicked(current.withHour(h).withMinute(min)) },
            current.hour,
            current.minute,
            true,
        ).show()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("編輯") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("標題") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = location,
                    onValueChange = { location = it },
                    label = { Text("地點(選填)") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )

                Text(
                    text = "開始",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 16.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        pickDate(start) { picked ->
                            val span = if (hasEnd) java.time.Duration.between(start, end) else null
                            start = picked
                            if (span != null) end = picked.plus(span)
                        }
                    }) { Text(start.format(dateFmt)) }
                    OutlinedButton(onClick = {
                        pickTime(start) { picked ->
                            val span = if (hasEnd) java.time.Duration.between(start, end) else null
                            start = picked
                            if (span != null) end = picked.plus(span)
                        }
                    }) { Text(start.format(timeFmt)) }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("有結束時間(排程)")
                    Switch(checked = hasEnd, onCheckedChange = { on ->
                        hasEnd = on
                        if (on && !end.isAfter(start)) end = start.plusHours(1)
                    })
                }
                if (hasEnd) {
                    Row(
                        modifier = Modifier.padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(onClick = { pickDate(end) { end = it } }) {
                            Text(end.format(dateFmt))
                        }
                        OutlinedButton(onClick = { pickTime(end) { end = it } }) {
                            Text(end.format(timeFmt))
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(if (ring) "響鈴提醒" else "只記錄,不提醒")
                    Switch(checked = ring, onCheckedChange = { ring = it })
                }

                Text(
                    text = "重複",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 16.dp),
                )
                ChoiceRow(Repeat.OPTIONS, repeat) { repeat = it }
                if (repeat.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("國定假日、連假跳過")
                        Switch(checked = skipHol, onCheckedChange = { skipHol = it })
                    }
                }
                if (ring) {

                    Text(
                        text = "提前響鈴",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                    ChoiceRow(LEAD_OPTIONS, lead) { lead = it }
                }

                if (onDelete != null || onToggleDone != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        if (onToggleDone != null) {
                            TextButton(onClick = onToggleDone) {
                                Text(
                                    when {
                                        r.repeat.isNotEmpty() -> "略過這次"
                                        r.done -> "取消完成"
                                        else -> "標示完成"
                                    },
                                )
                            }
                        }
                        if (onDelete != null) {
                            TextButton(onClick = onDelete) {
                                Text("刪除", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }

                val e = error
                if (e != null) {
                    Text(
                        text = e,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val t = title.trim()
                val startMs = toMillis(start)
                val endMs = if (hasEnd) toMillis(end) else null
                val ringOn = ring
                if (!ring) lead = 0
                val leadMs = lead * 60_000L
                val ringAt = if (lead > 0 && startMs - leadMs > System.currentTimeMillis()) startMs - leadMs else startMs
                when {
                    t.isEmpty() -> error = "標題不能空白"
                    endMs != null && endMs <= startMs -> error = "結束時間要晚於開始時間"
                    ringOn && !r.done && startMs <= System.currentTimeMillis() &&
                        (startMs != r.start || r.triggerAt == null) ->
                        error = "開始時間已經過了,不能響鈴。請改時間,或改成「只記錄」"
                    else -> onSave(
                        r.copy(
                            title = t,
                            location = location.trim(),
                            startAt = startMs,
                            endAt = endMs,
                            triggerAt = if (ringOn) ringAt else null,
                            // 不響鈴但有指定時間(或原本就有時間):照樣顯示在行程圖上
                            timed = !ringOn && (r.timed || r.triggerAt != null || r.endAt != null || startMs != r.start),
                            repeat = repeat,
                            leadMin = lead,
                            skipHolidays = repeat.isNotEmpty() && skipHol,
                        ),
                    )
                }
            }) { Text("儲存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
