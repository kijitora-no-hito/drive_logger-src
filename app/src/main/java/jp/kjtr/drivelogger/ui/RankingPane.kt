package jp.kjtr.drivelogger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jp.kjtr.drivelogger.ranking.BoardReply
import jp.kjtr.drivelogger.ranking.BoardRow
import jp.kjtr.drivelogger.ranking.BoardView
import jp.kjtr.drivelogger.ranking.RankBoard
import jp.kjtr.drivelogger.ranking.RankingRules
import jp.kjtr.drivelogger.ranking.RankingSync

// ランキングの画面の中身（API.md §6）。基準点めぐり・神社めぐりのタブと同じ形（自転車ルートロガー・ドライブロガーは RankingScreen が包む）。

@Composable
fun RankingPane(sync: RankingSync, boards: List<RankBoard>, openSettings: () -> Unit) {
    val state by sync.state.collectAsState()
    if (!state.enabled) {
        NotJoined(openSettings)
        return
    }
    var boardId by rememberSaveable { mutableStateOf(boards.first().id) }
    var period by rememberSaveable { mutableStateOf("all") }
    var view by remember { mutableStateOf<BoardView?>(null) }
    var loading by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    val board = boards.firstOrNull { it.id == boardId } ?: boards.first()

    LaunchedEffect(boardId, period, reload, state.enabled) {
        // 前に読んだ内容を先に出してから読みに行く
        view = sync.store.cached(boardId, period)?.let { BoardView(it.first, it.second, null) }
        loading = true
        view = sync.loadBoard(boardId, period)
        loading = false
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (b in boards) FilterChip(selected = b.id == boardId, onClick = { boardId = b.id }, label = { Text(b.label) })
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(selected = period == "all", onClick = { period = "all" }, label = { Text("総合") })
            FilterChip(
                selected = period == "month", onClick = { period = "month" },
                label = { Text("今月（${RankingRules.monthLabel(RankingRules.monthOf(System.currentTimeMillis()))}）") },
            )
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                if (loading) CircularProgressIndicator(Modifier.padding(12.dp).size(20.dp), strokeWidth = 2.dp)
                else TextButton(onClick = { reload++ }) { Text("更新") }
            }
        }
        val v = view
        val reply = v?.reply
        when {
            reply == null && (loading || v == null) -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            reply == null -> Message(v?.error ?: "つながりません", "通信できるところで［更新］を押してください。")
            else -> BoardContent(reply, board, state.name, v.fetchedAt, v.error, state.pending)
        }
    }
}

@Composable
private fun NotJoined(openSettings: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("オンラインランキング", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            "ランキングは参加している人どうしで見るものです。設定で参加すると見られます（参加は自由・既定はオフ）。",
            fontSize = 14.sp,
        )
        Button(onClick = openSettings) { Text("設定を開く") }
    }
}

@Composable
private fun Message(title: String, body: String) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, fontWeight = FontWeight.Bold)
        Text(body, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun BoardContent(r: BoardReply, board: RankBoard, myName: String, fetchedAt: Long, error: String?, pending: Boolean) {
    val now = System.currentTimeMillis()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
        if (error != null) {
            item {
                Text(
                    "$error。${RankingRules.agoText(fetchedAt, now)}に読んだ内容です。",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 6.dp),
                )
            }
        }
        item {
            Card(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val me = r.me
                    if (me != null) {
                        Text("あなたは ${me.rank} 位（${r.ranked} 人中）", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Text("${board.label}: ${board.format(me.value)}", fontSize = 14.sp)
                    } else {
                        Text("まだ順位がありません", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text(
                            if (r.period == "month") "今月の${board.label}が 0 です（走ると順位に入ります）"
                            else "${board.label}が 0 です（走ると順位に入ります）",
                            fontSize = 13.sp,
                        )
                    }
                    Text(
                        "名前: ${me?.name?.takeIf { it.isNotEmpty() } ?: myName}" + if (pending) "（送信待ちの記録があります）" else "",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (!r.unlocked) {
            item {
                Text(
                    "参加者が ${r.minPlayers} 人になると、上位の一覧が見られます（いま ${r.players} 人）",
                    fontSize = 14.sp, modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        } else {
            item {
                Text(
                    "上位 ${r.top.size} 人（参加者 ${r.players} 人・順位のある人 ${r.ranked} 人）",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            items(r.top, key = { it.rank }) { row -> RankRow(row, board) }
            val me = r.me
            if (me != null && r.top.none { it.me }) {
                item {
                    Column {
                        Text("…", Modifier.padding(start = 12.dp))
                        RankRow(BoardRow(me.rank, me.name.ifEmpty { myName }, me.value, true), board)
                    }
                }
            }
        }
        item {
            Text(
                "${RankingRules.agoText(fetchedAt, now)}に読み込み・" + if (r.period == "month") "今月は日本時間で数えます" else "総合は累計",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

@Composable
private fun RankRow(row: BoardRow, board: RankBoard) {
    val bg = if (row.me) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    Column {
        Row(
            Modifier.fillMaxWidth().background(bg, RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${row.rank}", Modifier.width(44.dp), fontWeight = FontWeight.Bold,
                color = if (row.rank <= 3) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                row.name + if (row.me) "（あなた）" else "", Modifier.weight(1f),
                fontWeight = if (row.me) FontWeight.Bold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(board.format(row.value), fontWeight = if (row.me) FontWeight.Bold else FontWeight.Normal)
        }
        HorizontalDivider()
    }
}
