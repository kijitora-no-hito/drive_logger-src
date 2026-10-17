package jp.kjtr.drivelogger.data

import android.content.Context
import android.content.SharedPreferences
import jp.kjtr.drivelogger.dashcam.BurnInPosition
import jp.kjtr.drivelogger.dashcam.BurnInSize
import jp.kjtr.drivelogger.dashcam.ImpactSensitivity
import jp.kjtr.drivelogger.dashcam.RecordMode
import jp.kjtr.drivelogger.dashcam.VideoQuality
import jp.kjtr.drivelogger.gauge.GaugeMath
import jp.kjtr.drivelogger.gauge.MeterStyle
import jp.kjtr.drivelogger.map.GsiLayer
import jp.kjtr.drivelogger.map.MapTrack
import jp.kjtr.drivelogger.record.Vehicle
import jp.kjtr.drivelogger.share.SnsPreset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class MapKind { NORMAL, SATELLITE, HYBRID, TERRAIN }

/**
 * 走行を始めるときの既定（基本は GPS の記録だけ。録画はオプション）。
 * [LAST] = 前回選んだ方（［記録だけ］［記録＋録画］の大きな 2 択の初期値）。
 */
enum class StartChoice { LAST, GPS_ONLY, WITH_VIDEO }

/**
 * 表示（日時・速度・地図など）の入れ方（0.4.0〜）。[LATER] = 録画は表示なしのきれいな映像で保存し、書き出し・再生のときに GPS 記録から描いて重ねる（既定）。
 * [BURN] = 録画のときに焼き込む（0.3.0 までの方式。SNS 用の書き出しで隠すときは覆う）。
 */
enum class OverlayMode { LATER, BURN }

/** 録画の向き（0.4.0〜）。[LANDSCAPE] = 録画 ON の間は画面を横向きに固定し、横長（16:9）で録る（既定）。[DEVICE] = 端末の向きに従う。 */
enum class VideoOrientation { LANDSCAPE, DEVICE }

/**
 * 測位の方式（0.8.0〜）。[DEVICE] = 端末の GPS（LocationManager の GPS・ネットワーク。既定）。
 * [GOOGLE] = Google の融合測位（Play 開発者サービスの FusedLocationProviderClient、高精度。使えない端末は端末の GPS に戻す。自前の融合はかけない）。
 */
enum class Positioning { DEVICE, GOOGLE }

