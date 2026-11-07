package jp.kjtr.drivelogger.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jp.kjtr.drivelogger.ranking.JoinResult
import jp.kjtr.drivelogger.ranking.RankingRules
import jp.kjtr.drivelogger.ranking.RankingSync
import kotlinx.coroutines.launch

// 設定の「オンラインランキング」（API.md §1・§6）。基準点めぐり・神社めぐりの写し（自転車ルートロガー・ドライブロガーで同じ形）。
// 違いは見出しの形（設定画面の節に合わせた RankingSection）・説明の「走行距離」・記録中の HELD。

@Composable
fun RankingSettingsSection(
    sync: RankingSync,
    sentValues: String,
    notSent: String,
    debug: Boolean,
    onMessage: (String) -> Unit,
) {
    val state by sync.state.collectAsState()
    var joinOpen by remember { mutableStateOf(false) }
    var renameOpen by remember { mutableStateOf(false) }
    var leaveOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    RankingSection("オンラインランキング") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("オンラインランキングに参加する", fontSize = 14.sp)
                Text(
                    "参加した人どうしで、走行距離などの順位を見られます。送るのは名前・ランダムな ID・数値だけです（位置・経路は送りません）。",
                    fontSize = 12.sp,
                )
            }
            Switch(checked = state.enabled, onCheckedChange = { on -> if (on) joinOpen = true else leaveOpen = true })
        }
        state.notice?.let {
            Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { sync.dismissNotice() }) { Text("閉じる", fontSize = 12.sp) }
        }
        if (state.enabled) {
            Text("名前: ${state.name}", fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(
                when {
                    state.pending -> "送信待ちの記録があります（つながったときに送ります）"
                    state.lastSentAt > 0 -> "最後に送った: ${RankingRules.agoText(state.lastSentAt, System.currentTimeMillis())}"
                    else -> "まだ送っていません"
                },
                fontSize = 12.sp,
            )
            OutlinedButton(onClick = { renameOpen = true }) { Text("名前を変える") }
            Text("参加をやめる（オフにする）と、サーバのあなたの記録（名前・順位）は消えます。", fontSize = 12.sp)
        }
        if (state.pendingDeletes > 0) {
            Text("参加をやめた記録の削除を、次に通信できたときにサーバへ送ります。", fontSize = 12.sp)
        }
        if (debug) {
            DebugServer(sync, state.baseUrl)
        }
    }

    if (joinOpen) {
        NameDialog(
            title = "オンラインランキングに参加",
            initial = "",
            confirm = "参加する",
            explain = { JoinExplain(sentValues, notSent) },
            onDismiss = { joinOpen = false },
            submit = { name ->
                when (sync.join(name)) {
                    JoinResult.OK -> { joinOpen = false; onMessage("ランキングに参加しました"); null }
                    JoinResult.OFFLINE -> { joinOpen = false; onMessage("参加にしました。いまはつながらないので、つながったときに送ります"); null }
                    JoinResult.BAD_NAME -> "この名前は使えません。別の名前にしてください"
                    JoinResult.BANNED -> { joinOpen = false; null }
                    JoinResult.HELD -> { joinOpen = false; onMessage("参加にしました。記録が終わったら送ります"); null }
                }
            },
        )
    }
    if (renameOpen) {
        NameDialog(
            title = "名前を変える",
            initial = state.name,
            confirm = "変える",
            explain = { Text("ランキングでほかの参加者に見える名前です（1〜${RankingRules.NAME_MAX} 文字）。", fontSize = 13.sp) },
            onDismiss = { renameOpen = false },
            submit = { name ->
                when (sync.rename(name)) {
                    JoinResult.OK -> { renameOpen = false; onMessage("名前を変えました"); null }
                    JoinResult.OFFLINE -> { renameOpen = false; onMessage("いまはつながらないので、つながったときに送ります"); null }
                    JoinResult.BAD_NAME -> "この名前は使えません。別の名前にしてください"
                    JoinResult.BANNED -> { renameOpen = false; null }
                    JoinResult.HELD -> { renameOpen = false; onMessage("名前を変えました。記録が終わったら送ります"); null }
                }
            },
        )
    }
    if (leaveOpen) {
        AlertDialog(
            onDismissRequest = { leaveOpen = false },
            confirmButton = {
                TextButton(onClick = {
                    leaveOpen = false
                    scope.launch {
                        sync.leave()
                        onMessage(
                            if (sync.state.value.pendingDeletes > 0) "参加をやめました。サーバの記録は、次に通信できたときに消します"
                            else "参加をやめました。サーバのあなたの記録を消しました"
                        )
                    }
                }) { Text("やめる") }
            },
            dismissButton = { TextButton(onClick = { leaveOpen = false }) { Text("続ける") } },
            title = { Text("ランキングへの参加をやめますか") },
            text = { Text("サーバにあるあなたの名前と記録を消します。もう一度参加すると、新しい参加者として順位が付きます（端末の中の記録はそのままです）。") },
        )
    }
}

