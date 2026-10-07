package com.freelife.app

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

private val HM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val DAY_LABEL: DateTimeFormatter = DateTimeFormatter.ofPattern("M月d日 (E)", Locale.TAIWAN)
private val MD: DateTimeFormatter = DateTimeFormatter.ofPattern("M/d")

private const val MODE_DAY = 0
private const val MODE_WEEK = 1
private const val MODE_MONTH = 2

/** 行程頁:本日時間軸(預設)/ 本週 / 本月可切換,下方是沒有時間的小任務。 */
@Composable
fun HomeScreen(
    reminders: List<Reminder>,
    notifMissing: Boolean,
    fullScreenMissing: Boolean,
    exactAlarmMissing: Boolean,
    overlayMissing: Boolean,
    silenced: Boolean,
    crashText: String?,
    onGrantNotif: () -> Unit,
    onGrantFullScreen: () -> Unit,
    onGrantExactAlarm: () -> Unit,
    onGrantOverlay: () -> Unit,
    onOpenSound: () -> Unit,
    onCopyCrash: () -> Unit,
    onClearCrash: () -> Unit,
    onEdit: (Reminder) -> Unit,
    onToggle: (Reminder) -> Unit,
    onDelete: (Reminder) -> Unit,
    onTestAlarm: () -> Unit,
    onOpenBriefing: () -> Unit,
) {
    var mode by remember { mutableStateOf(MODE_DAY) }
    var anchor by remember { mutableStateOf(LocalDate.now()) }
    var selected by remember { mutableStateOf(LocalDate.now()) }
    var editing by remember { mutableStateOf<Reminder?>(null) }
    var showQuick by remember { mutableStateOf(false) }
    var now by remember { mutableStateOf(LocalDateTime.now()) }

    // 每半分鐘更新「現在」,時間軸上的線才會跟著走
    DisposableEffect(Unit) {
        val h = Handler(Looper.getMainLooper())
        val tick = object : Runnable {
            override fun run() {
                now = LocalDateTime.now()
                h.postDelayed(this, 30_000L)
            }
        }
        h.postDelayed(tick, 30_000L)
        onDispose { h.removeCallbacks(tick) }
    }

    val today = now.toLocalDate()
    val all = reminders.toList()
    val weekStart = anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val month = YearMonth.from(anchor)
    val occs = when (mode) {
        MODE_DAY -> ScheduleModel.occurrences(all, anchor, anchor.plusDays(1))
        MODE_WEEK -> ScheduleModel.occurrences(all, weekStart, weekStart.plusDays(7))
        else -> ScheduleModel.occurrences(all, month.atDay(1), month.plusMonths(1).atDay(1))
    }
    val quick = all.filter { !ScheduleModel.isTimed(it) }
        .sortedWith(compareBy<Reminder>({ it.done }, { it.start }))

    fun step(dir: Int) {
        anchor = when (mode) {
            MODE_DAY -> anchor.plusDays(dir.toLong())
            MODE_WEEK -> anchor.plusWeeks(dir.toLong())
            else -> anchor.plusMonths(dir.toLong())
        }
        if (mode == MODE_MONTH) selected = anchor.withDayOfMonth(1)
    }

    val label = when (mode) {
        MODE_DAY -> anchor.format(DAY_LABEL) + if (anchor == today) "  今天" else ""
        MODE_WEEK -> "${weekStart.format(MD)} – ${weekStart.plusDays(6).format(MD)}" +
            if (weekStart == today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))) "  本週" else ""
        else -> "${month.year} 年 ${month.monthValue} 月" + if (month == YearMonth.from(today)) "  本月" else ""
    }
    val atToday = when (mode) {
        MODE_DAY -> anchor == today
        MODE_WEEK -> weekStart == today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        else -> month == YearMonth.from(today)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text("行程", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(
                    today.format(DAY_LABEL),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                HeaderAction(Glyph.CHECKLIST, "小任務", quick.count { !it.done }) { showQuick = true }
                HeaderAction(Glyph.SUN, "簡報", 0, onOpenBriefing)
            }
        }

        ModeSwitch(selected = mode, onSelect = { mode = it })

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(onClick = { step(-1) }) { Text("‹", fontSize = 22.sp) }
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable {
                    anchor = today
                    selected = today
                },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!atToday) {
                    TextButton(onClick = {
                        anchor = today
                        selected = today
                    }) { Text("回到今天") }
                }
                TextButton(onClick = { step(1) }) { Text("›", fontSize = 22.sp) }
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            if (exactAlarmMissing) {
                PermissionCard("需要允許「鬧鐘與提醒」,才能在準確的時間響鈴", "前往設定", onGrantExactAlarm)
            }
            if (notifMissing) {
                PermissionCard("需要允許通知,鬧鐘才會響", "允許通知", onGrantNotif)
            }
            if (fullScreenMissing) {
                PermissionCard("需要允許「全螢幕通知」,鎖屏時才會跳出鬧鐘畫面", "前往設定", onGrantFullScreen)
            }
            if (overlayMissing) {
                PermissionCard(
                    "建議允許「顯示在其他應用程式上層」,用手機時鬧鐘橫幅才會一直停在畫面上方",
                    "前往設定",
                    onGrantOverlay,
                )
            }
            if (silenced) {
                PermissionCard("手機目前是勿擾的「完全靜音」,鬧鐘也不會響。請改成「僅限鬧鐘」或關閉", "前往設定", onOpenSound)
            }
            if (crashText != null) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "上次出了問題(請按「複製」貼給我)",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Text(
                            text = crashText.take(300),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onCopyCrash) { Text("複製") }
                            TextButton(onClick = onClearCrash) { Text("清除") }
                        }
                    }
                }
            }

            when (mode) {
                MODE_DAY -> {
                    DaySummary(anchor, occs, now)
                    DayTimeline(anchor, occs, now) { editing = it }
                }

                MODE_WEEK -> {
                    val open = occs.count { !it.r.done }
                    Text(
                        text = "這週共 ${occs.size} 件,還有 $open 件沒完成。點某一天可以看時間軸。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    WeekView(weekStart, occs, today, now) {
                        anchor = it
                        selected = it
                        mode = MODE_DAY
                    }
                }

                else -> {
                    MonthView(month, occs, today, selected) { selected = it }
                    val dayItems = ScheduleModel.forDay(occs, selected)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "${selected.format(DAY_LABEL)} · ${dayItems.size} 件",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        TextButton(onClick = {
                            anchor = selected
                            mode = MODE_DAY
                        }) { Text("看時間軸 ›") }
                    }
                    if (dayItems.isEmpty()) {
                        Text(
                            "這天沒有排程",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    dayItems.forEach { AgendaRow(it, now) { r -> editing = r } }
                }
            }

            TextButton(onClick = onTestAlarm, modifier = Modifier.padding(top = 8.dp)) {
                Text("測試鬧鐘(10 秒後響)")
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (showQuick) {
        QuickTaskSheet(
            quick = quick,
            onDismiss = { showQuick = false },
            onToggle = onToggle,
            onOpen = {
                showQuick = false
                editing = it
            },
        )
    }

    val ed = editing
    if (ed != null) {
        EditReminderDialog(
            r = ed,
            onSave = {
                onEdit(it)
                editing = null
            },
            onDismiss = { editing = null },
            onDelete = {
                onDelete(ed)
                editing = null
            },
            onToggleDone = {
                onToggle(ed)
                editing = null
            },
        )
    }
}

/** 本日 / 本週 / 本月 的切換鈕。 */
@Composable
private fun ModeSwitch(selected: Int, onSelect: (Int) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(50),
        color = scheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(4.dp)) {
            listOf("本日", "本週", "本月").forEachIndexed { i, text ->
                val on = i == selected
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(50))
                        .background(if (on) scheme.primary else Color.Transparent)
                        .clickable { onSelect(i) }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                        color = if (on) scheme.onPrimary else scheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** 本日頂端的「下一件」卡片與一句話摘要。 */
@Composable
private fun DaySummary(day: LocalDate, occs: List<Occ>, now: LocalDateTime) {
    val scheme = MaterialTheme.colorScheme
    val items = ScheduleModel.forDay(occs, day)
    val open = items.count { !it.r.done }
    val isToday = day == now.toLocalDate()

    if (!isToday) {
        Text(
            text = if (items.isEmpty()) "這天沒有排程" else "共 ${items.size} 件,${open} 件沒完成",
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        return
    }

    val next = items.filter { !it.r.done && !it.effectiveEnd.isBefore(now) }.minByOrNull { it.start }
    val late = items.count { !it.r.done && it.effectiveEnd.isBefore(now) && it.r.repeat.isEmpty() }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp),
        colors = CardDefaults.cardColors(containerColor = scheme.primaryContainer),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            if (next == null) {
                Text(
                    "今天沒有更多行程了",
                    style = MaterialTheme.typography.titleMedium,
                    color = scheme.onPrimaryContainer,
                )
            } else {
                val started = !next.start.isAfter(now)
                val mins = Duration.between(now, next.start).toMinutes()
                val when_ = if (started) {
                    "進行中,${next.effectiveEnd.format(HM)} 結束"
                } else if (mins < 60) {
                    "$mins 分鐘後"
                } else {
                    "${mins / 60} 小時 ${mins % 60} 分後"
                }
                Text(
                    "下一件 · $when_",
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onPrimaryContainer.copy(alpha = 0.8f),
                )
                Text(
                    "${next.start.format(HM)}  ${next.r.title}",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = scheme.onPrimaryContainer,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (next.r.location.isNotBlank()) {
                    Text(
                        "@ ${next.r.location}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onPrimaryContainer.copy(alpha = 0.8f),
                    )
                }
            }
            val bits = mutableListOf("今天共 ${items.size} 件,${open} 件沒完成")
            if (late > 0) bits += "$late 件已過時"
            Text(
                bits.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = if (late > 0) scheme.error else scheme.onPrimaryContainer.copy(alpha = 0.8f),
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/** 本月畫面下方、被選中那天的清單。 */
@Composable
private fun AgendaRow(o: Occ, now: LocalDateTime, onClick: (Reminder) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val r = o.r
    val time = if (o.end != null) "${o.start.format(HM)}–${o.end.format(HM)}" else o.start.format(HM)
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(shape)
            .background(scheme.surface)
            .clickable { onClick(r) }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(32.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(if (o.end == null) scheme.tertiary else scheme.primary),
        )
        Column(modifier = Modifier.padding(start = 10.dp)) {
            Text(
                text = r.title,
                style = MaterialTheme.typography.bodyLarge,
                textDecoration = if (r.done) TextDecoration.LineThrough else null,
                color = if (r.done) scheme.outline else scheme.onSurface,
            )
            val late = !r.done && o.effectiveEnd.isBefore(now) && r.repeat.isEmpty()
            Text(
                text = time + (if (r.repeat.isEmpty()) "" else " · ${Repeat.label(r.repeat)}") +
                    (if (r.location.isBlank()) "" else " · ${r.location}") + if (late) " · 已過時" else "",
                style = MaterialTheme.typography.bodySmall,
                color = if (late) scheme.error else scheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PermissionCard(message: String, buttonText: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = onClick) { Text(buttonText) }
        }
    }
}

/** 底部導覽列:行程 / 助理 / 設定。 */
@Composable
fun BottomBar(screen: Screen, onSelect: (Screen) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(color = scheme.surface, tonalElevation = 3.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(64.dp),
        ) {
            listOf(
                Triple(Screen.HOME, Glyph.CALENDAR, "行程"),
                Triple(Screen.ASSISTANT, Glyph.MIC, "助理"),
                Triple(Screen.SETTINGS, Glyph.SLIDERS, "設定"),
            ).forEach { (target, icon, text) ->
                val on = target == screen
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable { onSelect(target) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (on) scheme.primaryContainer else Color.Transparent)
                            .padding(horizontal = 22.dp, vertical = 3.dp),
                    ) {
                        AppIcon(icon, if (on) scheme.primary else scheme.onSurfaceVariant, 22.dp)
                    }
                    Text(
                        text = text,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                        color = if (on) scheme.primary else scheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** 標題列右邊的按鈕:圖示 + 文字,右上角可以掛紅色數字。 */
@Composable
private fun HeaderAction(glyph: Glyph, label: String, badge: Int, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box {
        Surface(
            shape = RoundedCornerShape(50),
            color = scheme.surfaceVariant,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .clickable { onClick() },
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppIcon(glyph, scheme.primary, 18.dp)
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = scheme.onSurface,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
        if (badge > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 0.dp)
                    .height(18.dp)
                    .widthIn(min = 18.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(Color(0xFFE53935))
                    .padding(horizontal = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (badge > 99) "99+" else badge.toString(),
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

/** 點「小任務」按鈕後從下方滑出的清單。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuickTaskSheet(
    quick: List<Reminder>,
    onDismiss: () -> Unit,
    onToggle: (Reminder) -> Unit,
    onOpen: (Reminder) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp),
        ) {
            Text(
                text = "小任務 · ${quick.count { !it.done }} 件待辦",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            if (quick.isEmpty()) {
                Text(
                    text = "目前沒有小任務。到「助理」說一聲,例如「買牛奶」。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                quick.forEach { r ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = r.done, onCheckedChange = { onToggle(r) })
                        Text(
                            text = r.title,
                            style = MaterialTheme.typography.bodyLarge,
                            textDecoration = if (r.done) TextDecoration.LineThrough else null,
                            color = if (r.done) scheme.outline else scheme.onSurface,
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onOpen(r) }
                                .padding(vertical = 10.dp),
                        )
                    }
                }
            }
        }
    }
}
