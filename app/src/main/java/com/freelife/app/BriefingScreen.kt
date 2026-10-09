package com.freelife.app

import android.app.TimePickerDialog
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import android.content.Intent
import android.media.RingtoneManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import java.time.LocalDateTime

enum class Screen { HOME, BRIEFING, SETTINGS, ASSISTANT, REVIEW, TIMETABLE }

private fun itemText(i: Item): String {
    val loc = if (i.r.location.isBlank()) "" else " @${i.r.location}"
    return "${Briefing.span(i)}  ${i.r.title}$loc"
}

@Composable
private fun PlanSection(title: String, items: List<Item>) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
    if (items.isEmpty()) {
        Text(
            text = "沒有排程",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        items.forEach { Text(text = itemText(it), style = MaterialTheme.typography.bodyLarge) }
    }
}

/** 每日確認:看今天與明天的行程,按下確認後給三點建議。 */
@Composable
fun BriefingScreen(
    reminders: List<Reminder>,
    onAdd: (Reminder) -> Unit,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val ctx = LocalContext.current
    val plan = remember { Briefing.collect(LocalDateTime.now(), reminders.toList()) }
    var loading by remember { mutableStateOf(false) }
    var suggestions by remember { mutableStateOf<List<Suggestion>?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var added by remember { mutableStateOf(setOf<Int>()) }
    var greeting by remember { mutableStateOf(Briefing.greeting(ctx, plan)) }
    val speaker = remember { Speaker(ctx) }
    val main = remember { Handler(Looper.getMainLooper()) }

    DisposableEffect(Unit) {
        onDispose { speaker.shutdown() }
    }

    fun readAloud(g: String, list: List<Suggestion>) {
        val body = list.mapIndexed { i, s -> "第${i + 1}點,${s.text}" }.joinToString("。")
        speaker.speak(if (list.isEmpty()) g else "$g。$body")
    }

    fun generate() {
        val rules = Briefing.rules(plan)
        if (!Llm.configured(ctx)) {
            suggestions = rules
            note = "目前使用內建建議。到「設定」填入 Claude API 金鑰,可改由 AI 產生,並用你設定的個性說話。"
            if (AppSettings.voiceReply(ctx)) readAloud(greeting, rules)
            return
        }
        loading = true
        Thread {
            val result = try {
                Result.success(Briefing.aiBriefing(ctx, plan))
            } catch (e: Exception) {
                Result.failure(e)
            }
            main.post {
                val ok = result.getOrNull()
                if (ok != null) {
                    greeting = ok.first
                    suggestions = ok.second.map { Suggestion(it) }
                    note = "由 Claude 產生。行程標題會送到 Anthropic 的伺服器。"
                } else {
                    suggestions = rules
                    note = "AI 連線失敗,改用內建建議。原因:${result.exceptionOrNull()?.message}"
                }
                loading = false
                if (AppSettings.voiceReply(ctx)) readAloud(greeting, suggestions ?: emptyList())
            }
        }.start()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = "每日確認", style = MaterialTheme.typography.headlineMedium)
            TextButton(onClick = onBack) { Text("返回") }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text(
                    text = greeting,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(12.dp),
                )
            }
            PlanSection("今天剩下的", plan.today)
            PlanSection("明天", plan.tomorrow)
            if (plan.overdue.isNotEmpty()) PlanSection("已過時還沒完成", plan.overdue)
            Text(
                text = "小任務:${plan.quick.size} 件",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )

            Spacer(Modifier.height(16.dp))
            val list = suggestions
            if (list == null) {
                Button(
                    onClick = { generate() },
                    enabled = !loading,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (loading) "秘書思考中…" else "確認行程,給我三點建議")
                }
            } else {
                Text(
                    text = "三點建議",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                list.forEachIndexed { idx, s ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "${idx + 1}. ${s.text}",
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            val action = s.action
                            val label = s.actionLabel
                            if (action != null && label != null) {
                                if (idx in added) {
                                    Text(
                                        text = "已加入提醒",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(top = 8.dp),
                                    )
                                } else {
                                    OutlinedButton(
                                        onClick = {
                                            onAdd(action)
                                            added = added + idx
                                        },
                                        modifier = Modifier.padding(top = 8.dp),
                                    ) {
                                        Text(label)
                                    }
                                }
                            }
                        }
                    }
                }
                val n = note
                if (n != null) {
                    Text(
                        text = n,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
                Row(modifier = Modifier.padding(top = 8.dp)) {
                    TextButton(onClick = { readAloud(greeting, list) }) {
                        AppIcon(Glyph.SPEAKER, MaterialTheme.colorScheme.primary, 18.dp, Modifier.padding(end = 6.dp))
                        Text("朗讀")
                    }
                    TextButton(onClick = onOpenSettings) { Text("設定") }
                }
                Button(
                    onClick = onBack,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, bottom = 16.dp),
                ) {
                    Text("完成")
                }
            }
        }
    }
}