data class Settings(
    val mapKind: MapKind = MapKind.NORMAL,
    val startChoice: StartChoice = StartChoice.LAST,
    /** 前回の開始で録画も選んだか（[StartChoice.LAST] のとき使う。既定は記録だけ）。 */
    val lastStartWithVideo: Boolean = false,
    // ---- 録画 ----
    /** 録画の方式の既定（0.7.0〜。ループ録画＝ドラレコ / 長時間録画＝消さない。既定 ループ）。ドライブの画面の録画ボタンの横でも切り替えられる。 */
    val recordMode: RecordMode = RecordMode.LOOP,
    /** 長時間録画の 1 ファイルの長さ [分]（10 / 15 / 30。途中で落ちたときに失う量を抑えるため分けて保存する）。 */
    val longSegmentMinutes: Int = 10,
    /** 1 ファイルの長さ [分]（1 / 3 / 5）。 */
    val segmentMinutes: Int = 3,
    /** 動画に使う容量の上限 [MB]。 */
    val storageLimitMb: Int = 8 * 1024,
    /** 保護した動画の上限 [MB]。 */
    val protectedLimitMb: Int = 2 * 1024,
    val quality: VideoQuality = VideoQuality.FHD,
    /** 30 / 60。 */
    val fps: Int = 30,
    /** 音声も録る（既定 OFF。RECORD_AUDIO が要る）。 */
    val audio: Boolean = false,
    /** 録画の向き（既定 横）。 */
    val videoOrientation: VideoOrientation = VideoOrientation.LANDSCAPE,
    // ---- 表示（焼き込み / あとから重ねる） ----
    /** 表示の入れ方（既定 あとから重ねる）。 */
    val overlayMode: OverlayMode = OverlayMode.LATER,
    /** 表示を入れる（あとから重ねる方式では、書き出し・再生の既定。焼き込み方式では録画に焼き込む）。 */
    val burnIn: Boolean = true,
    /** 書き出しで速度を隠すとき「---」を出す（既定 OFF＝速度の表示そのものを描かない。あとから重ねる方式だけ）。 */
    val hideSpeedDashes: Boolean = false,
    /** ギャラリーに書き出すとき表示を重ねる（あとから重ねる方式の動画。既定 ON）。 */
    val galleryOverlay: Boolean = true,
    val burnDate: Boolean = true,
    val burnSpeed: Boolean = true,
    val burnLatLon: Boolean = true,
    val burnHeading: Boolean = true,
    val burnG: Boolean = false,
    val burnAppName: Boolean = true,
    val burnPosition: BurnInPosition = BurnInPosition.BOTTOM_LEFT,
    val burnSize: BurnInSize = BurnInSize.MEDIUM,
    /** 焼き込みを文字で出すかメーターで出すか（既定 文字）。 */
    val burnStyle: MeterStyle = MeterStyle.TEXT,
    // ---- 地図の焼き込み（地理院タイル。既定 OFF。ON のときだけ国土地理院のサーバーからタイルを取る） ----
    val burnMap: Boolean = false,
    val burnMapLayer: GsiLayer = GsiLayer.STD,
    /** 13〜17。 */
    val burnMapZoom: Int = 15,
    /** true = 進行方向が上、false = 北が上。 */
    val burnMapHeadingUp: Boolean = true,
    val burnMapSize: BurnInSize = BurnInSize.MEDIUM,
    val burnMapPosition: BurnInPosition = BurnInPosition.BOTTOM_RIGHT,
    val burnMapTrack: MapTrack = MapTrack.RECENT_5KM,
    // ---- メーター ----
    /** 走行画面の HUD を文字で出すかメーターで出すか（既定 文字）。 */
    val hudStyle: MeterStyle = MeterStyle.TEXT,
    /** 走行画面のメーターにコンパス・G メーターも出す。 */
    val hudCompass: Boolean = true,
    val hudG: Boolean = true,
    /** 速度計の上限 [km/h]（120 / 180 / 240。画面・焼き込み共通）。 */
    val gaugeMaxKmh: Int = 180,
    // ---- 衝撃・記録 ----
    val impactSensitivity: ImpactSensitivity = ImpactSensitivity.MEDIUM,
    /** 信号待ちも走行時間に入れるので既定 OFF。 */
    val autoPause: Boolean = false,
    // ---- 測位（0.3.0〜） ----
    /** 測位の方式（0.8.0〜。既定 端末の GPS）。 */
    val positioning: Positioning = Positioning.DEVICE,
    /** GPS を加速度・ジャイロと融合して位置・速度を推定する（既定 ON）。元の GPS の値も残す。 */
    val fusion: Boolean = true,
    /** 停車中は測位を止める（既定 ON。停車を始めた位置のまま記録する）。 */
    val stopPause: Boolean = true,
    /** GPX に書き出す位置（0.8.0〜。既定 なめらかにした位置。0.7.0 までの「元の GPS の値」の設定は RAW に引き継ぐ）。 */
    val gpxSource: GpxSource = GpxSource.SMOOTHED,
    /** 軌跡をなめらかにする（0.8.0〜。既定 ON。表示と距離に使う。記録はそのまま）。 */
    val trackSmoothing: Boolean = true,
    /** 乗り物（0.8.0〜。既定 車。新しいドライブと開いているドライブに使う）。 */
    val vehicle: Vehicle = Vehicle.CAR,
    // ---- SNS 用の書き出し（0.3.0〜。前回の選択を覚える） ----
    val snsPreset: SnsPreset = SnsPreset.X,
    val snsCropVertical: Boolean = true,
    val snsHideSpeed: Boolean = true,
    /** 40〜120 km/h（5 km/h 刻み）。 */
    val snsThresholdKmh: Int = 60,
    val snsHideLatLon: Boolean = true,
    val snsHideMap: Boolean = false,
    /** 共有の前の注意を出さない。 */
    val snsShareWarnHidden: Boolean = false,
    // ---- 写真（0.4.0〜） ----
    /** 撮った写真の EXIF に位置（GPS）を書き込む（OFF でもアプリの中では位置を使う）。 */
    val photoExifLocation: Boolean = true,
    /** 録画中に撮った写真にも焼き込み（日時・速度など）を入れる（既定 OFF＝素の写真）。 */
    val photoBurnIn: Boolean = false,
    /** GPX の書き出しに写真の位置（wpt）を入れる。 */
    val gpxPhotos: Boolean = true,
    /** SNS 用の画像に最高速度を出さない。 */
    val cardHideMax: Boolean = false,
    /** SNS 用の画像で、しきい値（[snsThresholdKmh]）を超えた速度の項目を出さない。 */
    val cardHideOverThreshold: Boolean = true,
    // ---- 駐車・寄り道（0.4.0〜） ----
    /** 駐車中も GPS を低頻度で測って徒歩の軌跡・距離を残す（既定 OFF＝駐車中は GPS を止める）。 */
    val parkWalkTrack: Boolean = false,
    // ---- 地図の大きさ（0.4.0〜。自転車ルートロガーと同じ。境目のつまみで変える） ----
    /** ドライブの詳細の地図の割合（縦向きは高さ・横向きは幅）。 */
    val tripMapRatio: Float = 0.6f,
    /** まとめの地図の割合。 */
    val summaryMapRatio: Float = 0.6f,
    // ---- 画面・その他 ----
    val keepScreenOn: Boolean = true,
    /** 走行中（10 km/h 超）は設定・一覧へ移れない。 */
    val restrictWhileMoving: Boolean = true,
    val updateCheck: Boolean = true,
    /** 初回の安全上の注意に同意した。 */
    val safetyAccepted: Boolean = false,
    /** 初めて録画を始めるときの発熱・電池の注意を出した。 */
    val heatNoticeShown: Boolean = false,
) {
    /** ［記録＋録画］を初めから選んでおくか。 */
    val defaultWithVideo: Boolean get() = when (startChoice) {
        StartChoice.LAST -> lastStartWithVideo
        StartChoice.GPS_ONLY -> false
        StartChoice.WITH_VIDEO -> true
    }
}

