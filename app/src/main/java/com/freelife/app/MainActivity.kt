package com.freelife.app

import android.Manifest
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.time.LocalDateTime

class MainActivity : ComponentActivity() {

    private val reminders = mutableStateListOf<Reminder>()
    private var notifGranted by mutableStateOf(true)
    private var fullScreenOk by mutableStateOf(true)
    private var exactAlarmOk by mutableStateOf(true)
    private var crashText by mutableStateOf<String?>(null)

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            refreshPermissions()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AlarmNotifier.ensureChannel(this)

        setContent {
            FreeLifeTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    HomeScreen(
                        reminders = reminders,
                        notifMissing = !notifGranted,
                        fullScreenMissing = !fullScreenOk,
                        exactAlarmMissing = !exactAlarmOk,
                        crashText = crashText,
                        onGrantNotif = { requestNotifPermission() },
                        onGrantFullScreen = { openFullScreenSettings() },
                        onGrantExactAlarm = { openExactAlarmSettings() },
                        onCopyCrash = { copyCrash() },
                        onClearCrash = { clearCrash() },
                        onAdd = { addReminder(it) },
                        onToggle = { toggleDone(it) },
                        onDelete = { deleteReminder(it) },
                        onTestAlarm = { testAlarm() },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshReminders()
        refreshPermissions()
    }

    private fun refreshReminders() {
        val list = ReminderStore.load(this)
        reminders.clear()
        reminders.addAll(list)
    }

    private fun refreshPermissions() {
        notifGranted = if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        fullScreenOk = if (Build.VERSION.SDK_INT >= 34) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.canUseFullScreenIntent()
        } else {
            true
        }
        exactAlarmOk = AlarmScheduler.canScheduleExact(this)
        crashText = CrashLog.load(this)
    }

    private fun openExactAlarmSettings() {
        val pkg = Uri.parse("package:$packageName")
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg))
            } else {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg))
            }
        } catch (e: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg))
        }
    }

    private fun copyCrash() {
        val text = crashText ?: return
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Free Life 當機紀錄", text))
    }

    private fun clearCrash() {
        CrashLog.clear(this)
        crashText = null
    }

    private fun requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun openFullScreenSettings() {
        val pkg = Uri.parse("package:$packageName")
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg))
            } else {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg))
            }
        } catch (e: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg))
        }
    }

    private fun addReminder(r: Reminder) {
        ReminderStore.upsert(this, r)
        AlarmScheduler.schedule(this, r)
        refreshReminders()
        refreshPermissions()
    }

    private fun toggleDone(r: Reminder) {
        val updated = r.copy(done = !r.done)
        ReminderStore.upsert(this, updated)
        if (updated.done) {
            AlarmScheduler.cancel(this, updated.id)
            AlarmNotifier.cancel(this, updated.id)
        } else {
            AlarmScheduler.schedule(this, updated)
        }
        refreshReminders()
    }

    private fun deleteReminder(r: Reminder) {
        AlarmScheduler.cancel(this, r.id)
        AlarmNotifier.cancel(this, r.id)
        ReminderStore.delete(this, r.id)
        refreshReminders()
    }

    private fun testAlarm() {
        val now = System.currentTimeMillis()
        addReminder(Reminder(id = now, title = "測試鬧鐘", triggerAt = now + 10_000L))
    }
}

