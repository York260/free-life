package com.freelife.app

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import java.time.LocalDateTime

/** 對話裡的一則訊息。created = 這一輪建立的提醒(可復原);note = 衝突等補充說明(不朗讀)。 */
data class ChatItem(
    val fromUser: Boolean,
    val text: String,
    val created: List<Reminder> = emptyList(),
    val note: String = "",
    val chips: List<String> = emptyList(),
    val undone: Boolean = false,
)

/**
 * 助理對話:打字或按麥克風用說的。
 * 有填 AI 金鑰時由 AI 理解整句話(可拆成多筆、會反問);沒有金鑰時用內建規則解析。
 */
@Composable
fun AssistantScreen(
    reminders: List<Reminder>,
    onAdd: (Reminder) -> Unit,
    onDelete: (Reminder) -> Unit,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val ctx = LocalContext.current
    val main = remember { Handler(Looper.getMainLooper()) }
    val name = AppSettings.assistantName(ctx)
    val aiOn = Llm.configured(ctx)

    val items = remember { mutableStateListOf<ChatItem>() }
    val history = remember { mutableListOf<ChatMsg>() }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var pendingAsk by remember { mutableStateOf<Outcome.Ask?>(null) }
    var voiceReply by remember { mutableStateOf(AppSettings.voiceReply(ctx)) }
    var converse by remember { mutableStateOf(AppSettings.converse(ctx)) }
    var thrift by remember { mutableStateOf(AppSettings.thrift(ctx)) }
    // AI 剛問了問題、正在等你回答時,下一句一定交給 AI
    var aiWaiting by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val speaker = remember { Speaker(ctx) }
    // 麥克風回傳時要呼叫 send,但 send 在後面才定義,用一個容器接起來
    val sendHolder = remember { arrayOfNulls<(String) -> Unit>(1) }

    DisposableEffect(Unit) {
        onDispose { speaker.shutdown() }
    }

    val speechLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val text = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!text.isNullOrBlank()) sendHolder[0]?.invoke(text)
        }
    }

    fun startListening() {
        try {
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-TW")
                .putExtra(RecognizerIntent.EXTRA_PROMPT, "請說…")
            speechLauncher.launch(i)
        } catch (e: ActivityNotFoundException) {
            items.add(ChatItem(false, "這支手機找不到語音辨識服務,請改用鍵盤上的麥克風。"))
        }
    }

    /** 助理說一句話:顯示、(開啟時)朗讀,必要時朗讀完自動開麥克風等你回答。 */
    fun say(item: ChatItem, listenAfter: Boolean) {
        items.add(item)
        val again = listenAfter && converse
        if (voiceReply) {
            speaker.speak(item.text) { if (again) startListening() }
        } else if (again) {
            startListening()
        }
    }

    fun conflictNote(newOnes: List<Reminder>): String {
        val all = reminders.toList() + newOnes
        return newOnes.mapNotNull { r ->
            val clash = Conflicts.overlapping(all, r)
            if (clash.isEmpty()) null else "注意:「${r.title}」${Conflicts.describe(clash)}時間重疊"
        }.joinToString("\n")
    }

    fun ruleReply(t: String) {
        val now = LocalDateTime.now()
        val p = pendingAsk
        val out = if (p == null) Assistant.start(t, now) else Assistant.reply(p.draft, p.kind, t, now)
        when (out) {
            is Outcome.Ask -> {
                pendingAsk = out
                say(ChatItem(false, out.prompt, chips = out.chips), true)
            }
            is Outcome.Done -> {
                pendingAsk = null
                val note = conflictNote(listOf(out.reminder))
                onAdd(out.reminder)
                say(ChatItem(false, out.message, created = listOf(out.reminder), note = note), false)
            }
        }
    }

    fun send(text: String) {
        val t = text.trim()
        if (t.isEmpty() || busy) return
        input = ""
        speaker.stop()
        items.add(ChatItem(true, t))
        if (!aiOn || pendingAsk != null) {
            ruleReply(t)
            return
        }
        // 省錢模式:單純、一句話講完的記事,規則就能處理,不花 AI 費用
        if (thrift && !aiWaiting && looksSimple(t)) {
            val d = ReminderParser.parse(t, LocalDateTime.now())
            if (d.date != null || d.time != null) {
                ruleReply(t)
                return
            }
        }
        busy = true
        history.add(ChatMsg(true, t))
        val sent = history.toList().takeLast(10)
        val snapshot = reminders.toList()
        Thread {
            val result = try {
                Result.success(AiAssistant.respond(ctx, sent, LocalDateTime.now(), snapshot))
            } catch (e: Exception) {
                Result.failure(e)
            }
            main.post {
                busy = false
                val turn = result.getOrNull()
                if (turn != null) {
                    val note = conflictNote(turn.created)
                    turn.created.forEach { onAdd(it) }
                    history.add(ChatMsg(false, turn.say))
                    aiWaiting = turn.ask
                    say(ChatItem(false, turn.say, created = turn.created, note = note), turn.ask)
                } else {
                    history.removeAt(history.size - 1)
                    items.add(ChatItem(false, "AI 連線出了問題(${result.exceptionOrNull()?.message}),這句改用內建解析。"))
                    ruleReply(t)
                }
            }
        }.start()
    }
    sendHolder[0] = { send(it) }

    LaunchedEffect(items.size, busy) {
        if (items.isNotEmpty()) listState.animateScrollToItem(items.lastIndex)
    }
    LaunchedEffect(Unit) {
        if (items.isEmpty()) {
            val hello = if (aiOn) {
                "${name}待命中。想記什麼、想知道什麼,直接說。"
            } else {
                "${name}待命中。目前沒有接 AI,我用內建規則聽得懂的說法來記。到設定填入金鑰,我就能聽懂整句話。"
            }
            items.add(ChatItem(false, hello))
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = name, style = MaterialTheme.typography.headlineMedium)
            Row {
                TextButton(onClick = onOpenSettings) { Text("設定") }
                TextButton(onClick = onBack) { Text("返回") }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("朗讀回覆", style = MaterialTheme.typography.bodyMedium)
            Switch(checked = voiceReply, onCheckedChange = {
                voiceReply = it
                AppSettings.setVoiceReply(ctx, it)
                if (!it) speaker.stop()
            })
            Text("省錢模式", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
            Switch(checked = thrift, onCheckedChange = {
                thrift = it
                AppSettings.setThrift(ctx, it)
            })
            Text("連續對話", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
            Switch(checked = converse, onCheckedChange = {
                converse = it
                AppSettings.setConverse(ctx, it)
            })
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(items) { idx, item ->
                Bubble(
                    item = item,
                    isLast = idx == items.lastIndex,
                    showChips = pendingAsk != null && idx == items.lastIndex,
                    onChip = { send(it) },
                    onUndo = {
                        item.created.forEach { onDelete(it) }
                        items[idx] = item.copy(undone = true)
                    },
                )
            }
            if (busy) {
                item { Text("$name 思考中…", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("例如:下週三下午提醒我帶文件,前一天晚上也提醒一次") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send(input) }),
                maxLines = 3,
            )
            Spacer(Modifier.padding(start = 8.dp))
            OutlinedButton(onClick = { startListening() }) {
                AppIcon(Glyph.MIC, MaterialTheme.colorScheme.primary, 20.dp)
            }
            Spacer(Modifier.padding(start = 4.dp))
            Button(onClick = { send(input) }, enabled = !busy) { Text("送出") }
        }
    }
}

@Composable
private fun Bubble(
    item: ChatItem,
    isLast: Boolean,
    showChips: Boolean,
    onChip: (String) -> Unit,
    onUndo: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (item.fromUser) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Card(
            modifier = Modifier.widthIn(max = 320.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (item.fromUser) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            ),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(text = item.text, style = MaterialTheme.typography.bodyLarge)
                if (item.created.isNotEmpty()) {
                    item.created.forEach { r ->
                        val span = when {
                            r.endAt != null -> formatRange(r.start, r.endAt)
                            r.triggerAt != null -> formatTrigger(r.start)
                            else -> "小任務"
                        }
                        val rep = if (r.repeat.isEmpty()) "" else " ・${Repeat.label(r.repeat)}"
                        Text(
                            text = "• $span ${r.title}$rep",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    if (item.undone) {
                        Text(
                            text = "已復原",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    } else {
                        TextButton(onClick = onUndo) { Text("復原") }
                    }
                }
                if (item.note.isNotBlank()) {
                    Text(
                        text = item.note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                if (showChips && isLast && item.chips.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        item.chips.forEach { chip ->
                            OutlinedButton(onClick = { onChip(chip) }) { Text(chip) }
                        }
                    }
                }
            }
        }
    }
}

private val CHAT_HINTS = Regex(
    "[?？嗎呢吧啊]|什麼|甚麼|哪|怎麼|為什麼|幾|有空|有沒有|是不是|你|謝|早安|晚安|哈|累|建議|查|看一下|" +
        "也|另外|還有|然後|並且|前一天|前一晚|前兩天|隔天|再提醒|取消|刪除|改成|改到|調整",
)

/** 短短一句、沒有疑問和多重要求的記事,才交給免費的規則解析。 */
private fun looksSimple(t: String): Boolean = t.length <= 40 && !CHAT_HINTS.containsMatchIn(t)
