package com.freelife.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

/**
 * 開勿擾的面板:普通 / 完全、持續幾小時(以小時加減),
 * 列出這段時間會被擋下的提醒,問要不要改時間或整個行程往後挪。
 */
@Composable
fun DndPanel(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val now = remember { System.currentTimeMillis() }
    val st = remember { Dnd.current(ctx, now) }
    var level by remember { mutableStateOf(Dnd.lastLevel(ctx)) }
    var hours by remember { mutableStateOf(1) }
    var choice by remember { mutableStateOf("defer") }
    var granted by remember { mutableStateOf(Dnd.policyGranted(ctx)) }
    val until = now + hours * 3_600_000L

    Column(
        modifier = Modifier
            .padding(20.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("勿擾", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

        if (st.manual) {
            Text("目前${Dnd.label(st.level)},到 ${Dnd.hm(st.until)}。", style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    Dnd.stopManual(ctx)
                    onClose()
                }) { Text("結束勿擾") }
                OutlinedButton(onClick = {
                    Dnd.startManual(ctx, st.level, st.until + 3_600_000L)
                    onClose()
                }) { Text("延長 1 小時") }
            }
            TextButton(onClick = onClose) { Text("關閉") }
            return@Column
        }
        if (st.level > Dnd.NONE) {
            Text(
                "「${st.reason}」進行中,已自動${Dnd.label(st.level)}到 ${Dnd.hm(st.until)}。也可以另外再開手動勿擾:",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Chips(listOf(Dnd.NORMAL to "普通勿擾", Dnd.FULL to "完全勿擾"), level) { level = it }
        Text(
            if (level == Dnd.NORMAL) "只擋 Free Life 的提醒(改成靜音通知),手機其他通知照常。"
            else "擋 Free Life 的提醒,同時打開手機的勿擾。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (level == Dnd.FULL && !granted) {
            Text("要先允許 Free Life 控制手機勿擾:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = {
                ctx.startActivity(Dnd.policyIntent())
                granted = Dnd.policyGranted(ctx)
            }) { Text("前往授權") }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(enabled = hours > 1, onClick = { hours-- }) { Text("−") }
            Text(
                "$hours 小時(到 ${Dnd.hm(until)})",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            OutlinedButton(enabled = hours < 12, onClick = { hours++ }) { Text("+") }
        }

        val blocked = Dnd.blocked(ctx, now, until)
        if (blocked.isNotEmpty()) {
            Text("這段時間會被擋下 ${blocked.size} 個提醒:", style = MaterialTheme.typography.bodyMedium)
            blocked.take(5).forEach {
                Text("・${Dnd.hm(it.triggerAt!!)} ${it.title}", style = MaterialTheme.typography.bodySmall)
            }
            if (blocked.size > 5) Text("…還有 ${blocked.size - 5} 個", style = MaterialTheme.typography.bodySmall)
            listOf(
                "defer" to "提醒延到勿擾結束後(${Dnd.hm(until)})",
                "shift" to "整個行程往後挪到勿擾結束後",
                "keep" to "不改,到時只出現靜音通知",
            ).forEach { (k, label) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = choice == k, onClick = { choice = k })
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                when (choice) {
                    "defer" -> Dnd.deferRings(ctx, blocked, until)
                    "shift" -> Dnd.shiftEvents(ctx, blocked, until)
                }
                Dnd.startManual(ctx, level, until)
                val tail = if (level == Dnd.FULL && !Dnd.policyGranted(ctx)) "(手機勿擾沒授權,只擋 Free Life 的提醒)" else ""
                android.widget.Toast.makeText(ctx, "${Dnd.label(level)}到 ${Dnd.hm(until)}$tail", android.widget.Toast.LENGTH_LONG).show()
                onClose()
            }) { Text("開始勿擾") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onClose) { Text("取消") }
        }
    }
}

@Composable
fun DndDialog(onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose) {
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
            DndPanel(onClose)
        }
    }
}

/** 桌面小工具的勿擾鍵:直接跳出勿擾面板。 */
class DndActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FreeLifeTheme {
                DndDialog { finish() }
            }
        }
    }
}
