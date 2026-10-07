package com.freelife.app

import android.app.TimePickerDialog
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import java.time.LocalDateTime

enum class Screen { HOME, BRIEFING, SETTINGS }

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

    fun generate() {
        val rules = Briefing.rules(plan)
        val apiKey = AppSettings.apiKey(ctx)
        if (apiKey.isBlank()) {
            suggestions = rules
            note = "目前使用內建建議。到「設定」填入 Claude API 金鑰,可改由 AI 產生。"
            return
        }
        loading = true
        val model = AppSettings.model(ctx)
        Thread {
            try {
                val ai = Briefing.aiSuggestions(apiKey, model, plan)
                suggestions = ai.map { Suggestion(it) }
                note = "由 Claude 產生($model)。行程標題會送到 Anthropic 的伺服器。"
            } catch (e: Exception) {
                suggestions = rules
                note = "AI 連線失敗,改用內建建議。原因:${e.message}"
            }
            loading = false
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
                                        text = "✓ 已加入提醒",
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
fun SettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var enabled by remember { mutableStateOf(AppSettings.briefingEnabled(ctx)) }
    var minutes by remember { mutableStateOf(AppSettings.briefingMinutes(ctx)) }
    var key by remember { mutableStateOf(AppSettings.apiKey(ctx)) }
    var model by remember { mutableStateOf(AppSettings.model(ctx)) }
    var conflictAsk by remember { mutableStateOf(AppSettings.conflictAsk(ctx)) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }

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
            TextButton(onClick = onBack) { Text("返回") }
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
                Text("每天早上提醒我確認行程")
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
                Text("提醒時間:%02d:%02d(點此修改)".format(minutes / 60, minutes % 60))
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
                    text = "提醒響起時,如果有別的排程正在進行,先靜音問我",
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
                text = "只會震動一下並顯示橫幅,可選「會後再提醒」「照常響」「關閉」。3 分鐘沒處理就改成正常響鈴,不會漏掉。" +
                    "長度超過 8 小時的排程(例如出差)不算開會。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )

            Text(
                text = "Claude AI 建議(選填)",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 24.dp),
            )
            Text(
                text = "不填也能用,會使用內建建議。填入 API 金鑰後,按「確認行程」時會把今明兩天的行程標題送到 Anthropic 由 Claude 產生建議," +
                    "依用量計費(每天一次通常很少)。金鑰只存在這支手機裡。",
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
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = model,
                onValueChange = {
                    model = it
                    AppSettings.setModel(ctx, it)
                },
                label = { Text("模型") },
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
                        testResult = try {
                            ClaudeClient.complete(k, m, "你是測試用助理。", "只回答:OK", 20)
                            "連線成功,金鑰可用。"
                        } catch (e: Exception) {
                            "失敗:${e.message}"
                        }
                        testing = false
                    }.start()
                },
                enabled = key.isNotBlank() && !testing,
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Text("測試金鑰")
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
