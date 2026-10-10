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
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
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
    private var overlayOk by mutableStateOf(true)
    private var silenced by mutableStateOf(false)
    private var crashText by mutableStateOf<String?>(null)
    private var briefingRequested by mutableStateOf(false)
    private var voiceRequested by mutableStateOf(false)
    private var reviewRequested by mutableStateOf(false)
    private var typeRequested by mutableStateOf(false)
    private var incoming by mutableStateOf<Incoming?>(null)

    /** 分享進來的文字或圖片。 */
    private fun readShare(i: Intent?) {
        if (i?.action != Intent.ACTION_SEND) return
        val type = i.type ?: ""
        if (type.startsWith("image/")) {
            @Suppress("DEPRECATION")
            val u = i.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM) ?: return
            incoming = Incoming(text = i.getStringExtra(Intent.EXTRA_TEXT) ?: "", image = u)
        } else {
            val t = i.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
            if (t.isNotEmpty()) incoming = Incoming(text = t)
        }
    }
    private var voiceListen by mutableStateOf(false)

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            refreshPermissions()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AlarmNotifier.ensureChannel(this)

        briefingRequested = intent.getBooleanExtra(BriefingNotifier.EXTRA_OPEN_BRIEFING, false)
        // 語音入口(小工具、磁貼、捷徑、語音圖示),或設定了「打開就聽」的一般啟動
        val plainLaunch = intent.action == Intent.ACTION_MAIN && !briefingRequested
        if (savedInstanceState == null &&
            (VoiceEntry.isVoice(intent) || (plainLaunch && AppSettings.openToVoice(this)))
        ) {
            voiceRequested = true
        }
        if (savedInstanceState == null && intent.action == VoiceEntry.ACTION_TYPE) typeRequested = true
        BriefingScheduler.schedule(this)
        DailyJobs.schedule(this)
        reviewRequested = intent.getBooleanExtra(DailyJobs.EXTRA_OPEN_REVIEW, false)
        if (savedInstanceState == null) readShare(intent)
        // 雲端模擬器自動測試用:adb shell am start ... --ez probe true
        if (savedInstanceState == null && intent.getBooleanExtra("probe", false)) VoiceProbe.run(this)
        if (savedInstanceState == null && intent.getBooleanExtra("probeAlarm", false)) {
            val at = System.currentTimeMillis() + 8_000L
            ReminderOps.add(this, Reminder(id = Assistant.newId(), title = "語音測試", triggerAt = at, startAt = at))
        }

        setContent {
            FreeLifeTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    var screen by remember { mutableStateOf(Screen.HOME) }

                    // 從早上的確認通知點進來時,直接開啟每日確認
                    LaunchedEffect(briefingRequested) {
                        if (briefingRequested) {
                            screen = Screen.BRIEFING
                            briefingRequested = false
                        }
                    }
                    LaunchedEffect(incoming?.stamp) {
                        if (incoming != null) screen = Screen.ASSISTANT
                    }
                    LaunchedEffect(reviewRequested) {
                        if (reviewRequested) {
                            screen = Screen.REVIEW
                            reviewRequested = false
                        }
                    }
                    LaunchedEffect(typeRequested) {
                        if (typeRequested) {
                            screen = Screen.ASSISTANT
                            typeRequested = false
                        }
                    }
                    LaunchedEffect(voiceRequested) {
                        if (voiceRequested) {
                            screen = Screen.ASSISTANT
                            voiceListen = true
                            voiceRequested = false
                        }
                    }
                    BackHandler(enabled = screen != Screen.HOME) { screen = Screen.HOME }

                    // 鍵盤打開時收起底部導覽列;導覽列顯示時,內容區不重複處理導覽列的邊距
                    val imeOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
                    val showBar = !imeOpen && screen != Screen.BRIEFING

                    Column(modifier = Modifier.fillMaxSize()) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .then(
                                    if (showBar) Modifier.consumeWindowInsets(WindowInsets.navigationBars) else Modifier,
                                ),
                        ) {
                            when (screen) {
                                Screen.HOME -> HomeScreen(
                                    reminders = reminders,
                                    notifMissing = !notifGranted,
                                    fullScreenMissing = !fullScreenOk,
                                    exactAlarmMissing = !exactAlarmOk,
                                    overlayMissing = !overlayOk,
                                    silenced = silenced,
                                    crashText = crashText,
                                    onGrantNotif = { requestNotifPermission() },
                                    onGrantFullScreen = { openFullScreenSettings() },
                                    onGrantExactAlarm = { openExactAlarmSettings() },
                                    onGrantOverlay = { openOverlaySettings() },
                                    onOpenSound = { openSoundSettings() },
                                    onCopyCrash = { copyCrash() },
                                    onClearCrash = { clearCrash() },
                                    onEdit = { updateReminder(it) },
                                    onToggle = { toggleDone(it) },
                                    onDelete = { deleteReminder(it) },
                                    onTestAlarm = { testAlarm() },
                                    onOpenBriefing = { screen = Screen.BRIEFING },
                                    onOpenReview = { screen = Screen.REVIEW },
                                    onAdd = { addReminder(it) },
                                )

                                Screen.ASSISTANT -> AssistantScreen(
                                    listen = voiceListen,
                                    onListenHandled = { voiceListen = false },
                                    onUpdate = { updateReminder(it) },
                                    incoming = incoming,
                                    onIncomingHandled = { incoming = null },
                                    reminders = reminders,
                                    onAdd = { addReminder(it) },
                                    onDelete = { deleteReminder(it) },
                                    onBack = { screen = Screen.HOME },
                                    onOpenSettings = { screen = Screen.SETTINGS },
                                )

                                Screen.BRIEFING -> BriefingScreen(
                                    reminders = reminders,
                                    onAdd = { addReminder(it) },
                                    onBack = { screen = Screen.HOME },
                                    onOpenSettings = { screen = Screen.SETTINGS },
                                )

                                Screen.SETTINGS -> SettingsScreen(
                                    onBack = { screen = Screen.HOME },
                                    onTestAlarm = { testAlarm() },
                                    onDataChanged = { refreshReminders() },
                                    onOpenTimetable = { screen = Screen.TIMETABLE },
                                )

                                Screen.TIMETABLE -> TimetableScreen(
                                    reminders = reminders,
                                    onSave = { list, replace ->
                                        if (replace) reminders.filter { it.tag == TIMETABLE_TAG }.toList().forEach { deleteReminder(it) }
                                        list.forEach { addReminder(it) }
                                        screen = Screen.HOME
                                    },
                                    onBack = { screen = Screen.SETTINGS },
                                )

                                Screen.REVIEW -> ReviewScreen(reminders = reminders, onBack = { screen = Screen.HOME })
                            }
                        }
                        if (showBar) BottomBar(screen) { screen = it }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(BriefingNotifier.EXTRA_OPEN_BRIEFING, false)) {
            briefingRequested = true
        }
        if (VoiceEntry.isVoice(intent)) voiceRequested = true
        if (intent.action == VoiceEntry.ACTION_TYPE) typeRequested = true
        if (intent.getBooleanExtra(DailyJobs.EXTRA_OPEN_REVIEW, false)) reviewRequested = true
        readShare(intent)
    }

    override fun onPause() {
        super.onPause()
        VoiceWidget.refresh(this)
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
        overlayOk = Settings.canDrawOverlays(this)
        silenced = Conflicts.totalSilence(this)
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

    private fun openOverlaySettings() {
        val pkg = Uri.parse("package:$packageName")
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg))
        } catch (e: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg))
        }
    }

    private fun openSoundSettings() {
        try {
            startActivity(Intent(Settings.ACTION_SOUND_SETTINGS))
        } catch (e: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
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

    private fun updateReminder(r: Reminder) {
        AlarmScheduler.cancel(this, r.id)
        ReminderStore.upsert(this, r)
        AlarmScheduler.schedule(this, r)
        refreshReminders()
        refreshPermissions()
    }

    private fun toggleDone(r: Reminder) {
        if (r.repeat.isNotEmpty() && !r.done) {
            // 重複提醒沒有「完成」:勾選代表略過這一次,直接排到下一次
            val next = Repeat.advance(Repeat.shift(r), System.currentTimeMillis())
            AlarmScheduler.cancel(this, r.id)
            ReminderStore.upsert(this, next)
            AlarmScheduler.schedule(this, next)
            refreshReminders()
            return
        }
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
        addReminder(Reminder(id = now, title = "測試鬧鐘", triggerAt = now + 10_000L, startAt = now + 10_000L))
    }
}
