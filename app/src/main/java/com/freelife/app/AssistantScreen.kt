package com.freelife.app

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
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
    /** 需要你按「確認」才會執行的變更(分享、拍照、修改、刪除、排空檔)。 */
    val proposal: Proposal? = null,
    /** 確認卡處理後的結果文字(「已加入」「已取消」);空字串代表還沒處理。 */
    val resolved: String = "",
)

/** 待確認的變更。 */
data class Proposal(
    val adds: List<Reminder> = emptyList(),
    val updates: List<Pair<Reminder, Reminder>> = emptyList(),
    val deletes: List<Reminder> = emptyList(),
) {
    val isEmpty: Boolean get() = adds.isEmpty() && updates.isEmpty() && deletes.isEmpty()
}

/** 從別的 App 分享進來的內容。 */
data class Incoming(val text: String = "", val image: android.net.Uri? = null, val stamp: Long = System.nanoTime())

/**
 * 助理對話:打字或按麥克風用說的。
 * 有填 AI 金鑰時由 AI 理解整句話(可拆成多筆、會反問);沒有金鑰時用內建規則解析。
 */
@Composable
fun AssistantScreen(
    listen: Boolean = false,
    onListenHandled: () -> Unit = {},
    reminders: List<Reminder>,
    onAdd: (Reminder) -> Unit,
    onDelete: (Reminder) -> Unit,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onUpdate: (Reminder) -> Unit = {},
    incoming: Incoming? = null,
    onIncomingHandled: () -> Unit = {},
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
    val voiceReply = AppSettings.voiceReply(ctx)
    val thrift = AppSettings.thrift(ctx)
    // AI 剛問了問題、正在等你回答時,下一句一定交給 AI
    var aiWaiting by remember { mutableStateOf(false) }
    // 語音辨識的其他候選,點一下可以換掉輸入框裡的字
    var alts by remember { mutableStateOf<List<String>>(emptyList()) }
    val listState = rememberLazyListState()
    val speaker = remember { Speaker(ctx) }
    // 麥克風回傳時要呼叫 send,但 send 在後面才定義,用一個容器接起來
    val sendHolder = remember { arrayOfNulls<(String) -> Unit>(1) }
    // 分享進來的內容:規則解析的結果也先給你確認,不直接入庫
    var confirmMode by remember { mutableStateOf(false) }
    var photoMenu by remember { mutableStateOf(false) }
    val photoUri = remember { arrayOfNulls<android.net.Uri>(1) }

    DisposableEffect(Unit) {
        onDispose { speaker.shutdown() }
    }

    val speechLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val list = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS) ?: arrayListOf()
            val best = VoiceFix.best(list, LocalDateTime.now())
            if (best.isNotBlank()) {
                // 先留在輸入框,讓你確認或修改後再送出
                input = best
                alts = list.map { VoiceFix.clean(it) }.filter { it.isNotEmpty() && it != best }.distinct().take(4)
            }
        }
    }

    fun startListening() {
        try {
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-TW")
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "zh-TW")
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                .putExtra(RecognizerIntent.EXTRA_PROMPT, "請說…例如:明天下午三點開會")
                .putStringArrayListExtra(
                    "android.speech.extra.BIASING_STRINGS",
                    VoiceFix.hints(reminders.map { it.title }.distinct().takeLast(20)),
                )
            speechLauncher.launch(i)
        } catch (e: ActivityNotFoundException) {
            items.add(ChatItem(false, "這支手機找不到語音辨識服務,請改用鍵盤上的麥克風。"))
        }
    }

    /** 助理說一句話:顯示、(開啟時)朗讀,必要時朗讀完自動開麥克風等你回答。 */
    fun say(item: ChatItem, listenAfter: Boolean) {
        items.add(item)
        if (voiceReply) speaker.speak(item.text) {}
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
                if (confirmMode) {
                    confirmMode = false
                    say(ChatItem(false, "我讀到這件事,要加入嗎?", note = note, proposal = Proposal(adds = listOf(out.reminder))), false)
                } else {
                    onAdd(out.reminder)
                    say(ChatItem(false, out.message, created = listOf(out.reminder), note = note), false)
                }
            }
        }
    }

    /** AI 的結果:新增直接加入(分享/拍照則先確認);修改、刪除、排空檔一律先確認。 */
    fun handleTurn(turn: AiTurn, confirmAdds: Boolean) {
        val planned = turn.plan?.let { Planner.plan(reminders.toList(), it, LocalDateTime.now()) } ?: emptyList()
        val adds = if (confirmAdds) turn.created + planned else planned
        val proposal = Proposal(adds = adds, updates = turn.updates, deletes = turn.deletes)
        val direct = if (confirmAdds) emptyList() else turn.created
        direct.forEach { onAdd(it) }
        val note = conflictNote(direct + adds)
        var text = turn.say
        if (turn.plan != null && planned.isEmpty()) text += "(這段期間找不到足夠的空檔。)"
        say(
            ChatItem(
                false, text, created = direct, note = note,
                proposal = if (proposal.isEmpty) null else proposal,
            ),
            turn.ask,
        )
    }

    fun applyProposal(idx: Int, ok: Boolean) {
        val item = items.getOrNull(idx) ?: return
        val p = item.proposal ?: return
        if (!ok) {
            items[idx] = item.copy(resolved = "已取消")
            return
        }
        p.adds.forEach { onAdd(it) }
        p.updates.forEach { (_, n) -> onUpdate(n) }
        p.deletes.forEach { onDelete(it) }
        val bits = mutableListOf<String>()
        if (p.adds.isNotEmpty()) bits += "已加入 ${p.adds.size} 件"
        if (p.updates.isNotEmpty()) bits += "已修改 ${p.updates.size} 件"
        if (p.deletes.isNotEmpty()) bits += "已刪除 ${p.deletes.size} 件"
        items[idx] = item.copy(resolved = bits.joinToString("、"))
    }

    fun send(text: String) {
        val t = text.trim()
        if (t.isEmpty() || busy) return
        input = ""
        alts = emptyList()
        speaker.stop()
        items.add(ChatItem(true, t))
        if (!aiOn && pendingAsk == null) {
            // 沒接 AI 也能排空檔:「這週要讀書三小時」
            val req = Planner.parse(t, LocalDateTime.now())
            if (req != null) {
                val blocks = Planner.plan(reminders.toList(), req, LocalDateTime.now())
                if (blocks.isEmpty()) {
                    say(ChatItem(false, "這段期間找不到足夠的空檔。"), false)
                } else {
                    say(ChatItem(false, "幫你在空檔排了 ${blocks.size} 段「${req.title}」,要加入嗎?", proposal = Proposal(adds = blocks)), false)
                }
                return
            }
        }
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
                    history.add(ChatMsg(false, turn.say))
                    aiWaiting = turn.ask
                    handleTurn(turn, confirmAdds = confirmMode)
                    confirmMode = false
                } else {
                    history.removeAt(history.size - 1)
                    items.add(ChatItem(false, "AI 連線出了問題(${result.exceptionOrNull()?.message}),這句改用內建解析。"))
                    ruleReply(t)
                }
            }
        }.start()
    }
    sendHolder[0] = { send(it) }

    fun sendImage(uri: android.net.Uri, note: String) {
        if (busy) return
        items.add(ChatItem(true, if (note.isBlank()) "(傳了一張圖片)" else "(圖片)$note"))
        if (!aiOn) {
            say(ChatItem(false, "讀圖片需要 AI。請到設定填入 Claude 金鑰,或把圖片上的文字打給我。"), false)
            return
        }
        busy = true
        val snapshot = reminders.toList()
        Thread {
            val result = try {
                val b64 = ImageUtil.jpegBase64(ctx, uri)
                Result.success(AiAssistant.respondImage(ctx, b64, note, LocalDateTime.now(), snapshot))
            } catch (e: Exception) {
                Result.failure(e)
            }
            main.post {
                busy = false
                val turn = result.getOrNull()
                if (turn != null) {
                    handleTurn(turn, confirmAdds = true)
                } else {
                    items.add(ChatItem(false, "讀不了這張圖片(${result.exceptionOrNull()?.message})。"))
                }
            }
        }.start()
    }

    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val u = photoUri[0]
        if (ok && u != null) sendImage(u, input.trim().also { input = "" })
    }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { u ->
        if (u != null) sendImage(u, input.trim().also { input = "" })
    }

    // 從 LINE 等 App 分享進來:讀出行程後先問你,不直接入庫
    LaunchedEffect(incoming?.stamp) {
        val inc = incoming ?: return@LaunchedEffect
        onIncomingHandled()
        if (inc.image != null) {
            sendImage(inc.image, inc.text)
        } else if (inc.text.isNotBlank()) {
            confirmMode = true
            pendingAsk = null
            send(inc.text.take(1500))
        }
    }

    // 只有從小工具、磁貼、捷徑進來那一次才自動開始聽
    LaunchedEffect(listen) {
        if (listen) {
            onListenHandled()
            startListening()
        }
    }

    LaunchedEffect(items.size, busy) {
        if (items.isNotEmpty()) listState.animateScrollToItem(items.lastIndex)
    }
    LaunchedEffect(Unit) {
        if (items.isEmpty()) {
            val hello = if (aiOn) {
                "${name}待命中。"
            } else {
                "${name}待命中。目前用內建規則,例如「明天下午3點開會」。"
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
            if (!aiOn) {
                Text("內建規則", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
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
                    onConfirm = { applyProposal(idx, it) },
                )
            }
            if (busy) {
                item { Text("$name 思考中…", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }

        if (alts.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("聽成這樣?", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                alts.forEach { a ->
                    OutlinedButton(onClick = {
                        alts = alts.filter { it != a } + input
                        input = a
                    }) { Text(a, maxLines = 1) }
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                OutlinedButton(
                    onClick = { photoMenu = true },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp),
                ) {
                    AppIcon(Glyph.CAMERA, MaterialTheme.colorScheme.primary, 20.dp)
                }
                androidx.compose.material3.DropdownMenu(expanded = photoMenu, onDismissRequest = { photoMenu = false }) {
                    androidx.compose.material3.DropdownMenuItem(text = { Text("拍照建行程") }, onClick = {
                        photoMenu = false
                        try {
                            val f = java.io.File(ctx.cacheDir, "shots").apply { mkdirs() }.let { java.io.File(it, "shot.jpg") }
                            val u = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
                            photoUri[0] = u
                            takePhoto.launch(u)
                        } catch (e: Exception) {
                            items.add(ChatItem(false, "開不了相機:${e.message}"))
                        }
                    })
                    androidx.compose.material3.DropdownMenuItem(text = { Text("從相簿選圖片") }, onClick = {
                        photoMenu = false
                        pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    })
                }
            }
            Spacer(Modifier.padding(start = 6.dp))
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("例如:明天下午3點開會") },
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
    onConfirm: (Boolean) -> Unit = {},
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
                            r.triggerAt != null || r.timed -> formatTrigger(r.start)
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
                item.proposal?.let { p -> ProposalView(p, item.resolved, onConfirm) }
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

/** 確認卡:列出要新增、修改、刪除的內容,按「確認」才執行。 */
@Composable
private fun ProposalView(p: Proposal, resolved: String, onConfirm: (Boolean) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    fun span(r: Reminder) = when {
        r.endAt != null -> formatRange(r.start, r.endAt)
        r.hasTime -> formatTrigger(r.start)
        else -> "小任務"
    }
    Column(modifier = Modifier.padding(top = 6.dp)) {
        p.adds.forEach { r ->
            Text(
                "+ ${span(r)} ${r.title}" + (if (r.location.isBlank()) "" else " @${r.location}") + (if (r.ringless) "(只記錄)" else ""),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.primary,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
        p.updates.forEach { (o, n) ->
            Text(
                "✎ ${o.title}:${span(o)} → ${span(n)}" + (if (o.title != n.title) "(改名為「${n.title}」)" else ""),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.tertiary,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
        p.deletes.forEach { r ->
            Text(
                "− ${span(r)} ${r.title}",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.error,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
        if (resolved.isNotEmpty()) {
            Text(resolved, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                Button(onClick = { onConfirm(true) }) { Text("確認") }
                TextButton(onClick = { onConfirm(false) }) { Text("取消") }
            }
        }
    }
}
