package jp.kjtr.drivelogger

import android.app.Application
import jp.kjtr.drivelogger.dashcam.ClipStore
import jp.kjtr.drivelogger.dashcam.DashcamEngine
import jp.kjtr.drivelogger.data.AppDatabase
import jp.kjtr.drivelogger.data.Backup
import jp.kjtr.drivelogger.data.SettingsStore
import jp.kjtr.drivelogger.data.SummaryRepository
import jp.kjtr.drivelogger.data.TripRepository
import jp.kjtr.drivelogger.map.GsiTileStore
import jp.kjtr.drivelogger.photo.PhotoRepository
import jp.kjtr.drivelogger.photo.PhotoShooter
import jp.kjtr.drivelogger.record.DriveSession
import jp.kjtr.drivelogger.record.RecorderStatus
import jp.kjtr.drivelogger.ranking.RankValues
import jp.kjtr.drivelogger.ranking.RankingCellCache
import jp.kjtr.drivelogger.ranking.RankingSpec
import jp.kjtr.drivelogger.ranking.RankingSync
import jp.kjtr.drivelogger.stats.TripFacts
import jp.kjtr.drivelogger.sensor.ImpactMonitor
import jp.kjtr.drivelogger.sensor.LocationHub
import jp.kjtr.drivelogger.service.DriveController
import jp.kjtr.drivelogger.share.SnsExporter
import jp.kjtr.drivelogger.update.UpdateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** アプリ全体で 1 つずつのもの（DB・設定・測位・記録・録画）。 */
class DriveApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val db: AppDatabase by lazy { AppDatabase.create(this) }
    val settings: SettingsStore by lazy { SettingsStore(this) }
    val trips: TripRepository by lazy { TripRepository(db) }
    val clips: ClipStore by lazy { ClipStore(this, db) }
    /** 全記録のまとめの材料（トリップごとにメモリに覚える）。 */
    val summaries: SummaryRepository by lazy { SummaryRepository(db) }
    val backup: Backup by lazy { Backup(db, trips) }
    val locationHub: LocationHub by lazy { LocationHub(this) }
    val session: DriveSession by lazy {
        DriveSession(db, appScope).also { s ->
            // 融合は車のときだけ（0.8.0〜 自転車・徒歩は使わない）
            s.fusionEnabled = { settings.settings.value.let { it.fusion && it.vehicle.imuFusion } }
            s.vehicleNow = { settings.settings.value.vehicle }
            s.smoothingEnabled = { settings.settings.value.trackSmoothing }
        }
    }
    /** 焼き込む地図の地理院タイル（地図の焼き込みが ON のときだけ通信する）。 */
    val tiles: GsiTileStore by lazy {
        GsiTileStore(this, "DriveLogger/" + BuildConfig.VERSION_NAME + " (Android; " + packageName + ")") { settings.settings.value.burnMap }
    }
    val dashcam: DashcamEngine by lazy { DashcamEngine(this, clips, appScope, tiles) }
    val impact: ImpactMonitor by lazy { ImpactMonitor(this) }
    val controller: DriveController by lazy { DriveController(this) }
    /** 写真（フォトライブラリの Pictures/ドライブロガー と photo 表）。 */
    val photos: PhotoRepository by lazy { PhotoRepository(this, db) }
    /** 写真を撮る窓口（録画中は録画を止めずに撮る）。 */
    val shooter: PhotoShooter by lazy { PhotoShooter(this) }
    /** SNS 用の書き出し（画面を離れても続く。アップロードはしない）。 */
    val sns: SnsExporter by lazy { SnsExporter(this, tiles) }
    /** 走行サービスの様子（サービスが書き、画面が読む）。 */
    val recorder = RecorderStatus()
    /** 更新の確認（GitHub の releases.json。設定で OFF なら通信しない）。 */
    val updates: UpdateManager by lazy { UpdateManager(this, appScope) { settings.settings.value.updateCheck } }

    /** オンラインランキング（0.9.0。参加は設定で ON にした人だけ・既定 OFF。docs/DESIGN.md §4.9）。 */
    val ranking: RankingSync by lazy {
        RankingSync(
            context = this,
            scope = appScope,
            appId = RankingSpec.APP_ID,
            appVersion = BuildConfig.VERSION_NAME,
            userAgent = "DriveLogger/${BuildConfig.VERSION_NAME} (Android; $packageName)",
            releaseBaseUrl = BuildConfig.RANKING_BASE,
            debug = BuildConfig.DEBUG,
            snapshot = { rankingSnapshot() },
            // 記録中（GPS・録画・駐車中の徒歩の軌跡）は送らない（終わってから。watch の Flow が流れる）
            hold = { recorder.serviceRunning.value || session.state.value?.recording == true },
        )
    }
    private val rankingCells by lazy { RankingCellCache(java.io.File(noBackupFilesDir, "ranking_cells.bin")) }

    /** ランキングに送る値（閉じたドライブすべて。まとめと同じ距離・マス）。 */
    private suspend fun rankingSnapshot(): RankValues {
        val list = trips.allFinished()
        val byId = list.associateBy { it.id }
        val cells = rankingCells.cells(list.map { it.id to SummaryRepository.cellKey(it) }) { id -> summaries.cellsOf(byId.getValue(id)) }
        val facts = list.map { t ->
            TripFacts(
                id = t.id, name = "", startedAt = t.startedAt, endedAt = t.endedAt ?: t.startedAt,
                distanceM = t.distanceM, movingMs = t.movingMs, maxSpeedMps = t.maxSpeedMps, cells = cells[t.id].orEmpty(),
            )
        }
        return RankingSpec.compute(facts, System.currentTimeMillis())
    }

    override fun onCreate() {
        super.onCreate()
        appScope.launch {
            settings.settings.map { it.updateCheck }.distinctUntilChanged().collect { updates.publish() }
        }
        // 軌跡をなめらかにする（0.8.0〜）: 表示・距離に使う。設定が変わった・0.7.0 から上書きしたら、閉じたドライブの統計（距離）を計算し直す
        TripRepository.smoothingEnabled = settings.settings.value.trackSmoothing
        appScope.launch {
            settings.settings.map { it.trackSmoothing }.distinctUntilChanged().collect { on ->
                TripRepository.smoothingEnabled = on
                val key = if (on) STATS_SMOOTHED else STATS_RECORDED
                val prefs = getSharedPreferences("state", MODE_PRIVATE)
                if (prefs.getString(K_STATS_KEY, null) != key) {
                    runCatching { trips.recomputeAllFinished() }.onFailure { android.util.Log.w("DL-App", "recompute failed: ${it.message}") }
                    prefs.edit().putString(K_STATS_KEY, key).apply()
                }
            }
        }
        // 乗り物（0.8.0〜）: 開いているドライブにも
        appScope.launch {
            settings.settings.map { it.vehicle }.distinctUntilChanged().collect { session.setVehicle(it) }
        }
        // 測位の方式（0.8.0〜。Google の融合測位は使えなければ端末の GPS に戻る）
        appScope.launch {
            settings.settings.map { it.positioning }.distinctUntilChanged().collect {
                locationHub.setUseFused(it == jp.kjtr.drivelogger.data.Positioning.GOOGLE)
            }
        }
        // オンラインランキング（0.9.0）: 閉じたドライブが変わったら（終了・削除・バックアップの読み込み・計算し直し）30 秒まとめて送る（参加しているときだけ）。
        // 記録中は送らず、終わったときにもう一度流れるよう記録の状態も一緒に見る
        ranking.watch(
            combine(
                trips.finished().map { l -> l.map { listOf(it.id, it.pointCount, it.distanceM, it.startedAt, it.updatedAt) } }.distinctUntilChanged(),
                recorder.serviceRunning,
                session.state.map { it?.recording == true }.distinctUntilChanged(),
            ) { a, b, c -> listOf(a, b, c) }.distinctUntilChanged(),
        )
        // 落ちたときに録画中のまま残った動画の行・行の無いファイルを突き合わせる
        appScope.launch {
            runCatching { clips.reconcile(dashcam.state.value.currentFile) }
            // 開いているドライブを読み込む（0.6.0〜 ドライブは記録していなくても開いたまま残る。記録中のまま落ちたログは続けるか聞く）
            runCatching { controller.restoreOpen() }.onFailure { android.util.Log.w("DL-App", "restore failed: ${it.message}") }
        }
    }

    companion object {
        /** 閉じたドライブの統計をどの点で計算したか（なめらかにした点 / 記録した点）。 */
        private const val K_STATS_KEY = "stats_points"
        private const val STATS_SMOOTHED = "smoothed-1"
        private const val STATS_RECORDED = "recorded"
    }
}
