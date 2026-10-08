package com.freelife.app

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

private val MD_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("M/d")

/** 每週回顧:每天的排程時數長條圖、完成率、開會時數、最忙的一天,以及優化建議。 */
@Composable
fun ReviewScreen(reminders: List<Reminder>, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    var weekStart by remember { mutableStateOf(LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))) }
    var aiText by remember { mutableStateOf<String?>(null) }
    var aiBusy by remember { mutableStateOf(false) }
    val stats = WeekStats.compute(reminders.toList(), weekStart)

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
            Text("每週回顧", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            TextButton(onClick = onBack) { Text("返回") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { weekStart = weekStart.minusWeeks(1); aiText = null }) { Text("‹", fontSize = 22.sp) }
            Text(
                "${weekStart.format(MD_FMT)} – ${weekStart.plusDays(6).format(MD_FMT)}",
                style = MaterialTheme.typography.titleMedium,
            )
            TextButton(onClick = { weekStart = weekStart.plusWeeks(1); aiText = null }) { Text("›", fontSize = 22.sp) }
        }

        // 四個重點數字
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            val rate = if (stats.total > 0) stats.done * 100 / stats.total else 0
            Kpi("完成", "${stats.done}/${stats.total}", "$rate%", Modifier.weight(1f))
            Kpi("排程", stats.busyHoursText(), "", Modifier.weight(1f))
            Kpi("開會值勤", WeekStats.hoursText(stats.meetingMinutes), "", Modifier.weight(1f))
            Kpi("最忙", stats.busiestLabel() ?: "—", "", Modifier.weight(1f))
        }

        // 每天的排程時數
        Text(
            "每天排了多久",
            style = MaterialTheme.typography.titleSmall,
            color = scheme.primary,
            modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
        )
        val maxMin = (stats.perDayMinutes.maxOrNull() ?: 0L).coerceAtLeast(60L)
        val busiest = stats.busiestIndex()
        Row(
            modifier = Modifier.fillMaxWidth().height(170.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (i in 0..6) {
                val m = stats.perDayMinutes[i]
                val frac = (m.toFloat() / maxMin).coerceIn(0f, 1f)
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    Text(
                        if (m > 0) "%.1f".format(m / 60.0) else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                    )
                    Box(
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .width(22.dp)
                            .height((120 * frac).dp.coerceAtLeastDp(3))
                            .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                            .background(if (i == busiest) scheme.tertiary else scheme.primary),
                    )
                    Text(
                        WEEKDAY_CHARS[i],
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (i == busiest) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Text(
                        "${stats.perDayCount[i]} 件",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
        }

        Text(
            "建議",
            style = MaterialTheme.typography.titleSmall,
            color = scheme.primary,
            modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
        )
        stats.suggestions().forEach { s ->
            Row(modifier = Modifier.padding(vertical = 4.dp)) {
                Text("•", color = scheme.primary, modifier = Modifier.padding(end = 8.dp))
                Text(s, style = MaterialTheme.typography.bodyMedium)
            }
        }

        if (Llm.configured(ctx)) {
            val t = aiText
            if (t != null) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    colors = CardDefaults.cardColors(containerColor = scheme.primaryContainer),
                ) {
                    Text(t, modifier = Modifier.padding(14.dp), color = scheme.onPrimaryContainer)
                }
            } else {
                OutlinedButton(
                    onClick = {
                        aiBusy = true
                        val summary = buildString {
                            append("本週(${weekStart})統計:共 ${stats.total} 件,完成 ${stats.done} 件,過時未完成 ${stats.overdue} 件;")
                            append("排程總時數 ${stats.busyHoursText()},開會值勤 ${WeekStats.hoursText(stats.meetingMinutes)};")
                            append("每天排程分鐘(週一到週日):${stats.perDayMinutes.joinToString(",")};")
                            append("每天件數:${stats.perDayCount.joinToString(",")}。")
                        }
                        Thread {
                            val out = try {
                                Llm.chat(
                                    ctx,
                                    Persona.base(ctx) + "\n根據使用者這週的行程統計,給三句具體、可執行的下週優化建議,每句一行,不要編號。",
                                    listOf(ChatMsg(true, summary)),
                                    300,
                                ).trim()
                            } catch (e: Exception) {
                                "暫時拿不到 AI 建議:${e.message}"
                            }
                            Handler(Looper.getMainLooper()).post {
                                aiText = out
                                aiBusy = false
                            }
                        }.start()
                    },
                    enabled = !aiBusy,
                    modifier = Modifier.padding(top = 12.dp),
                ) { Text(if (aiBusy) "思考中…" else "請${AppSettings.assistantName(ctx)}給建議") }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

private fun androidx.compose.ui.unit.Dp.coerceAtLeastDp(min: Int) = if (this.value < min) min.dp else this

@Composable
private fun Kpi(label: String, value: String, sub: String, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = modifier.padding(top = 12.dp),
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
            if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.labelSmall, color = scheme.primary)
        }
    }
}