/** 設定:每日確認時間、Claude API 金鑰(選填)。 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onTestAlarm: () -> Unit = {},
    onDataChanged: () -> Unit = {},
    onOpenTimetable: () -> Unit = {},
) {
    val ctx = LocalContext.current
    var enabled by remember { mutableStateOf(AppSettings.briefingEnabled(ctx)) }
    var minutes by remember { mutableStateOf(AppSettings.briefingMinutes(ctx)) }
    var key by remember { mutableStateOf(AppSettings.apiKey(ctx)) }
    var model by remember { mutableStateOf(AppSettings.model(ctx)) }
    var aName by remember { mutableStateOf(AppSettings.assistantName(ctx).let { if (it == "小助") "" else it }) }
    var aAddress by remember { mutableStateOf(AppSettings.address(ctx).let { if (it == "長官") "" else it }) }
    var tone by remember { mutableStateOf(AppSettings.tone(ctx)) }
    var conflictAsk by remember { mutableStateOf(AppSettings.conflictAsk(ctx)) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var voiceReplyOn by remember { mutableStateOf(AppSettings.voiceReply(ctx)) }
    var thriftOn by remember { mutableStateOf(AppSettings.thrift(ctx)) }
    var soundName by remember { mutableStateOf(AlarmSound.title(ctx)) }
    var openToVoice by remember { mutableStateOf(AppSettings.openToVoice(ctx)) }
    var alarmMode by remember { mutableStateOf(AppSettings.alarmMode(ctx)) }
    var nightOn by remember { mutableStateOf(AppSettings.nightEnabled(ctx)) }
    var nightMin by remember { mutableStateOf(AppSettings.nightMinutes(ctx)) }
    var eveningOn by remember { mutableStateOf(AppSettings.eveningEnabled(ctx)) }
    var eveningMin by remember { mutableStateOf(AppSettings.eveningMinutes(ctx)) }
    var weeklyOn by remember { mutableStateOf(AppSettings.weeklyEnabled(ctx)) }
    var prepMin by remember { mutableStateOf(AppSettings.prepMinutes(ctx)) }
    var sleepMin by remember { mutableStateOf(AppSettings.sleepMinutes(ctx)) }
    var backupMsg by remember { mutableStateOf<String?>(null) }
    val stamp = java.time.LocalDate.now().toString()
    val exportJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            backupMsg = try {
                Backup.write(ctx, uri, Backup.exportJson(ctx))
                "備份完成。"
            } catch (e: Exception) {
                "備份失敗:${e.message}"
            }
        }
    }
    val exportIcs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/calendar")) { uri ->
        if (uri != null) {
            backupMsg = try {
                Backup.write(ctx, uri, Backup.exportIcs(ctx))
                "已匯出行事曆檔,可以匯入 Google 日曆。"
            } catch (e: Exception) {
                "匯出失敗:${e.message}"
            }
        }
    }
    val importJson = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            backupMsg = try {
                val n = Backup.importJson(ctx, Backup.read(ctx, uri))
                onDataChanged()
                "已還原 $n 筆。"
            } catch (e: Exception) {
                "還原失敗,檔案格式不對:${e.message}"
            }
        }
    }
    fun resched() = DailyJobs.schedule(ctx)
    var soundKey by remember { mutableStateOf(AppSettings.alarmSound(ctx)) }
    var volume by remember { mutableStateOf(AppSettings.alarmVolume(ctx).toFloat()) }
    var fade by remember { mutableStateOf(AppSettings.alarmFade(ctx)) }
    val soundPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == android.app.Activity.RESULT_OK) {
            @Suppress("DEPRECATION")
            val u = res.data?.getParcelableExtra<android.net.Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            // 選到「預設」時系統會回傳預設鬧鐘的 uri;存成空字串代表跟著系統
            val isDefault = u == null || u == RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            AppSettings.setAlarmSound(ctx, if (isDefault) "" else u.toString())
            soundKey = AppSettings.alarmSound(ctx)
            soundName = AlarmSound.title(ctx)
            AlarmSound.preview(ctx)
        }
    }
    DisposableEffect(Unit) { onDispose { AlarmSound.stopPreview() } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = "設定", style = MaterialTheme.typography.headlineMedium)
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = "每日確認",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 16.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("每天早上確認行程")
                Switch(
                    checked = enabled,
                    onCheckedChange = {
                        enabled = it
                        AppSettings.setBriefingEnabled(ctx, it)
                        BriefingScheduler.schedule(ctx)
                    },
                )
            }
            TextButton(
                onClick = {
                    TimePickerDialog(
                        ctx,
                        { _, h, m ->
                            minutes = h * 60 + m
                            AppSettings.setBriefingMinutes(ctx, minutes)
                            BriefingScheduler.schedule(ctx)
                        },
                        minutes / 60,
                        minutes % 60,
                        true,
                    ).show()
                },
            ) {
                Text("時間 %02d:%02d".format(minutes / 60, minutes % 60))
            }

            SectionTitle("每日助理")
            TimeSwitchRow("睡前預告明天", nightOn, nightMin, {
                nightOn = it
                AppSettings.setNightEnabled(ctx, it)
                resched()
            }, {
                nightMin = it
                AppSettings.setNightMinutes(ctx, it)
                resched()
            })
            Text("起床前準備", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
            Chips(listOf(30 to "30 分", 45 to "45 分", 60 to "1 小時", 90 to "1.5 小時"), prepMin) {
                prepMin = it
                AppSettings.setPrepMinutes(ctx, it)
            }
            Text("睡眠時間", style = MaterialTheme.typography.bodyMedium)
            Chips(listOf(420 to "7 小時", 450 to "7.5 小時", 480 to "8 小時"), sleepMin) {
                sleepMin = it
                AppSettings.setSleepMinutes(ctx, it)
            }
            TimeSwitchRow("傍晚追問沒完成的事", eveningOn, eveningMin, {
                eveningOn = it
                AppSettings.setEveningEnabled(ctx, it)
                resched()
            }, {
                eveningMin = it
                AppSettings.setEveningMinutes(ctx, it)
                resched()
            })
            TimeSwitchRow("每週回顧(週日 20:00)", weeklyOn, null, {
                weeklyOn = it
                AppSettings.setWeeklyEnabled(ctx, it)
                resched()
            })

            Text("沒收到通知?按下面試看看:", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                fun check(): Boolean {
                    val ok = androidx.core.app.NotificationManagerCompat.from(ctx).areNotificationsEnabled()
                    if (!ok) android.widget.Toast.makeText(ctx, "手機把這個 App 的通知關掉了,請到 設定 → 應用程式 → Free Life → 通知 打開", android.widget.Toast.LENGTH_LONG).show()
                    return ok
                }
                OutlinedButton(onClick = { if (check()) DailyJobs.showNight(ctx) }) { Text("睡前") }
                OutlinedButton(onClick = {
                    if (check() && DailyJobs.showEvening(ctx) == 0) {
                        android.widget.Toast.makeText(ctx, "目前沒有過時未完成的事,所以不會通知", android.widget.Toast.LENGTH_LONG).show()
                    }
                }) { Text("傍晚") }
                OutlinedButton(onClick = { if (check()) DailyJobs.showWeekly(ctx) }) { Text("每週") }
            }

            SectionTitle("課表")
            OutlinedButton(onClick = onOpenTimetable) { Text("設定這學期的課表") }

            SectionTitle("備份")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { exportJson.launch("freelife-備份-$stamp.json") }) { Text("備份") }
                OutlinedButton(onClick = { importJson.launch(arrayOf("application/json", "application/octet-stream", "*/*")) }) { Text("還原") }
                OutlinedButton(onClick = { exportIcs.launch("freelife-$stamp.ics") }) { Text("匯出行事曆") }
            }
            Text(
                "備份檔可以存到雲端硬碟;換手機時按「還原」選那個檔案。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            backupMsg?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp)) }

            Text(
                text = "語音入口",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 24.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "點桌面圖示時直接開始聽",
                    modifier = Modifier.weight(1f).padding(end = 12.dp),
                )
                Switch(checked = openToVoice, onCheckedChange = {
                    openToVoice = it
                    AppSettings.setOpenToVoice(ctx, it)
                })
            }
            Text(
                text = "側邊鍵雙擊:手機「設定 → 進階功能 → 側邊按鈕 → 按兩下 → 開啟應用程式」,選「Free Life 語音」。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )

            Text(
                text = "鬧鐘響法",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 24.dp),
            )
            Chips(
                listOf("voice" to "語音播報", "ring" to "鈴聲", "both" to "鈴聲加語音"),
                alarmMode,
            ) {
                alarmMode = it
                AppSettings.setAlarmMode(ctx, it)
            }
            if (alarmMode == "voice") {
                Text(
                    "響起時用語音唸出內容,例如「${AppSettings.address(ctx)},十分鐘後開會,地點三樓會議室」,唸到你處理為止。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "鈴聲:$soundName",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
            AlarmSound.BUILTIN.forEach { (key, label, desc) ->
                val on = soundKey == "builtin:$key"
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .clickable {
                            AppSettings.setAlarmSound(ctx, "builtin:$key")
                            soundKey = "builtin:$key"
                            soundName = AlarmSound.title(ctx)
                            AlarmSound.preview(ctx)
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = on, onClick = null)
                    Column(modifier = Modifier.padding(start = 10.dp)) {
                        Text("賈維斯・$label", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            desc,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = {
                    val i = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "選擇鬧鐘鈴聲")
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                        .putExtra(
                            RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                            AlarmSound.chosen(ctx) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                        )
                    soundPicker.launch(i)
                }) { Text("系統鈴聲…") }
                TextButton(onClick = { AlarmSound.preview(ctx) }) { Text("試聽 5 秒") }
                TextButton(onClick = {
                    AppSettings.setAlarmSound(ctx, "")
                    soundKey = ""
                    soundName = AlarmSound.title(ctx)
                }) { Text("恢復預設") }
            }
            Text(
                text = "音量 ${volume.toInt()}%",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
            Slider(
                value = volume,
                onValueChange = { volume = it },
                onValueChangeFinished = { AppSettings.setAlarmVolume(ctx, volume.toInt()) },
                valueRange = 20f..100f,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "漸強(從小聲慢慢變大)",
                    modifier = Modifier.weight(1f).padding(end = 12.dp),
                )
                Switch(checked = fade, onCheckedChange = {
                    fade = it
                    AppSettings.setAlarmFade(ctx, it)
                })
            }

            Text(
                text = "會議中的提醒",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 24.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "排程進行中先靜音問我",
                    modifier = Modifier.weight(1f).padding(end = 12.dp),
                )
                Switch(
                    checked = conflictAsk,
                    onCheckedChange = {
                        conflictAsk = it
                        AppSettings.setConflictAsk(ctx, it)
                    },
                )
            }
            Text(
                text = "提醒響起時若有排程進行中,會先靜音問你;3 分鐘沒處理就照常響。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )

            Text(
                text = "助理",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 24.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("朗讀助理的回覆")
                Switch(checked = voiceReplyOn, onCheckedChange = {
                    voiceReplyOn = it
                    AppSettings.setVoiceReply(ctx, it)
                })
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "省錢模式(簡單句子不用 AI)",
                    modifier = Modifier.weight(1f).padding(end = 12.dp),
                )
                Switch(checked = thriftOn, onCheckedChange = {
                    thriftOn = it
                    AppSettings.setThrift(ctx, it)
                })
            }

            Text(
                text = "助理個性",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 24.dp),
            )
            OutlinedTextField(
                value = aName,
                onValueChange = {
                    aName = it
                    AppSettings.setAssistantName(ctx, it)
                },
                label = { Text("助理的名字(預設:小助)") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            )
            OutlinedTextField(
                value = aAddress,
                onValueChange = {
                    aAddress = it
                    AppSettings.setAddress(ctx, it)
                },
                label = { Text("助理怎麼稱呼你(預設:長官)") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            )
            Text(
                text = "語氣",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Persona.TONES.forEach { (code, label) ->
                    if (tone == code) {
                        Button(onClick = {}) { Text(label) }
                    } else {
                        OutlinedButton(onClick = {
                            tone = code
                            AppSettings.setTone(ctx, code)
                        }) { Text(label) }
                    }
                }
            }

            Text(
                text = "AI(選填)",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 24.dp),
            )
            Text(
                text = "不填也能用內建規則。填入金鑰後,助理能聽懂整句話。啟用後行程標題與時間會送到 Anthropic,依用量另外計費。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            OutlinedTextField(
                value = key,
                onValueChange = {
                    key = it
                    AppSettings.setApiKey(ctx, it)
                },
                label = { Text("Claude API 金鑰") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            )
            OutlinedTextField(
                value = model,
                onValueChange = {
                    model = it
                    AppSettings.setModel(ctx, it)
                },
                label = { Text("Claude 模型") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            )
            Button(
                onClick = {
                    testing = true
                    testResult = "測試中…"
                    val k = key.trim()
                    val m = model.trim().ifEmpty { DEFAULT_MODEL }
                    Thread {
                        val err = Llm.test(k, m)
                        Handler(Looper.getMainLooper()).post {
                            testResult = if (err == null) "連線成功,金鑰可用。" else "失敗:$err"
                            testing = false
                        }
                    }.start()
                },
                enabled = key.isNotBlank() && !testing,
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Text("測試金鑰")
            }
            TextButton(onClick = onTestAlarm, modifier = Modifier.padding(top = 8.dp)) {
                Text("測試鬧鐘(10 秒後響)")
            }
            val result = testResult
            if (result != null) {
                Text(
                    text = result,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
                )
            }
        }
    }
}
