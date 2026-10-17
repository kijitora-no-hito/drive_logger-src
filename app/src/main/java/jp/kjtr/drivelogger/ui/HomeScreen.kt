package jp.kjtr.drivelogger.ui

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.LocalParking
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import jp.kjtr.drivelogger.BuildConfig
import jp.kjtr.drivelogger.R
import jp.kjtr.drivelogger.data.TripEntity
import kotlinx.coroutines.delay

/**
 * ホーム（起動時の画面。0.5.0〜。DESIGN.md §7）。
 * - ドライブが開いていない: 「ドライブを始める」＝［GPS 記録から］［記録＋録画］の大きな 2 択（前回の選択を目立たせる）と
 *   ［記録せずに始める］（0.6.0〜 ドライブの開始＝記録の開始ではない）・更新の確認の帯・安全の注意。
 * - ドライブが開いている（駐車中・記録していないときを含む）: 「ドライブの画面へ戻る」のカード（何を記録しているか）。開始のボタン・更新の帯は出さない。
 *   （記録中・駐車中にアプリを開いたときは AppNav が走行画面へ直接移す）
 * - 最近のドライブには［このドライブを再開］（閉じたドライブを開き直して記録を足す）。
 * - 最近のドライブ・これまでの記録（回数・距離・走行時間）、一覧・動画・まとめ・写真・設定への入口。
 *   ドライブ中に動いている（10 km/h 超）ときは入口を使えない（走行画面のメニューと同じ制限）。
 */
@Composable
fun HomeScreen(
    vm: MainViewModel,
    onResumeDrive: () -> Unit,
    onTrips: () -> Unit,
    onOpenTrip: (Long) -> Unit,
    onClips: () -> Unit,
    onSummary: () -> Unit,
    onCamera: () -> Unit,
    onSettings: () -> Unit,
) {
    val context = LocalContext.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val drive by vm.session.state.collectAsStateWithLifecycle()
    val startError by vm.controller.startError.collectAsStateWithLifecycle()
    val trips by remember { vm.trips.finished() }.collectAsState(emptyList())
    val start = rememberRecordStarter(vm)
    val driving = drive != null

    // 更新の確認（ドライブ中はしない。OFF・6 時間以内なら何もしない）
    LaunchedEffect(driving) { if (!driving) vm.updates.checkOnLaunch() }

    fun guarded(action: () -> Unit): () -> Unit = {
        if (vm.restrictedNow()) Toast.makeText(context, R.string.restricted_while_moving, Toast.LENGTH_SHORT).show() else action()
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 720.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                // アプリ名・アイコン
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(Modifier.size(48.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        // 狭い画面でも名前は 1 行（版は下の行に）
                        Text(
                            stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                            maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            stringResource(R.string.home_tagline) + "　v" + BuildConfig.VERSION_NAME,
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                val d = drive
                if (d != null) {
                    DrivingCard(d, onClick = onResumeDrive)
                } else {
                    // 更新の確認の帯（ドライブ中は出さない）
                    UpdateBanner(vm)
                    Text(stringResource(R.string.home_start_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    // 0.6.0〜 ドライブの開始＝記録の開始ではない: GPS 記録から / 記録＋録画 / 記録せずに開く
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StartCard(
                            icon = Icons.Filled.Map, title = stringResource(R.string.start_gps_only), desc = stringResource(R.string.start_gps_only_sub),
                            last = !settings.defaultWithVideo, color = colorResource(R.color.brand_primary),
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        ) { start(RecordAction.OPEN_DRIVE_GPS) }
                        StartCard(
                            icon = Icons.Filled.Videocam, title = stringResource(R.string.start_with_video),
                            // 録画の方式は設定の既定（0.7.0〜）
                            desc = stringResource(
                                if (settings.recordMode == jp.kjtr.drivelogger.dashcam.RecordMode.LONG) R.string.start_with_video_sub_long else R.string.start_with_video_sub,
                            ),
                            last = settings.defaultWithVideo, color = colorResource(R.color.recording),
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        ) { start(RecordAction.OPEN_DRIVE_VIDEO) }
                    }
                    OutlinedCard(onClick = { start(RecordAction.OPEN_DRIVE) }, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.DirectionsCar, contentDescription = null, tint = colorResource(R.color.brand_primary))
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.start_no_record), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                Text(stringResource(R.string.start_no_record_sub), style = MaterialTheme.typography.bodySmall)
                            }
                            Icon(Icons.Filled.ChevronRight, contentDescription = null)
                        }
                    }
                    Text(stringResource(R.string.safety_short), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                // 最近のドライブ（閉じたもの。［このドライブを再開］で記録を追加できる）
                val recent = trips.firstOrNull()
                if (recent != null) {
                    RecentCard(recent, onClick = guarded { onOpenTrip(recent.id) }, reopen = { ReopenButton(vm, recent, guard = { vm.restrictedNow() }) })
                    TotalsCard(trips, onClick = guarded(onSummary))
                } else if (!driving) {
                    Text(stringResource(R.string.home_no_trips), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                // 入口
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NavTile(Icons.AutoMirrored.Filled.List, stringResource(R.string.home_nav_trips), guarded(onTrips), Modifier.weight(1f))
                    NavTile(Icons.Filled.VideoLibrary, stringResource(R.string.clips_title), guarded(onClips), Modifier.weight(1f))
                    NavTile(Icons.Filled.BarChart, stringResource(R.string.summary_title), guarded(onSummary), Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // 写真: ドライブ中は走行画面の［写真］から撮る（ドライブに紐づく）。ここはドライブしていないときだけ
                    if (!driving) NavTile(Icons.Filled.PhotoCamera, stringResource(R.string.home_nav_camera), onCamera, Modifier.weight(1f))
                    NavTile(Icons.Filled.Settings, stringResource(R.string.settings_title), guarded(onSettings), Modifier.weight(1f))
                }
            }
        }
    }

    startError?.let { f -> RecordStartErrorDialog(f, onDismiss = { vm.controller.dismissStartError() }) }
}

/** アプリのアイコン（アダプティブの背景＋前景。前景のセーフゾーンが収まるよう 1.5 倍で描いて切り抜く）。 */
@Composable
private fun AppIcon(modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
        Image(painterResource(R.drawable.ic_launcher_background), contentDescription = null, modifier = Modifier.requiredSize(72.dp))
        Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.requiredSize(72.dp))
    }
}

