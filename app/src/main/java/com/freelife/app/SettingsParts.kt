package com.freelife.app

import android.app.TimePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** 一排可單選的小按鈕。 */
@Composable
fun <T> Chips(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        options.forEach { (v, label) ->
            val on = v == selected
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (on) scheme.primary else scheme.surfaceVariant)
                    .clickable { onSelect(v) }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (on) scheme.onPrimary else scheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 開關 + 時間(點時間可改)。 */
@Composable
fun TimeSwitchRow(
    label: String,
    enabled: Boolean,
    minutes: Int?,
    onEnabled: (Boolean) -> Unit,
    onMinutes: (Int) -> Unit = {},
) {
    val ctx = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        if (minutes != null) {
            TextButton(
                onClick = {
                    TimePickerDialog(ctx, { _, h, m -> onMinutes(h * 60 + m) }, minutes / 60, minutes % 60, true).show()
                },
                enabled = enabled,
            ) { Text("%02d:%02d".format(minutes / 60, minutes % 60)) }
        }
        Switch(checked = enabled, onCheckedChange = onEnabled)
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 24.dp, bottom = 4.dp),
    )
}

private val unused = Color.Unspecified