@Composable
private fun JoinExplain(sentValues: String, notSent: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("参加すると、ランキングのサーバ（taisen-game.com）に次のものを送ります。", fontSize = 13.sp)
        Text("送るもの", fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Text("・名前（このあと決めます。ほかの参加者に見えます）", fontSize = 13.sp)
        Text("・ランダムな ID（この端末で作る番号。あなたが誰かは分かりません）", fontSize = 13.sp)
        Text("・数値: $sentValues", fontSize = 13.sp)
        Text("・アプリの版と端末の種類（Android）", fontSize = 13.sp)
        Text("送らないもの", fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Text("・$notSent", fontSize = 13.sp)
        Text(
            "参加をやめる（オフにする）と、サーバのあなたの記録は消えます。上位の一覧は参加者が ${RankingRules.MIN_PLAYERS} 人以上になると見られます" +
                "（それまでは自分の順位だけ）。",
            fontSize = 13.sp,
        )
    }
}

/** 名前を入れる窓。[submit] はエラーの文（null なら閉じてよい）。 */
@Composable
private fun NameDialog(
    title: String,
    initial: String,
    confirm: String,
    explain: @Composable () -> Unit,
    onDismiss: () -> Unit,
    submit: suspend (String) -> String?,
) {
    var name by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val valid = RankingRules.cleanName(name) != null
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        confirmButton = {
            TextButton(
                enabled = valid && !busy,
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        error = submit(name)
                        busy = false
                    }
                },
            ) { Text(confirm) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("やめる") } },
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                explain()
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; error = null },
                    label = { Text("名前（1〜${RankingRules.NAME_MAX} 文字）") },
                    singleLine = true,
                    isError = error != null || (name.isNotEmpty() && !valid),
                    supportingText = {
                        Text(error ?: "${RankingRules.nameLength(name)} / ${RankingRules.NAME_MAX}" + if (name.isNotEmpty() && !valid) "（使えない文字か長すぎます）" else "")
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (busy) Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.padding(end = 8.dp), strokeWidth = 2.dp)
                    Text("送っています…", fontSize = 13.sp)
                }
            }
        },
    )
}

/** debug ビルドだけ: 接続先を変える（エミュレータから PC のサーバは http://10.0.2.2:<port>/rank/api/v1）。 */
@Composable
private fun DebugServer(sync: RankingSync, current: String) {
    var url by remember(current) { mutableStateOf(current) }
    Text("開発用（debug ビルドだけ）: 接続先", fontSize = 12.sp, fontWeight = FontWeight.Bold)
    OutlinedTextField(
        value = url, onValueChange = { url = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        textStyle = MaterialTheme.typography.bodySmall,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { sync.setDebugBaseUrl(url) }) { Text("保存", fontSize = 12.sp) }
        OutlinedButton(onClick = { url = "http://10.0.2.2:8792/rank/api/v1" }) { Text("10.0.2.2", fontSize = 12.sp) }
        OutlinedButton(onClick = { sync.setDebugBaseUrl(""); url = sync.baseUrl }) { Text("本番", fontSize = 12.sp) }
    }
    if (sync.state.collectAsState().value.enabled) {
        TextButton(onClick = { sync.sendNowForDebug() }) { Text("今すぐ送る", fontSize = 12.sp) }
    }
}

/** 設定画面の節（見出し + 中身）。設定画面のほかの節の見出しと同じ形。 */
@Composable
private fun RankingSection(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        content()
    }
}