/** ドライブ中（駐車中を含む）。タップで走行中の画面へ。 */
@Composable
private fun DrivingCard(d: jp.kjtr.drivelogger.record.DriveState, onClick: () -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000L - now % 1_000L)
        }
    }
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = colorResource(
                when {
                    d.parked -> R.color.park
                    d.recording -> R.color.recording
                    else -> R.color.brand_primary
                },
            ),
            contentColor = Color.White,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (d.parked) Icons.Filled.LocalParking else Icons.Filled.DirectionsCar, contentDescription = null, modifier = Modifier.size(40.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(if (d.parked) R.string.home_parked else R.string.home_driving), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                // 何を記録しているか（0.6.0〜 ドライブが開いていても記録していないことがある）
                Text(
                    // 記録中は走行画面のボタンと同じ「●」（0.7.0〜）
                    (if (d.recording) "● " else "") + stringResource(
                        when {
                            d.videoLogging -> R.string.home_rec_video
                            d.gpsLogging -> R.string.home_rec_gps
                            d.parked -> R.string.home_rec_parked
                            else -> R.string.home_rec_none
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold,
                )
                Text(
                    stringResource(R.string.home_driving_stats, Format.km(d.distanceM), Format.duration(d.movingMs), Format.duration(now - d.startedAt)),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(stringResource(R.string.home_back_to_drive), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
            }
            Icon(Icons.Filled.ChevronRight, contentDescription = null)
        }
    }
}

/** 開始の大きなボタン（前回の選択を塗りつぶしにする）。 */
@Composable
private fun StartCard(icon: ImageVector, title: String, desc: String, last: Boolean, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = modifier.heightIn(min = 132.dp),
        shape = RoundedCornerShape(16.dp),
        colors = if (last) CardDefaults.cardColors(containerColor = color, contentColor = Color.White)
        else CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(40.dp), tint = if (last) Color.White else color)
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Text(desc, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
            if (last) {
                Text(
                    stringResource(R.string.home_last_choice), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun RecentCard(t: TripEntity, onClick: () -> Unit, reopen: @Composable () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.home_recent), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                if (t.recordedVideo) Icon(Icons.Filled.Videocam, contentDescription = stringResource(R.string.trip_has_video), tint = colorResource(R.color.recording), modifier = Modifier.size(18.dp))
            }
            Text(t.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                Format.dateTime(t.startedAt) + (t.endedAt?.let { " – " + Format.time(it) } ?: "") + "　" + Format.km(t.distanceM) + "・" +
                    stringResource(R.string.trip_running, Format.duration(t.movingMs)),
                style = MaterialTheme.typography.bodySmall,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { reopen() }
        }
    }
}

@Composable
private fun TotalsCard(trips: List<TripEntity>, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.home_totals), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(
                    stringResource(R.string.home_totals_value, trips.size, Format.km(trips.sumOf { it.distanceM }), Format.duration(trips.sumOf { it.movingMs })),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(stringResource(R.string.home_totals_hint), style = MaterialTheme.typography.bodySmall)
            }
            Icon(Icons.Filled.ChevronRight, contentDescription = null)
        }
    }
}

@Composable
private fun NavTile(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier) {
    Card(onClick = onClick, modifier = modifier.heightIn(min = 76.dp)) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
        ) {
            Icon(icon, contentDescription = null)
            Text(label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center, maxLines = 2)
        }
    }
}