/** 設定の SharedPreferences 永続化。 */
class SettingsStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<Settings> = _settings

    fun update(f: (Settings) -> Settings) {
        val s = f(_settings.value)
        _settings.value = s
        prefs.edit()
            .putString(K_MAP, s.mapKind.name)
            .putString(K_START, s.startChoice.name)
            .putBoolean(K_LAST_VIDEO, s.lastStartWithVideo)
            .putInt(K_SEGMENT, s.segmentMinutes)
            .putString(K_RECORD_MODE, s.recordMode.name)
            .putInt(K_LONG_SEGMENT, s.longSegmentMinutes)
            .putInt(K_LIMIT, s.storageLimitMb)
            .putInt(K_PROTECTED_LIMIT, s.protectedLimitMb)
            .putString(K_QUALITY, s.quality.name)
            .putInt(K_FPS, s.fps)
            .putBoolean(K_AUDIO, s.audio)
            .putBoolean(K_BURN, s.burnIn)
            .putString(K_VIDEO_ORIENT, s.videoOrientation.name)
            .putString(K_OVERLAY_MODE, s.overlayMode.name)
            .putBoolean(K_HIDE_DASHES, s.hideSpeedDashes)
            .putBoolean(K_GALLERY_OVERLAY, s.galleryOverlay)
            .putBoolean(K_BURN_DATE, s.burnDate)
            .putBoolean(K_BURN_SPEED, s.burnSpeed)
            .putBoolean(K_BURN_LATLON, s.burnLatLon)
            .putBoolean(K_BURN_HEADING, s.burnHeading)
            .putBoolean(K_BURN_G, s.burnG)
            .putBoolean(K_BURN_APP, s.burnAppName)
            .putString(K_BURN_POS, s.burnPosition.name)
            .putString(K_BURN_SIZE, s.burnSize.name)
            .putString(K_BURN_STYLE, s.burnStyle.name)
            .putBoolean(K_BURN_MAP, s.burnMap)
            .putString(K_BURN_MAP_LAYER, s.burnMapLayer.name)
            .putInt(K_BURN_MAP_ZOOM, s.burnMapZoom)
            .putBoolean(K_BURN_MAP_HEADING_UP, s.burnMapHeadingUp)
            .putString(K_BURN_MAP_SIZE, s.burnMapSize.name)
            .putString(K_BURN_MAP_POS, s.burnMapPosition.name)
            .putString(K_BURN_MAP_TRACK, s.burnMapTrack.name)
            .putString(K_HUD_STYLE, s.hudStyle.name)
            .putBoolean(K_HUD_COMPASS, s.hudCompass)
            .putBoolean(K_HUD_G, s.hudG)
            .putInt(K_GAUGE_MAX, s.gaugeMaxKmh)
            .putString(K_IMPACT, s.impactSensitivity.name)
            .putBoolean(K_AUTO_PAUSE, s.autoPause)
            .putBoolean(K_FUSION, s.fusion)
            .putString(K_POSITIONING, s.positioning.name)
            .putBoolean(K_STOP_PAUSE, s.stopPause)
            .putString(K_GPX_SOURCE, s.gpxSource.name)
            .putBoolean(K_TRACK_SMOOTHING, s.trackSmoothing)
            .putString(K_VEHICLE, s.vehicle.name)
            .putString(K_SNS_PRESET, s.snsPreset.name)
            .putBoolean(K_SNS_CROP, s.snsCropVertical)
            .putBoolean(K_SNS_HIDE_SPEED, s.snsHideSpeed)
            .putInt(K_SNS_THRESHOLD, s.snsThresholdKmh)
            .putBoolean(K_SNS_HIDE_LATLON, s.snsHideLatLon)
            .putBoolean(K_SNS_HIDE_MAP, s.snsHideMap)
            .putBoolean(K_SNS_WARN_HIDDEN, s.snsShareWarnHidden)
            .putBoolean(K_PHOTO_EXIF_LOC, s.photoExifLocation)
            .putBoolean(K_PHOTO_BURN, s.photoBurnIn)
            .putBoolean(K_GPX_PHOTOS, s.gpxPhotos)
            .putBoolean(K_CARD_HIDE_MAX, s.cardHideMax)
            .putBoolean(K_CARD_HIDE_OVER, s.cardHideOverThreshold)
            .putBoolean(K_PARK_WALK, s.parkWalkTrack)
            .putFloat(K_TRIP_MAP_RATIO, s.tripMapRatio)
            .putFloat(K_SUMMARY_MAP_RATIO, s.summaryMapRatio)
            .putBoolean(K_SCREEN_ON, s.keepScreenOn)
            .putBoolean(K_RESTRICT, s.restrictWhileMoving)
            .putBoolean(K_UPDATE_CHECK, s.updateCheck)
            .putBoolean(K_SAFETY, s.safetyAccepted)
            .putBoolean(K_HEAT, s.heatNoticeShown)
            .apply()
    }

    private fun load(): Settings {
        val d = Settings()
        return Settings(
            mapKind = enumOr(prefs.getString(K_MAP, null), d.mapKind),
            startChoice = enumOr(prefs.getString(K_START, null), d.startChoice),
            lastStartWithVideo = prefs.getBoolean(K_LAST_VIDEO, d.lastStartWithVideo),
            segmentMinutes = prefs.getInt(K_SEGMENT, d.segmentMinutes).takeIf { it in SEGMENT_CHOICES } ?: d.segmentMinutes,
            recordMode = enumOr(prefs.getString(K_RECORD_MODE, null), d.recordMode),
            longSegmentMinutes = prefs.getInt(K_LONG_SEGMENT, d.longSegmentMinutes)
                .takeIf { it in LONG_SEGMENT_CHOICES || it in DEBUG_LONG_SEGMENT_CHOICES } ?: d.longSegmentMinutes,
            storageLimitMb = prefs.getInt(K_LIMIT, d.storageLimitMb).takeIf { it > 0 } ?: d.storageLimitMb,
            protectedLimitMb = prefs.getInt(K_PROTECTED_LIMIT, d.protectedLimitMb).takeIf { it > 0 } ?: d.protectedLimitMb,
            quality = enumOr(prefs.getString(K_QUALITY, null), d.quality),
            fps = prefs.getInt(K_FPS, d.fps).takeIf { it == 30 || it == 60 } ?: d.fps,
            audio = prefs.getBoolean(K_AUDIO, d.audio),
            burnIn = prefs.getBoolean(K_BURN, d.burnIn),
            videoOrientation = enumOr(prefs.getString(K_VIDEO_ORIENT, null), d.videoOrientation),
            // 0.3.0 から上書きした人も、既定は「あとから重ねる」（元の映像を残す方が後から選べる）
            overlayMode = enumOr(prefs.getString(K_OVERLAY_MODE, null), d.overlayMode),
            hideSpeedDashes = prefs.getBoolean(K_HIDE_DASHES, d.hideSpeedDashes),
            galleryOverlay = prefs.getBoolean(K_GALLERY_OVERLAY, d.galleryOverlay),
            burnDate = prefs.getBoolean(K_BURN_DATE, d.burnDate),
            burnSpeed = prefs.getBoolean(K_BURN_SPEED, d.burnSpeed),
            burnLatLon = prefs.getBoolean(K_BURN_LATLON, d.burnLatLon),
            burnHeading = prefs.getBoolean(K_BURN_HEADING, d.burnHeading),
            burnG = prefs.getBoolean(K_BURN_G, d.burnG),
            burnAppName = prefs.getBoolean(K_BURN_APP, d.burnAppName),
            burnPosition = enumOr(prefs.getString(K_BURN_POS, null), d.burnPosition),
            burnSize = enumOr(prefs.getString(K_BURN_SIZE, null), d.burnSize),
            burnStyle = enumOr(prefs.getString(K_BURN_STYLE, null), d.burnStyle),
            burnMap = prefs.getBoolean(K_BURN_MAP, d.burnMap),
            burnMapLayer = enumOr(prefs.getString(K_BURN_MAP_LAYER, null), d.burnMapLayer),
            burnMapZoom = prefs.getInt(K_BURN_MAP_ZOOM, d.burnMapZoom).takeIf { it in MAP_ZOOM_CHOICES } ?: d.burnMapZoom,
            burnMapHeadingUp = prefs.getBoolean(K_BURN_MAP_HEADING_UP, d.burnMapHeadingUp),
            burnMapSize = enumOr(prefs.getString(K_BURN_MAP_SIZE, null), d.burnMapSize),
            burnMapPosition = enumOr(prefs.getString(K_BURN_MAP_POS, null), d.burnMapPosition),
            burnMapTrack = enumOr(prefs.getString(K_BURN_MAP_TRACK, null), d.burnMapTrack),
            hudStyle = enumOr(prefs.getString(K_HUD_STYLE, null), d.hudStyle),
            hudCompass = prefs.getBoolean(K_HUD_COMPASS, d.hudCompass),
            hudG = prefs.getBoolean(K_HUD_G, d.hudG),
            gaugeMaxKmh = prefs.getInt(K_GAUGE_MAX, d.gaugeMaxKmh).takeIf { it in GaugeMath.MAX_CHOICES } ?: d.gaugeMaxKmh,
            impactSensitivity = enumOr(prefs.getString(K_IMPACT, null), d.impactSensitivity),
            autoPause = prefs.getBoolean(K_AUTO_PAUSE, d.autoPause),
            fusion = prefs.getBoolean(K_FUSION, d.fusion),
            positioning = enumOr(prefs.getString(K_POSITIONING, null), d.positioning),
            stopPause = prefs.getBoolean(K_STOP_PAUSE, d.stopPause),
            gpxSource = prefs.getString(K_GPX_SOURCE, null)?.let { n -> GpxSource.entries.firstOrNull { it.name == n } }
                ?: if (prefs.getBoolean(K_GPX_RAW, false)) GpxSource.RAW else d.gpxSource,
            trackSmoothing = prefs.getBoolean(K_TRACK_SMOOTHING, d.trackSmoothing),
            vehicle = enumOr(prefs.getString(K_VEHICLE, null), d.vehicle),
            snsPreset = enumOr(prefs.getString(K_SNS_PRESET, null), d.snsPreset),
            snsCropVertical = prefs.getBoolean(K_SNS_CROP, d.snsCropVertical),
            snsHideSpeed = prefs.getBoolean(K_SNS_HIDE_SPEED, d.snsHideSpeed),
            snsThresholdKmh = prefs.getInt(K_SNS_THRESHOLD, d.snsThresholdKmh).takeIf { it in SNS_THRESHOLDS } ?: d.snsThresholdKmh,
            snsHideLatLon = prefs.getBoolean(K_SNS_HIDE_LATLON, d.snsHideLatLon),
            snsHideMap = prefs.getBoolean(K_SNS_HIDE_MAP, d.snsHideMap),
            snsShareWarnHidden = prefs.getBoolean(K_SNS_WARN_HIDDEN, d.snsShareWarnHidden),
            photoExifLocation = prefs.getBoolean(K_PHOTO_EXIF_LOC, d.photoExifLocation),
            photoBurnIn = prefs.getBoolean(K_PHOTO_BURN, d.photoBurnIn),
            gpxPhotos = prefs.getBoolean(K_GPX_PHOTOS, d.gpxPhotos),
            cardHideMax = prefs.getBoolean(K_CARD_HIDE_MAX, d.cardHideMax),
            cardHideOverThreshold = prefs.getBoolean(K_CARD_HIDE_OVER, d.cardHideOverThreshold),
            parkWalkTrack = prefs.getBoolean(K_PARK_WALK, d.parkWalkTrack),
            tripMapRatio = prefs.getFloat(K_TRIP_MAP_RATIO, d.tripMapRatio).coerceIn(MAP_RATIO_MIN, MAP_RATIO_MAX),
            summaryMapRatio = prefs.getFloat(K_SUMMARY_MAP_RATIO, d.summaryMapRatio).coerceIn(MAP_RATIO_MIN, MAP_RATIO_MAX),
            keepScreenOn = prefs.getBoolean(K_SCREEN_ON, d.keepScreenOn),
            restrictWhileMoving = prefs.getBoolean(K_RESTRICT, d.restrictWhileMoving),
            updateCheck = prefs.getBoolean(K_UPDATE_CHECK, d.updateCheck),
            safetyAccepted = prefs.getBoolean(K_SAFETY, d.safetyAccepted),
            heatNoticeShown = prefs.getBoolean(K_HEAT, d.heatNoticeShown),
        )
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, def: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: def

    companion object {
        val SEGMENT_CHOICES = listOf(1, 3, 5)
        /** 長時間録画の 1 ファイルの長さ [分]（0.7.0〜）。 */
        val LONG_SEGMENT_CHOICES = listOf(10, 15, 30)
        /** debug ビルドだけ足す短い長さ（分割とつなぎをエミュレータで短時間に確かめるため）。 */
        val DEBUG_LONG_SEGMENT_CHOICES = listOf(1)
        private const val K_RECORD_MODE = "record_mode"
        private const val K_LONG_SEGMENT = "long_segment_minutes"
        /** 容量の上限の選択肢 [MB]。 */
        val STORAGE_CHOICES_MB = listOf(2 * 1024, 4 * 1024, 8 * 1024, 16 * 1024, 32 * 1024)
        /** debug ビルドだけ足す小さい上限（容量の削除をエミュレータで短時間に確かめるため）。 */
        val DEBUG_STORAGE_CHOICES_MB = listOf(50, 100)
        val PROTECTED_CHOICES_MB = listOf(1024, 2 * 1024, 4 * 1024, 8 * 1024)
        /** 焼き込む地図のズーム。 */
        val MAP_ZOOM_CHOICES = 13..17
        val DEBUG_PROTECTED_CHOICES_MB = listOf(20)
        /** 「速度を隠す」のしきい値 [km/h]。 */
        val SNS_THRESHOLDS = (40..120 step 5).toList()

        private const val K_FUSION = "fusion"
        private const val K_POSITIONING = "positioning"
        private const val K_STOP_PAUSE = "stop_pause"
        /** 0.7.0 までの「GPX を元の GPS の値で」（読むだけ）。 */
        private const val K_GPX_RAW = "gpx_raw"
        private const val K_GPX_SOURCE = "gpx_source"
        private const val K_TRACK_SMOOTHING = "track_smoothing"
        private const val K_VEHICLE = "vehicle"
        private const val K_SNS_PRESET = "sns_preset"
        private const val K_SNS_CROP = "sns_crop_vertical"
        private const val K_SNS_HIDE_SPEED = "sns_hide_speed"
        private const val K_SNS_THRESHOLD = "sns_threshold_kmh"
        private const val K_SNS_HIDE_LATLON = "sns_hide_latlon"
        private const val K_SNS_HIDE_MAP = "sns_hide_map"
        private const val K_SNS_WARN_HIDDEN = "sns_share_warn_hidden"
        private const val K_PHOTO_EXIF_LOC = "photo_exif_location"
        private const val K_PHOTO_BURN = "photo_burn_in"
        private const val K_GPX_PHOTOS = "gpx_photos"
        private const val K_CARD_HIDE_MAX = "card_hide_max"
        private const val K_CARD_HIDE_OVER = "card_hide_over_threshold"
        private const val K_PARK_WALK = "park_walk_track"
        private const val K_TRIP_MAP_RATIO = "trip_map_ratio"
        private const val K_SUMMARY_MAP_RATIO = "summary_map_ratio"
        /** 地図の割合の範囲（つまみで動かせる範囲）。 */
        const val MAP_RATIO_MIN = 0.3f
        const val MAP_RATIO_MAX = 0.85f
        private const val K_VIDEO_ORIENT = "video_orientation"
        private const val K_OVERLAY_MODE = "overlay_mode"
        private const val K_HIDE_DASHES = "hide_speed_dashes"
        private const val K_GALLERY_OVERLAY = "gallery_overlay"

        private const val K_MAP = "map_kind"
        private const val K_START = "start_choice"
        private const val K_LAST_VIDEO = "last_start_with_video"
        private const val K_SEGMENT = "segment_minutes"
        private const val K_LIMIT = "storage_limit_mb"
        private const val K_PROTECTED_LIMIT = "protected_limit_mb"
        private const val K_QUALITY = "quality"
        private const val K_FPS = "fps"
        private const val K_AUDIO = "audio"
        private const val K_BURN = "burn_in"
        private const val K_BURN_DATE = "burn_date"
        private const val K_BURN_SPEED = "burn_speed"
        private const val K_BURN_LATLON = "burn_latlon"
        private const val K_BURN_HEADING = "burn_heading"
        private const val K_BURN_G = "burn_g"
        private const val K_BURN_APP = "burn_app"
        private const val K_BURN_POS = "burn_position"
        private const val K_BURN_SIZE = "burn_size"
        private const val K_BURN_STYLE = "burn_style"
        private const val K_BURN_MAP = "burn_map"
        private const val K_BURN_MAP_LAYER = "burn_map_layer"
        private const val K_BURN_MAP_ZOOM = "burn_map_zoom"
        private const val K_BURN_MAP_HEADING_UP = "burn_map_heading_up"
        private const val K_BURN_MAP_SIZE = "burn_map_size"
        private const val K_BURN_MAP_POS = "burn_map_position"
        private const val K_BURN_MAP_TRACK = "burn_map_track"
        private const val K_HUD_STYLE = "hud_style"
        private const val K_HUD_COMPASS = "hud_compass"
        private const val K_HUD_G = "hud_g"
        private const val K_GAUGE_MAX = "gauge_max_kmh"
        private const val K_IMPACT = "impact_sensitivity"
        private const val K_AUTO_PAUSE = "auto_pause"
        private const val K_SCREEN_ON = "keep_screen_on"
        private const val K_RESTRICT = "restrict_while_moving"
        private const val K_UPDATE_CHECK = "update_check"
        private const val K_SAFETY = "safety_accepted"
        private const val K_HEAT = "heat_notice_shown"
    }
}