@Composable
private fun HomeScreen(
    reminders: List<Reminder>,
    notifMissing: Boolean,
    fullScreenMissing: Boolean,
    exactAlarmMissing: Boolean,
    crashText: String?,
    onGrantNotif: () -> Unit,
    onGrantFullScreen: () -> Unit,
    onGrantExactAlarm: () -> Unit,
    onCopyCrash: () -> Unit,
    onClearCrash: () -> Unit,
    onAdd: (Reminder) -> Unit,
    onToggle: (Reminder) -> Unit,
    onDelete: (Reminder) -> Unit,
    onTestAlarm: () -> Unit,
) {
    var input by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<Outcome.Ask?>(null) }
    var lastMessage by remember { mutableStateOf<String?>(null) }

    fun handle(outcome: Outcome) {
        when (outcome) {
            is Outcome.Ask -> {
                pending = outcome
                lastMessage = null
            }
            is Outcome.Done -> {
                pending = null
                onAdd(outcome.reminder)
                lastMessage = outcome.message
            }
        }
    }

    fun submit(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        input = ""
        val now = LocalDateTime.now()
        val p = pending
        handle(if (p == null) Assistant.start(t, now) else Assistant.reply(p.draft, p.kind, t, now))
    }

    val scheduled = reminders
        .filter { it.isScheduled }
        .sortedWith(compareBy<Reminder>({ it.done }, { it.triggerAt ?: 0L }))
    val quick = reminders
        .filter { !it.isScheduled }
        .sortedWith(compareBy<Reminder>({ it.done }, { -it.id }))

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 16.dp),
    ) {
        Text(
            text = "Free Life",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            text = "打字,或按鍵盤上的麥克風說一句話,我來幫你記",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        if (exactAlarmMissing) {
            PermissionCard("需要允許「鬧鐘與提醒」,才能在準確的時間響鈴", "前往設定", onGrantExactAlarm)
        }
        if (notifMissing) {
            PermissionCard("需要允許通知,鬧鐘才會響", "允許通知", onGrantNotif)
        }
        if (fullScreenMissing) {
            PermissionCard("需要允許「全螢幕通知」,鎖屏時才會跳出鬧鐘畫面", "前往設定", onGrantFullScreen)
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

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            item { SectionHeader("排程 · 鬧鐘提醒") }
            if (scheduled.isEmpty()) {
                item { EmptyHint("還沒有排程。試試輸入「明天下午3點看牙醫」") }
            }
            items(scheduled, key = { it.id }) { r ->
                ReminderRow(r, onToggle, onDelete)
            }

            item { SectionHeader("隨手小事") }
            if (quick.isEmpty()) {
                item { EmptyHint("沒有時間的事會放這裡,例如輸入「買牛奶」") }
            }
            items(quick, key = { it.id }) { r ->
                ReminderRow(r, onToggle, onDelete)
            }

            item {
                TextButton(onClick = onTestAlarm) { Text("測試鬧鐘(10 秒後響)") }
            }
        }

        val p = pending
        val msg = lastMessage
        if (p != null) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(text = p.prompt, style = MaterialTheme.typography.bodyLarge)
                    Row(
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        p.chips.forEach { chip ->
                            OutlinedButton(onClick = { submit(chip) }) { Text(chip) }
                        }
                        TextButton(onClick = { pending = null }) { Text("取消") }
                    }
                }
            }
        } else if (msg != null) {
            Text(
                text = msg,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(if (pending == null) "例如:明天下午3點看牙醫" else "回答上面的問題…")
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit(input) }),
                maxLines = 3,
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = { submit(input) }) { Text("送出") }
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

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

@Composable
private fun ReminderRow(
    r: Reminder,
    onToggle: (Reminder) -> Unit,
    onDelete: (Reminder) -> Unit,
) {
    val at = r.triggerAt
    val overdue = at != null && !r.done && at < System.currentTimeMillis()
    val details = buildList<String> {
        if (at != null) add(formatTrigger(at))
        if (r.location.isNotBlank()) add(r.location)
        if (overdue) add("已過時")
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = r.done, onCheckedChange = { onToggle(r) })
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = r.title,
                style = MaterialTheme.typography.bodyLarge,
                textDecoration = if (r.done) TextDecoration.LineThrough else null,
                color = if (r.done) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
            )
            if (details.isNotEmpty()) {
                Text(
                    text = details.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        TextButton(onClick = { onDelete(r) }) { Text("刪除") }
    }
}
