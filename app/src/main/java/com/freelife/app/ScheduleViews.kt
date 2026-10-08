package com.freelife.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter

private val HM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
val WEEKDAY_CHARS = listOf("一", "二", "三", "四", "五", "六", "日")

private fun minutes(t: LocalTime): Int = t.hour * 60 + t.minute

private fun durationText(from: LocalDateTime, to: LocalDateTime): String {
    val mins = Duration.between(from, to).toMinutes().toInt()
    val h = mins / 60
    val r = mins % 60
    return when {
        h == 0 -> "$r 分鐘"
        r == 0 -> "$h 小時"
        else -> "$h 小時 $r 分"
    }
}

// ───────────────────────── 本日:時間軸 ─────────────────────────

/**
 * 一天的時間軸:每小時一格,有結束時間的畫成方塊,單點提醒畫成小標籤;
 * 時間重疊的並排並加紅框,空檔以淡色標出,今天會有一條「現在」的線。
 */
@Composable
fun DayTimeline(
    day: LocalDate,
    occs: List<Occ>,
    now: LocalDateTime,
    onClick: (Reminder) -> Unit,
) {
    val items = ScheduleModel.forDay(occs, day)
    val placed = ScheduleModel.layout(items)
    val firstHour = minOf(7, items.minOfOrNull { it.start.hour } ?: 7)
    val lastHour = (
        items.maxOfOrNull { o ->
            val e = o.effectiveEnd
            if (e.toLocalDate() != day) 24 else if (e.minute > 0) e.hour + 1 else e.hour
        } ?: 21
        ).coerceAtLeast(21).coerceAtMost(24)

    val hourH = 60.dp
    val gutter = 46.dp
    val grid = MaterialTheme.colorScheme.outline.copy(alpha = 0.28f)

    fun yOf(minute: Int) = hourH * ((minute - firstHour * 60) / 60f)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(hourH * (lastHour - firstHour) + 8.dp),
    ) {
        val bodyW = maxWidth - gutter

        // 小時格線與標籤
        for (h in firstHour..lastHour) {
            val y = yOf(h * 60)
            Text(
                text = "%02d:00".format(h % 24),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.offset(x = 0.dp, y = y - 7.dp),
            )
            Box(
                modifier = Modifier
                    .offset(x = gutter, y = y)
                    .width(bodyW)
                    .height(1.dp)
                    .background(grid),
            )
        }

        // 空檔(至少 1 小時),今天從現在算起
        val isToday = day == now.toLocalDate()
        if (!day.isBefore(now.toLocalDate())) {
            val open = LocalDateTime.of(day, LocalTime.of(8, 0))
            val from = if (isToday && now.isAfter(open)) now else open
            val until = LocalDateTime.of(day, LocalTime.of(20, 0))
            if (from.isBefore(until)) {
                ScheduleModel.freeGaps(items, from, until, 60).forEach { (a, b) ->
                    val top = yOf(minutes(a.toLocalTime()))
                    val h = yOf(minutes(b.toLocalTime())) - top
                    Box(
                        modifier = Modifier
                            .offset(x = gutter + 2.dp, y = top + 1.dp)
                            .width(bodyW - 4.dp)
                            .height(h - 2.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.10f)),
                    ) {
                        Text(
                            text = "空檔 · ${durationText(a, b)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }

        // 行程方塊
        placed.forEach { p ->
            val o = p.occ
            val startMin = minutes(o.start.toLocalTime())
            val rawEnd = if (o.end == null) {
                startMin + 30
            } else if (o.end.toLocalDate() != day) {
                lastHour * 60
            } else {
                minutes(o.end.toLocalTime())
            }
            val endMin = rawEnd.coerceAtMost(lastHour * 60)
            val top = yOf(startMin)
            val h = (yOf(endMin) - top).coerceAtLeast(30.dp)
            val colW = bodyW / p.cols
            EventBlock(
                p = p,
                now = now,
                modifier = Modifier
                    .offset(x = gutter + colW * p.col + 2.dp, y = top + 1.dp)
                    .width(colW - 4.dp)
                    .height(h - 2.dp),
                tall = h >= 50.dp,
                onClick = { onClick(o.r) },
            )
        }

        // 現在的線
        if (isToday) {
            val nm = minutes(now.toLocalTime())
            if (nm in (firstHour * 60)..(lastHour * 60)) {
                val y = yOf(nm)
                val c = MaterialTheme.colorScheme.primary
                Box(
                    modifier = Modifier
                        .offset(x = gutter - 4.dp, y = y - 4.dp)
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(c),
                )
                Box(
                    modifier = Modifier
                        .offset(x = gutter, y = y - 1.dp)
                        .width(bodyW)
                        .height(2.dp)
                        .background(c),
                )
                Text(
                    text = now.format(HM),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = c,
                    modifier = Modifier.offset(x = 0.dp, y = y + 4.dp),
                )
            }
        }

        if (items.isEmpty()) {
            Text(
                text = "這天沒有排程",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 90.dp),
            )
        }
    }
}

@Composable
private fun EventBlock(
    p: Placed,
    now: LocalDateTime,
    modifier: Modifier,
    tall: Boolean,
    onClick: () -> Unit,
) {
    val o = p.occ
    val r = o.r
    val point = o.end == null
    val scheme = MaterialTheme.colorScheme
    val info = r.ringless
    val container = if (point) scheme.tertiaryContainer else scheme.primaryContainer
    val content = if (point) scheme.onTertiaryContainer else scheme.onPrimaryContainer
    val accent = if (point) scheme.tertiary else scheme.primary
    val clash = ScheduleModel.hasClash(p)
    val overdue = !r.done && o.effectiveEnd.isBefore(now) && r.repeat.isEmpty()
    val ongoing = !r.done && !o.start.isAfter(now) && o.effectiveEnd.isAfter(now) && !point
    val shape = RoundedCornerShape(8.dp)

    val title = r.title
    val timeText = if (o.end != null) "${o.start.format(HM)}–${o.end.format(HM)}" else o.start.format(HM)
    val status = when {
        r.done -> "已完成"
        overdue -> "已過時"
        ongoing -> "進行中"
        else -> ""
    }

    var m = modifier
        .clip(shape)
        .background(container.copy(alpha = if (r.done) 0.45f else if (info) 0.5f else 1f))
    if (clash || overdue) {
        m = m.border(1.5.dp, scheme.error, shape)
    } else if (info && !r.done) {
        m = m.border(1.dp, accent.copy(alpha = 0.7f), shape)
    }
    Row(modifier = m.clickable { onClick() }) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(4.dp)
                .background(accent.copy(alpha = if (r.done) 0.45f else 1f)),
        )
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            if (tall) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (clash) {
                        AppIcon(Glyph.WARNING, scheme.error, 14.dp, Modifier.padding(end = 4.dp))
                    }
                    if (r.repeat.isNotEmpty()) {
                        AppIcon(Glyph.REPEAT, content, 14.dp, Modifier.padding(end = 4.dp))
                    }
                    if (info) {
                        AppIcon(Glyph.BELL_OFF, content.copy(alpha = 0.8f), 14.dp, Modifier.padding(end = 4.dp))
                    }
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = content,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textDecoration = if (r.done) TextDecoration.LineThrough else null,
                    )
                }
                Text(
                    text = listOf(timeText, status).filter { it.isNotEmpty() }.joinToString(" · ") +
                        if (r.location.isBlank()) "" else " · ${r.location}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (overdue) scheme.error else content.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text(
                    text = "${o.start.format(HM)} $title",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = content,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textDecoration = if (r.done) TextDecoration.LineThrough else null,
                )
            }
        }
    }
}

