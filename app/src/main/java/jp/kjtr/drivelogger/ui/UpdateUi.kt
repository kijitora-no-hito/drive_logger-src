package jp.kjtr.drivelogger.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import jp.kjtr.drivelogger.R
import jp.kjtr.drivelogger.update.Releases
import jp.kjtr.drivelogger.update.UpdateLinks
import jp.kjtr.drivelogger.update.UpdateRelease
import jp.kjtr.drivelogger.update.UpdateStatus
import jp.kjtr.drivelogger.update.UpdateUi

/** ブラウザで開く（ACTION_VIEW。APK のダウンロード・インストールはブラウザと端末に任せる。REQUEST_INSTALL_PACKAGES は要求しない）。 */
fun openInBrowser(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        Toast.makeText(context, R.string.update_browser_failed, Toast.LENGTH_LONG).show()
    }
}

/**
 * ホームの上部の控えめな帯「新しい版 v… があります」［更新内容］［ダウンロード］［あとで］（DESIGN.md §10）。
 * 記録中は出さない（呼ぶ側で判定）。Android の通知は使わない。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UpdateBanner(vm: MainViewModel) {
    val ui by vm.updates.state.collectAsStateWithLifecycle()
    val r = ui.banner ?: return
    val context = LocalContext.current
    var showNotes by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.SystemUpdate, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.update_banner, r.version),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { showNotes = true }) { Text(stringResource(R.string.update_notes)) }
                TextButton(onClick = { openInBrowser(context, vm.updates.apkUrl(r)) }) { Text(stringResource(R.string.update_download)) }
                TextButton(onClick = { vm.updates.dismiss() }) { Text(stringResource(R.string.update_later)) }
            }
        }
    }
    if (showNotes) UpdateNotesDialog(r, onDownload = { openInBrowser(context, vm.updates.apkUrl(r)) }, onPage = { openInBrowser(context, UpdateLinks.PAGE_URL) }) { showNotes = false }
}

/** ［更新内容］: notes の箇条書き（先頭 6 行）と入れ方。 */
@Composable
private fun UpdateNotesDialog(r: UpdateRelease, onDownload: () -> Unit, onPage: () -> Unit, onDismiss: () -> Unit) {
    val notes = remember(r) { Releases.summary(r.notes) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.update_dialog_title, r.version)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.update_notes), style = MaterialTheme.typography.titleSmall)
                if (notes.isEmpty()) {
                    Text(stringResource(R.string.update_dialog_notes_empty), style = MaterialTheme.typography.bodyMedium)
                } else {
                    notes.forEach { Text("・$it", style = MaterialTheme.typography.bodyMedium) }
                }
                Text(stringResource(R.string.update_dialog_install_head), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.update_dialog_install), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.update_dialog_later), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { onDownload(); onDismiss() }) { Text(stringResource(R.string.update_download)) } },
        dismissButton = {
            Row {
                TextButton(onClick = onPage) { Text(stringResource(R.string.update_open_page)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
            }
        },
    )
}

/** 設定の「更新」の節: ON/OFF・［今すぐ確認］・状態の 1 行・説明ページ。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UpdateSettingsSection(vm: MainViewModel, switchRow: @Composable (title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) -> Unit) {
    val ui by vm.updates.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    switchRow(stringResource(R.string.set_update_check), stringResource(R.string.set_update_check_desc), ui.enabled) { v -> vm.updateSettings { it.copy(updateCheck = v) } }
    Text(updateStatusLine(ui), style = MaterialTheme.typography.bodySmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { vm.updates.checkNow() }, enabled = ui.enabled && ui.status != UpdateStatus.CHECKING) { Text(stringResource(R.string.set_update_now)) }
        OutlinedButton(onClick = { openInBrowser(context, UpdateLinks.PAGE_URL) }) { Text(stringResource(R.string.set_update_page)) }
    }
}

@Composable
private fun updateStatusLine(ui: UpdateUi): String = when {
    !ui.enabled -> stringResource(R.string.update_status_off)
    ui.status == UpdateStatus.CHECKING -> stringResource(R.string.update_status_checking)
    ui.newer != null -> stringResource(R.string.update_status_available, ui.newer.version)
    ui.status == UpdateStatus.FAILED ->
        if (ui.lastSuccessMs > 0) stringResource(R.string.update_status_failed_at, Format.shortDateTime(ui.lastSuccessMs)) else stringResource(R.string.update_status_failed)
    ui.status == UpdateStatus.LATEST -> stringResource(R.string.update_status_latest, Format.shortDateTime(ui.lastSuccessMs))
    ui.lastSuccessMs > 0 -> stringResource(R.string.update_status_known, Format.shortDateTime(ui.lastSuccessMs))
    else -> stringResource(R.string.update_status_never)
}
