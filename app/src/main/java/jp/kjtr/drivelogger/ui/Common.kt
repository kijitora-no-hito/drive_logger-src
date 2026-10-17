package jp.kjtr.drivelogger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jp.kjtr.drivelogger.R

/** 地図と情報の境目のつまみ（自転車ルートロガーと同じ形）。[totalPx] は地図 + 情報の長さ。離したら [onStopped]（設定に保存）。 */
@Composable
fun RatioHandle(vertical: Boolean, totalPx: Float, ratio: Float, onRatio: (Float) -> Unit, onStopped: () -> Unit) {
    val dragState = androidx.compose.foundation.gestures.rememberDraggableState { delta ->
        if (totalPx > 0f) onRatio((ratio + delta / totalPx).coerceIn(jp.kjtr.drivelogger.data.SettingsStore.MAP_RATIO_MIN, jp.kjtr.drivelogger.data.SettingsStore.MAP_RATIO_MAX))
    }
    val desc = stringResource(R.string.map_resize_handle)
    androidx.compose.foundation.layout.Box(
        (if (vertical) Modifier.fillMaxWidth().height(22.dp) else Modifier.fillMaxHeight().width(22.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .draggable(
                state = dragState,
                orientation = if (vertical) androidx.compose.foundation.gestures.Orientation.Vertical else androidx.compose.foundation.gestures.Orientation.Horizontal,
                onDragStopped = { onStopped() },
            )
            .semantics { contentDescription = desc },
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.layout.Box(
            (if (vertical) Modifier.width(40.dp).height(5.dp) else Modifier.width(5.dp).height(40.dp))
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), RoundedCornerShape(3.dp)),
        )
    }
}

/** 数値 1 つ（ラベル + 値）。 */
@Composable
fun StatCell(label: String, value: String, modifier: Modifier = Modifier, valueSize: TextUnit = 22.sp, unit: String? = null) {
    Column(modifier.padding(horizontal = 4.dp, vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, fontSize = valueSize, fontWeight = FontWeight.Bold, maxLines = 1)
            if (unit != null) Text(" $unit", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(bottom = 3.dp))
        }
    }
}

/** HUD のパネル（半透明の黒地）。 */
@Composable
fun Modifier.hudPanel(): Modifier = this
    .clip(RoundedCornerShape(10.dp))
    .background(colorResource(R.color.hud_panel_bg))
    .padding(horizontal = 10.dp, vertical = 6.dp)

@Composable
fun ConfirmDialog(title: String, text: String?, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = text?.let { { Text(it) } },
        confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun TextInputDialog(title: String, initial: String, onDone: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, modifier = Modifier.fillMaxWidth().focusRequester(focus))
        },
        confirmButton = { TextButton(onClick = { onDone(text.text.trim()); onDismiss() }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** 設定の 1 行（題・説明・スイッチ）。 */
@Composable
fun SwitchRow(title: String, desc: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (desc != null) Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/** 選択肢のダイアログ。 */
@Composable
fun <T> ChoiceDialog(title: String, options: List<T>, selected: T, label: @Composable (T) -> String, onPick: (T) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { o ->
                    Row(Modifier.fillMaxWidth().clickable { onPick(o); onDismiss() }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = o == selected, onClick = { onPick(o); onDismiss() })
                        Text(label(o))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

/** 設定の 1 行（題・今の値。押すと選ぶ）。 */
@Composable
fun ValueRow(title: String, value: String, enabled: Boolean = true, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
    }
}
