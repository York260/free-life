package com.freelife.app

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 鬧鐘響起時顯示的全螢幕畫面(鎖屏上也會跳出)。 */
class AlarmActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()

        val id = intent.getLongExtra(EXTRA_ID, -1L)
        val reminder = ReminderStore.get(this, id)
        if (reminder == null) {
            finish()
            return
        }

        setContent {
            FreeLifeTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AlarmScreen(
                        title = reminder.title,
                        location = reminder.location,
                        whenText = reminder.triggerAt?.let { formatTrigger(it) } ?: "",
                        onDone = {
                            AlarmActions.done(this, id)
                            finish()
                        },
                        onSnooze10 = {
                            AlarmActions.snooze(this, id, 10)
                            finish()
                        },
                        onSnooze60 = {
                            AlarmActions.snooze(this, id, 60)
                            finish()
                        },
                    )
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}

@Composable
private fun AlarmScreen(
    title: String,
    location: String,
    whenText: String,
    onDone: () -> Unit,
    onSnooze10: () -> Unit,
    onSnooze60: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("⏰", fontSize = 64.sp)
        Spacer(Modifier.height(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineLarge,
            textAlign = TextAlign.Center,
        )
        if (location.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(text = location, style = MaterialTheme.typography.titleMedium)
        }
        if (whenText.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(text = whenText, style = MaterialTheme.typography.bodyLarge)
        }
        Spacer(Modifier.height(40.dp))
        Button(
            onClick = onDone,
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
        ) {
            Text("完成", fontSize = 22.sp)
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onSnooze10, modifier = Modifier.fillMaxWidth()) {
            Text("延後 10 分鐘")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onSnooze60, modifier = Modifier.fillMaxWidth()) {
            Text("延後 1 小時")
        }
    }
}