// ───────────────────────── 本週:七天一覽 ─────────────────────────

/** 每天一列,右邊是 7:00 到 22:00 的橫條,色塊越多代表越忙;點一列跳到那天的時間軸。 */
@Composable
fun WeekView(
    weekStart: LocalDate,
    occs: List<Occ>,
    today: LocalDate,
    now: LocalDateTime,
    onPickDay: (LocalDate) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (i in 0..6) {
            val day = weekStart.plusDays(i.toLong())
            val items = ScheduleModel.forDay(occs, day)
            val isToday = day == today
            val shape = RoundedCornerShape(12.dp)
            var rowMod = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(if (isToday) scheme.primaryContainer.copy(alpha = 0.28f) else scheme.surface)
            if (isToday) rowMod = rowMod.border(1.dp, scheme.primary, shape)
            Row(
                modifier = rowMod
                    .clickable { onPickDay(day) }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.width(44.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = WEEKDAY_CHARS[i],
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (isToday) scheme.primary else if (i >= 5) scheme.tertiary else scheme.onSurface,
                    )
                    Text(
                        text = "${day.monthValue}/${day.dayOfMonth}",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    WeekTrack(items, if (isToday) now else null)
                    val summary = if (items.isEmpty()) {
                        "沒有排程"
                    } else {
                        val names = items.take(2).joinToString("、") { it.r.title }
                        "${items.size} 件 · $names" + if (items.size > 2) "…" else ""
                    }
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun WeekTrack(items: List<Occ>, now: LocalDateTime?, height: androidx.compose.ui.unit.Dp = 20.dp) {
    val scheme = MaterialTheme.colorScheme
    val from = 7 * 60
    val span = 15 * 60f
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(6.dp))
            .background(scheme.surfaceVariant),
    ) {
        for (h in listOf(9, 12, 15, 18)) {
            Box(
                modifier = Modifier
                    .offset(x = maxWidth * ((h * 60 - from) / span))
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(scheme.outline.copy(alpha = 0.35f)),
            )
        }
        items.forEach { o ->
            val s = (minutes(o.start.toLocalTime()) - from).coerceIn(0, 900)
            val e = if (o.end == null) {
                s + 30
            } else if (o.end.toLocalDate() != o.date) {
                900
            } else {
                (minutes(o.end.toLocalTime()) - from).coerceIn(0, 900)
            }
            val w = (maxWidth * ((e - s).coerceAtLeast(20) / span)).coerceAtLeast(6.dp)
            val color = if (o.r.done) {
                scheme.outline
            } else if (o.end == null) {
                scheme.tertiary
            } else {
                scheme.primary
            }
            Box(
                modifier = Modifier
                    .offset(x = maxWidth * (s / span), y = 3.dp)
                    .width(w)
                    .height(14.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(color.copy(alpha = if (o.r.ringless) 0.45f else 0.9f)),
            )
        }
        if (now != null) {
            val nm = (minutes(now.toLocalTime()) - from).coerceIn(0, 900)
            Box(
                modifier = Modifier
                    .offset(x = maxWidth * (nm / span))
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(scheme.onSurface),
            )
        }
    }
}

// ───────────────────────── 本月:月曆熱度 ─────────────────────────

/** 月曆:數字下方的小點代表當天的行程(青色=排程、琥珀=提醒、灰=已完成),底色越深代表越忙。 */
@Composable
fun MonthView(
    month: YearMonth,
    occs: List<Occ>,
    today: LocalDate,
    selected: LocalDate,
    onPick: (LocalDate) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val first = month.atDay(1)
    val lead = first.dayOfWeek.value - 1
    val rows = (lead + month.lengthOfMonth() + 6) / 7

    Column {
        Row(modifier = Modifier.fillMaxWidth()) {
            WEEKDAY_CHARS.forEachIndexed { i, c ->
                Text(
                    text = c,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (i >= 5) scheme.tertiary else scheme.onSurfaceVariant,
                )
            }
        }
        for (row in 0 until rows) {
            Row(modifier = Modifier.fillMaxWidth()) {
                for (col in 0..6) {
                    val dayNum = row * 7 + col - lead + 1
                    if (dayNum < 1 || dayNum > month.lengthOfMonth()) {
                        Box(modifier = Modifier.weight(1f).height(54.dp))
                        continue
                    }
                    val day = month.atDay(dayNum)
                    val items = ScheduleModel.forDay(occs, day)
                    val heat = (items.count { !it.r.done } * 0.12f).coerceAtMost(0.5f)
                    val shape = RoundedCornerShape(10.dp)
                    var cell = Modifier
                        .weight(1f)
                        .height(54.dp)
                        .padding(1.5.dp)
                        .clip(shape)
                        .background(scheme.primary.copy(alpha = heat))
                    if (day == selected) cell = cell.border(1.5.dp, scheme.primary, shape)
                    Column(
                        modifier = cell.clickable { onPick(day) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        val isToday = day == today
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(if (isToday) scheme.primary else androidx.compose.ui.graphics.Color.Transparent),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "$dayNum",
                                fontSize = 13.sp,
                                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                                color = if (isToday) scheme.onPrimary else scheme.onSurface,
                            )
                        }
                        Row(
                            modifier = Modifier.padding(top = 3.dp).height(6.dp),
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            items.take(3).forEach { o ->
                                val c = if (o.r.done) {
                                    scheme.outline
                                } else if (o.end == null) {
                                    scheme.tertiary
                                } else {
                                    scheme.primary
                                }
                                Box(modifier = Modifier.size(5.dp).clip(CircleShape).background(c))
                            }
                            if (items.size > 3) {
                                Text(
                                    text = "+${items.size - 3}",
                                    fontSize = 9.sp,
                                    color = scheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 顏色說明:青色=排程、琥珀=小任務/提醒、虛線框加鈴鐺斜線=只記錄不響鈴。 */
@Composable
fun ScheduleLegend() {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.padding(top = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        @Composable
        fun item(color: androidx.compose.ui.graphics.Color, text: String, hollow: Boolean = false) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .width(14.dp)
                        .height(10.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(color.copy(alpha = if (hollow) 0.3f else 0.9f))
                        .then(if (hollow) Modifier.border(1.dp, color, RoundedCornerShape(3.dp)) else Modifier),
                )
                if (hollow) AppIcon(Glyph.BELL_OFF, scheme.onSurfaceVariant, 12.dp, Modifier.padding(start = 4.dp))
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
        item(scheme.primary, "排程")
        item(scheme.tertiary, "有時間的小任務")
        item(scheme.primary, "只記錄", hollow = true)
    }
}
